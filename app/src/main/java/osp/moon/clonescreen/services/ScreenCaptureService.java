package osp.moon.clonescreen.services;

import static android.app.Activity.RESULT_OK;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager; // Добавлен импорт
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat; // Добавлен импорт
import androidx.core.content.ContextCompat; // Добавлен импорт

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.customviews.BorderView;

public class ScreenCaptureService extends Service {

    private static final String TAG = ScreenCaptureService.class.getName();

    public static final String ACTION_SHOW_BORDER_GREEN = "osp.moon.clonescreen.SHOW_BORDER_GREEN";
    public static final String ACTION_HIDE_BORDER = "osp.moon.clonescreen.HIDE_BORDER";

    private static final int SERVICE_ID = 123;
    public static final String ACTION_PREPARE = "osp.moon.clonescreen.PREPARE";
    public static final String ACTION_START = "osp.moon.clonescreen.START";
    public static final String ACTION_STOP = "osp.moon.clonescreen.STOP";

    // Используем только поле экземпляра для MediaProjection
    private MediaProjection currentMediaProjectionInstance;
    private MediaProjection.Callback mediaProjectionCallback;
    private MediaProjectionManager mediaProjectionManager;


    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private int screenWidth = 0, screenHeight = 0, screenDpi = 0;
    private static final int BIT_RATE = 6000000;
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 2;

    private MediaCodec videoEncoder;
    private VirtualDisplay virtualDisplay;
    private Surface inputSurface;
    private Thread workerThread;
    private TcpServer tcpServer;
    private Thread resolutionChangeDetector;

    private WindowManager windowManager;
    private BorderView borderView;
    private WindowManager.LayoutParams borderViewParams;
    private Handler mainThreadHandler;

    private final Object encoderLock = new Object();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private byte[] lastSps = null;
    private byte[] lastPps = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Сервис создан.");
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mainThreadHandler = new Handler(Looper.getMainLooper());
        mediaProjectionManager = ContextCompat.getSystemService(this, MediaProjectionManager.class);
        if (mediaProjectionManager == null) {
            Log.e(TAG, "onCreate: MediaProjectionManager не доступен!");
            // Это критично, сервис не сможет работать без него
            // stopSelf(); // Можно рассмотреть остановку здесь
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: Получен пустой intent или action.");
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: Получена команда: " + action);

        switch (action) {
            case ACTION_PREPARE:
                Log.d(TAG, "onStartCommand: Обработка ACTION_PREPARE.");
                startForegroundService();
                break;
            case ACTION_START:
                Log.i(TAG, "onStartCommand (ACTION_START): Вход в обработку."); // Изменен уровень лога для ясности
                if (intent != null && intent.getExtras() != null) {
                    for (String key : intent.getExtras().keySet()) {
                        Log.d(TAG, "onStartCommand (ACTION_START): Extra key=" + key + ", value=" + intent.getExtras().get(key));
                    }
                } else {
                    Log.w(TAG, "onStartCommand (ACTION_START): Intent или его extras is null.");
                }

                if (mediaProjectionManager == null) {
                    Log.e(TAG, "onStartCommand (ACTION_START): mediaProjectionManager is null! Невозможно получить MediaProjection.");
                    Toast.makeText(this, "Ошибка сервиса: MediaProjectionManager не инициализирован.", Toast.LENGTH_LONG).show();
                    stopCaptureAndSelf();
                    return START_NOT_STICKY;
                }

                boolean hasCode = intent.hasExtra("media_projection_result_code");
                boolean hasData = intent.hasExtra("media_projection_result_data");
                Log.d(TAG, "onStartCommand (ACTION_START): hasExtra 'media_projection_result_code'? " + hasCode);
                Log.d(TAG, "onStartCommand (ACTION_START): hasExtra 'media_projection_result_data'? " + hasData);

                if (hasCode && hasData) {
                    int resultCodeFromIntent = intent.getIntExtra("media_projection_result_code", -999); // Используем другое значение по умолчанию для отладки
                    Intent resultDataFromIntent = intent.getParcelableExtra("media_projection_result_data");

                    Log.d(TAG, "onStartCommand (ACTION_START): resultCode from Intent = " + resultCodeFromIntent);
                    Log.d(TAG, "onStartCommand (ACTION_START): resultData from Intent is null? " + (resultDataFromIntent == null));

                    // ИСПРАВЛЕННОЕ УСЛОВИЕ:
                    if (resultCodeFromIntent == RESULT_OK && resultDataFromIntent != null) {
                        Log.d(TAG, "onStartCommand (ACTION_START): resultCode (" + resultCodeFromIntent + ") is RESULT_OK и resultData не null. Продолжаем.");

                        currentMediaProjectionInstance = mediaProjectionManager.getMediaProjection(resultCodeFromIntent, resultDataFromIntent);
                        if (currentMediaProjectionInstance == null) {
                            Log.e(TAG, "onStartCommand: Не удалось получить MediaProjection из данных Intent (mediaProjectionManager.getMediaProjection вернул null).");
                            Toast.makeText(this, "Ошибка: Не удалось начать захват экрана (getMediaProjection null).", Toast.LENGTH_LONG).show();
                            stopCaptureAndSelf();
                            return START_NOT_STICKY;
                        }
                        Log.i(TAG, "onStartCommand: MediaProjection успешно получен из Intent.");
                    } else {
                        Log.e(TAG, "onStartCommand: Невалидные данные для MediaProjection. resultCode=" + resultCodeFromIntent + " (ожидался " + RESULT_OK + "), resultData isNull=" + (resultDataFromIntent == null));
                        Toast.makeText(this, "Ошибка: Некорректные данные для старта захвата (детали в логе).", Toast.LENGTH_LONG).show();
                        stopCaptureAndSelf();
                        return START_NOT_STICKY;
                    }
                } else if (currentMediaProjectionInstance == null) {
                    Log.e(TAG, "onStartCommand: ACTION_START получен, но нет данных (ключей) для MediaProjection и нет существующего экземпляра.");
                    Toast.makeText(this, "Ошибка: MediaProjection не инициализирован (нет ключей).", Toast.LENGTH_LONG).show();
                    stopCaptureAndSelf();
                    return START_NOT_STICKY;
                }

                if (currentMediaProjectionInstance != null && !isRunning.get()) {
                    Log.i(TAG, "onStartCommand: Запускаем startCapture().");
                    startCapture();
                } else {
                    if (currentMediaProjectionInstance == null) Log.e(TAG, "onStartCommand (ACTION_START): currentMediaProjectionInstance все еще null после всех проверок!");
                    if (isRunning.get()) Log.w(TAG, "onStartCommand (ACTION_START): Сервис уже запущен (isRunning=true).");
                }
                break;
            case ACTION_STOP:
                Log.d(TAG, "onStartCommand: Обработка ACTION_STOP.");
                stopCaptureAndSelf(); // Используем новый метод для полной остановки
                break;
            case ACTION_SHOW_BORDER_GREEN:
                Log.d(TAG, "onStartCommand: Обработка ACTION_SHOW_BORDER_GREEN.");
                mainThreadHandler.post(() -> showBorderView(Color.GREEN));
                break;
            case ACTION_HIDE_BORDER:
                Log.d(TAG, "onStartCommand: Обработка ACTION_HIDE_BORDER.");
                mainThreadHandler.post(this::hideBorderView);
                break;
        }
        return START_NOT_STICKY;
    }

    private void startForegroundService() {
        Log.d(TAG, "startForegroundService: Переводим сервис в режим Foreground.");
        Notification notification = getNotification(this, "Подготовка к трансляции..."); // Убедитесь, что R.string.app_name существует
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(SERVICE_ID, notification);
        }
    }

    private void startCapture() {
        Log.i(TAG, "startCapture: Попытка запуска захвата.");
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "startCapture: Захват уже запущен.");
            return;
        }
        Log.d(TAG, "startCapture: isRunning установлен в true.");

        if (borderView == null || borderView.getWindowToken() == null) {
            Log.w(TAG, "startCapture: BorderView не отображается при старте захвата (возможно, будет показан позже).");
        }

        workerThread = new Thread(() -> {
            Log.d(TAG, "workerThread: Поток запущен.");
            try {
                if (currentMediaProjectionInstance == null) {
                    Log.e(TAG, "workerThread: currentMediaProjectionInstance is NULL. Невозможно начать захват.");
                    isRunning.set(false); // Сбросить флаг
                    stopSelf(); // Останавливаем сервис, если не можем начать
                    return;
                }

                mediaProjectionCallback = new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.e(TAG, "!!! MediaProjection.onStop() был вызван системой! Остановка захвата. !!!");
                        // Прямо здесь обнуляем экземпляр проекции, чтобы reconfigureEncoder точно увидел это
                        // и делаем это ДО вызова stopCaptureAndSelf, чтобы isRunning не успел стать false раньше
                        MediaProjection instanceBeingStopped = currentMediaProjectionInstance;
                        currentMediaProjectionInstance = null; // Делаем его null немедленно

                        if (instanceBeingStopped != null) {
                            // Отписываемся от колбэка самого себя, но уже от локальной копии
                            try {
                                instanceBeingStopped.unregisterCallback(this); // 'this' относится к текущему экземпляру mediaProjectionCallback
                            } catch (Exception e) {
                                Log.w(TAG, "MediaProjection.onStop: Ошибка при отписке колбэка от instanceBeingStopped.", e);
                            }
                            // Останавливать instanceBeingStopped.stop() здесь не нужно,
                            // так как система его уже остановила, раз вызвала onStop().
                            // Это будет сделано (или попытается быть сделано) в releaseEncoderAndProjection.
                        }

                        Log.d(TAG, "MediaProjection.onStop: currentMediaProjectionInstance установлен в null.");

                        if (isRunning.get()) { // isRunning все еще может быть true здесь
                            stopCaptureAndSelf();
                        } else {
                            Log.d(TAG, "MediaProjection.onStop: isRunning уже был false, возможно, stopCaptureAndSelf уже вызван.");
                            // На всякий случай, если другой поток вызвал stopCaptureAndSelf, а этот onStop пришел с задержкой,
                            // но currentMediaProjectionInstance уже обнулен.
                        }
                    }
                };
                currentMediaProjectionInstance.registerCallback(mediaProjectionCallback, mainThreadHandler);
                Log.d(TAG, "workerThread: MediaProjection.Callback зарегистрирован.");

                tcpServer = new TcpServer(this);
                tcpServer.start();

                Log.d(TAG, "workerThread: Ожидаем первого подключения клиента...");
                tcpServer.waitForClient(); // Блокирующий вызов
                Log.i(TAG, "workerThread: waitForClient завершился. isRunning=" + isRunning.get() + ", tcpServer.isClientConnected=" + (tcpServer != null && tcpServer.isClientConnected()));

                if (isRunning.get() && tcpServer != null && tcpServer.isClientConnected()) {
                    Log.i(TAG, "workerThread: Первый клиент на месте, конфигурируем кодек.");
                    reconfigureEncoder(); // Первая конфигурация кодека
                    Log.i(TAG, "workerThread: Начальная настройка завершена. Запускаем drainEncoder.");
                    drainEncoder(); // Запускаем основной цикл обработки данных
                } else if (!isRunning.get()) {
                    Log.w(TAG, "workerThread: Сервис был остановлен во время ожидания клиента. Захват не начнется.");
                } else {
                    Log.w(TAG, "workerThread: waitForClient завершился, но клиент не подключен или TCP сервер не готов. Захват может не начаться корректно.");
                    // Если клиент не подключился, но сервис еще работает, возможно, стоит остановить сервис?
                    // stopCaptureAndSelf(); // Раскомментировать, если это желаемое поведение
                }

            } catch (InterruptedException e) {
                Log.w(TAG, "workerThread: Поток был прерван.", e);
                Thread.currentThread().interrupt(); // Восстанавливаем флаг прерывания
            } catch (Exception e) { // Ловим все ошибки, чтобы записать в лог
                Log.e(TAG, "workerThread: КРИТИЧЕСКАЯ ошибка в потоке workerThread.", e);
            } finally {
                Log.i(TAG, "workerThread: Поток workerThread завершает работу. isRunning=" + isRunning.get());
                // Если поток завершился, но сервис еще "думает", что работает (например, из-за необработанного исключения выше),
                // инициируем полную остановку, чтобы освободить ресурсы.
                if (isRunning.get()) {
                    Log.w(TAG, "workerThread: Поток завершился неожиданно, но isRunning все еще true. Инициируем stopCaptureAndSelf.");
                    stopCaptureAndSelf();
                }
            }
        });
        workerThread.setName("ScreenCaptureWorker");
        workerThread.start();
    }


    private void stopCaptureAndSelf() {
        Log.i(TAG, "stopCaptureAndSelf: Начало полной остановки сервиса и его компонентов.");
        if (!isRunning.compareAndSet(true, false)) {
            Log.d(TAG, "stopCaptureAndSelf: Сервис уже был остановлен или в процессе.");
            // Если isRunning уже false, но мы хотим убедиться, что stopSelf() вызван.
            // Проверка getServiceLooper() была неверной. Просто вызываем stopSelf().
            // Система сама разберется, если сервис уже остановлен.
            // Однако, если сервис действительно еще существует (не в процессе уничтожения),
            // повторный вызов stopSelf() не навредит.
            // Но чтобы избежать потенциальных проблем, если сервис уже в процессе полного уничтожения,
            // можно эту часть и вовсе убрать или добавить более специфичную проверку, если это необходимо.
            // Для простоты и избежания лишних вызовов, если isRunning уже false,
            // можно просто выйти, так как основной stopCapture/stopSelf уже должен был быть вызван.
            // Но если мы попали сюда, а isRunning УЖЕ false, значит что-то пошло не так с предыдущей остановкой
            // или это повторный вызов.
            // Чтобы гарантировать остановку, если вдруг предыдущий stopSelf() не сработал:
            Log.d(TAG, "stopCaptureAndSelf: isRunning был false, но вызываем stopSelf() для гарантии, если сервис еще существует.");
            try {
                stopSelf(); // Попытаемся остановить, если он еще не остановлен полностью
            } catch (Exception e) {
                Log.w(TAG, "stopCaptureAndSelf: Исключение при повторном вызове stopSelf() (возможно, сервис уже уничтожается).", e);
            }
            return;
        }
        Log.d(TAG, "stopCaptureAndSelf: isRunning установлен в false.");

        // Порядок важен: сначала прерываем потоки, которые могут использовать ресурсы
        if (resolutionChangeDetector != null) {
            Log.d(TAG, "stopCaptureAndSelf: Прерываем resolutionChangeDetector.");
            resolutionChangeDetector.interrupt();
            try { resolutionChangeDetector.join(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            resolutionChangeDetector = null;
        }

        if (workerThread != null) {
            Log.d(TAG, "stopCaptureAndSelf: Прерываем workerThread.");
            workerThread.interrupt();
            try { workerThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            workerThread = null;
        }

        if (tcpServer != null) {
            Log.d(TAG, "stopCaptureAndSelf: Останавливаем TcpServer.");
            tcpServer.stopServer();
            try { tcpServer.join(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            tcpServer = null;
        }

        releaseEncoderAndProjection(); // Освобождаем кодек и проекцию

        mainThreadHandler.post(this::hideBorderView); // Скрываем рамку

        Log.i(TAG, "stopCaptureAndSelf: Остановка завершена, вызываем stopSelf().");
        stopSelf(); // Останавливаем сам сервис
    }



    private void releaseEncoderAndProjection() {
        Log.d(TAG, "releaseEncoderAndProjection: Начало освобождения.");
        synchronized (encoderLock) {
            if (virtualDisplay != null) {
                virtualDisplay.release(); virtualDisplay = null;
                Log.d(TAG, "releaseEncoderAndProjection: VirtualDisplay освобожден.");
            }
            if (inputSurface != null) {
                inputSurface.release(); inputSurface = null;
                Log.d(TAG, "releaseEncoderAndProjection: InputSurface освобожден.");
            }
            if (videoEncoder != null) {
                try {
                    videoEncoder.stop();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "releaseEncoderAndProjection: Ошибка при videoEncoder.stop() (возможно, уже остановлен).", e);
                }
                videoEncoder.release(); videoEncoder = null;
                Log.d(TAG, "releaseEncoderAndProjection: VideoEncoder освобожден.");
            }
        }

        if (currentMediaProjectionInstance != null) {
            Log.d(TAG, "releaseEncoderAndProjection: Остановка currentMediaProjectionInstance.");
            if (mediaProjectionCallback != null) {
                try {
                    currentMediaProjectionInstance.unregisterCallback(mediaProjectionCallback);
                } catch (Exception e) { /* Игнорируем, если уже отписан или ошибка */ }
                mediaProjectionCallback = null;
            }
            try {
                currentMediaProjectionInstance.stop();
            } catch (Exception e) { /* Игнорируем, если уже остановлен или ошибка */ }
            currentMediaProjectionInstance = null;
            Log.d(TAG, "releaseEncoderAndProjection: currentMediaProjectionInstance обнулен.");
        }
        // Если вы использовали статическое поле в ServerFragment, его здесь тоже нужно обнулить,
        // но лучше, чтобы ServerFragment сам управлял своим экземпляром MediaProjection.
        // ScreenCaptureService.mediaProjectionStatic = null; // Если было статическое поле
    }


    private void reconfigureEncoder() throws IOException {
        synchronized (encoderLock) {
            Log.i(TAG, "reconfigureEncoder: НАЧАЛО. isRunning=" + isRunning.get() + ", mediaProjection=" + (currentMediaProjectionInstance != null));
            if (!isRunning.get() || currentMediaProjectionInstance == null) {
                Log.e(TAG, "reconfigureEncoder: ОТМЕНА. Сервис не запущен или currentMediaProjectionInstance is null (возможно, уже остановлен).");
                return;
            }

            // Освобождаем старые ресурсы ПЕРЕД получением новых размеров
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
            if (videoEncoder != null) {
                try { videoEncoder.stop(); } catch (Exception e) { Log.w(TAG, "reconfigureEncoder: Ошибка при videoEncoder.stop() (старый).", e); }
                videoEncoder.release(); videoEncoder = null;
                Log.d(TAG, "reconfigureEncoder: Старые кодек, surface, virtualDisplay освобождены.");
            }

            Log.d(TAG, "reconfigureEncoder: 1. Получаем новые размеры экрана.");
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) { Log.e(TAG, "reconfigureEncoder: WindowManager is null!"); throw new IOException("WindowManager is null"); }
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDpi = metrics.densityDpi;
            Log.i(TAG, "reconfigureEncoder: Новые размеры: " + screenWidth + "x" + screenHeight + " DPI: " + screenDpi);

            if (tcpServer != null && tcpServer.isClientConnected()) {
                Log.i(TAG, "reconfigureEncoder: ОТПРАВКА ПАКЕТА ТИП 2 клиенту: " + screenWidth + "x" + screenHeight);
                tcpServer.sendResolution(screenWidth, screenHeight);
            } else {
                Log.w(TAG, "reconfigureEncoder: Клиент не подключен, разрешение не отправлено на этом этапе.");
            }

            Log.d(TAG, "reconfigureEncoder: 4. Создаем НОВЫЙ кодек.");
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, screenWidth, screenHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

            try {
                videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                inputSurface = videoEncoder.createInputSurface();
                videoEncoder.start();
                Log.d(TAG, "reconfigureEncoder: Новый кодек настроен и запущен. InputSurface создан.");
            } catch (Exception e_codec) {
                Log.e(TAG, "reconfigureEncoder: КРИТИЧЕСКАЯ ОШИБКА при создании/настройке кодека.", e_codec);
                if (videoEncoder != null) { videoEncoder.release(); videoEncoder = null; }
                if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
                throw new IOException("Failed to create/configure MediaCodec", e_codec); // Перебрасываем, чтобы прервать
            }


            Log.d(TAG, "reconfigureEncoder: 5. Настраиваем VirtualDisplay.");
            // ПОВТОРНАЯ ПРОВЕРКА ПЕРЕД СОЗДАНИЕМ VIRTUAL DISPLAY
            synchronized (encoderLock) { // Убедимся, что читаем свежие значения
                if (!isRunning.get() || currentMediaProjectionInstance == null) {
                    Log.e(TAG, "reconfigureEncoder: ОТМЕНА перед createVirtualDisplay. isRunning=" + isRunning.get() + ", currentMediaProjectionInstance is null? " + (currentMediaProjectionInstance == null));
                    if (videoEncoder != null) { try { videoEncoder.stop(); } finally { videoEncoder.release(); videoEncoder = null;}}
                    if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
                    return; // ВАЖНО: выходим, если условия не выполнены
                }
            }
            try {
                Thread.sleep(50); // <--- ДОБАВИТЬ НЕБОЛЬШУЮ ЗАДЕРЖКУ (например, 50-100 мс)
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "reconfigureEncoder: Прервано во время тестовой задержки.");
                if (isRunning.get()) stopCaptureAndSelf();
                throw new IOException("Interrupted during test delay", ie);
            }
            try {
                // Если currentMediaProjectionInstance все еще не null (маловероятно, но на всякий случай)
                if (currentMediaProjectionInstance == null) {
                    Log.e(TAG, "reconfigureEncoder: КРИТИЧЕСКАЯ СИТУАЦИЯ! currentMediaProjectionInstance стал null ПОСЛЕ ПРОВЕРКИ, ПЕРЕД createVirtualDisplay!");
                    if (isRunning.get()) stopCaptureAndSelf();
                    throw new IOException("MediaProjection became null unexpectedly before createVirtualDisplay");
                }
                Log.d(TAG, "reconfigureEncoder: Попытка currentMediaProjectionInstance.createVirtualDisplay(...)");
                virtualDisplay = currentMediaProjectionInstance.createVirtualDisplay("ScreenCapture",
                        screenWidth, screenHeight, screenDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        inputSurface, null, null);
                Log.i(TAG, "reconfigureEncoder: Новый VirtualDisplay создан.");
            } catch (SecurityException e_sec) {
                Log.e(TAG, "reconfigureEncoder: SecurityException при создании VirtualDisplay! MediaProjection, вероятно, уже невалиден.", e_sec);
                if (isRunning.get()) stopCaptureAndSelf(); // Если stopCapture еще не был вызван
                throw e_sec; // Перебрасываем, чтобы прервать reconfigureEncoder
            } catch (Exception e_vd) {
                Log.e(TAG, "reconfigureEncoder: Общая ошибка при создании VirtualDisplay.", e_vd);
                if (isRunning.get()) stopCaptureAndSelf();
                throw new IOException("Failed to create VirtualDisplay", e_vd);
            }
            Log.i(TAG, "reconfigureEncoder: ЗАВЕРШЕНИЕ.");
        }
    }

    private void drainEncoder() {
        Log.d(TAG, "drainEncoder: Запускаем resolutionChangeDetector.");
        if (resolutionChangeDetector != null && resolutionChangeDetector.isAlive()) {
            Log.w(TAG, "drainEncoder: resolutionChangeDetector уже запущен, не перезапускаем.");
        } else {
            resolutionChangeDetector = new Thread(() -> {
                Log.d(TAG, "resolutionChangeDetector: Поток запущен.");
                int lastW = 0, lastH = 0;
                // Инициализация lastW, lastH текущими значениями, если они уже есть
                synchronized (encoderLock) { // Доступ к screenWidth/Height должен быть синхронизирован
                    if (screenWidth > 0 && screenHeight > 0) {
                        lastW = screenWidth;
                        lastH = screenHeight;
                    } else { // Если еще не было конфигурации, получаем текущие
                        try {
                            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                            if (wm != null) {
                                DisplayMetrics m = new DisplayMetrics();
                                wm.getDefaultDisplay().getRealMetrics(m);
                                lastW = m.widthPixels;
                                lastH = m.heightPixels;
                                Log.d(TAG, "resolutionChangeDetector: Начальные размеры: " + lastW + "x" + lastH);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "resolutionChangeDetector: Ошибка получения начальных размеров.", e);
                        }
                    }
                }

                while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1000); // Проверяем раз в секунду
                        if (!isRunning.get()) break; // Дополнительная проверка после sleep

                        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                        if (wm == null) { Log.w(TAG, "resolutionChangeDetector: WindowManager is null, пропуск проверки."); continue; }

                        DisplayMetrics currentMetrics = new DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(currentMetrics);

                        if (currentMetrics.widthPixels != lastW || currentMetrics.heightPixels != lastH) {
                            Log.i(TAG, "!!! resolutionChangeDetector: ОБНАРУЖЕНО ИЗМЕНЕНИЕ РАЗРЕШЕНИЯ! " +
                                    lastW + "x" + lastH + " -> " + currentMetrics.widthPixels + "x" + currentMetrics.heightPixels + " !!!");
                            lastW = currentMetrics.widthPixels; // Обновляем
                            lastH = currentMetrics.heightPixels;
                            if (isRunning.get()) {
                                try {
                                    Log.d(TAG, "resolutionChangeDetector: Вызов reconfigureEncoder().");
                                    reconfigureEncoder();
                                } catch (Exception e_reconfig) {
                                    Log.e(TAG, "resolutionChangeDetector: Ошибка из reconfigureEncoder.", e_reconfig);
                                    if (isRunning.get()) stopCaptureAndSelf(); // Если reconfigureEncoder упал, останавливаем все
                                    break;
                                }
                            } else {
                                Log.d(TAG, "resolutionChangeDetector: isRunning false, reconfigureEncoder не вызван.");
                            }
                        }
                    } catch (InterruptedException e) {
                        Log.d(TAG, "resolutionChangeDetector: Поток прерван.");
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) { // Ловим другие неожиданные ошибки
                        Log.e(TAG, "resolutionChangeDetector: Общая ошибка в цикле.", e);
                    }
                }
                Log.d(TAG, "resolutionChangeDetector: Поток остановлен. isRunning=" + isRunning.get());
            });
            resolutionChangeDetector.setName("ResolutionDetector");
            resolutionChangeDetector.start();
        }

        Log.d(TAG, "drainEncoder: Начало цикла извлечения данных.");
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
            synchronized (encoderLock) {
                if (videoEncoder == null || !isRunning.get()) {
                    if (!isRunning.get()) { Log.d(TAG, "drainEncoder: isRunning=false, выход из цикла."); break; }
                    try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                try {
                    int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000);

                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // Ничего
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "drainEncoder: Формат кодировщика изменился.");
                        MediaFormat newFormat = videoEncoder.getOutputFormat();
                        Log.d(TAG, "drainEncoder: Новый формат: " + newFormat);
                        ByteBuffer spsBuffer = newFormat.getByteBuffer("csd-0");
                        ByteBuffer ppsBuffer = newFormat.getByteBuffer("csd-1");

                        lastSps = null; lastPps = null;
                        if (spsBuffer != null) {
                            lastSps = new byte[spsBuffer.remaining()];
                            spsBuffer.get(lastSps); spsBuffer.rewind();
                            Log.d(TAG, "drainEncoder: SPS сохранен, " + lastSps.length + " bytes.");
                        } else Log.w(TAG, "drainEncoder: spsBuffer is null в INFO_OUTPUT_FORMAT_CHANGED.");

                        if (ppsBuffer != null) {
                            lastPps = new byte[ppsBuffer.remaining()];
                            ppsBuffer.get(lastPps); ppsBuffer.rewind();
                            Log.d(TAG, "drainEncoder: PPS сохранен, " + lastPps.length + " bytes.");
                        } else Log.w(TAG, "drainEncoder: ppsBuffer is null в INFO_OUTPUT_FORMAT_CHANGED.");

                        if (tcpServer != null && tcpServer.isClientConnected() && lastSps != null && lastPps != null) {
                            Log.i(TAG, "drainEncoder: Отправка SPS/PPS клиенту при INFO_OUTPUT_FORMAT_CHANGED.");
                            tcpServer.sendData(lastSps, true);
                            tcpServer.sendData(lastPps, true);
                        } else {
                            Log.w(TAG, "drainEncoder: Клиент не подключен или SPS/PPS отсутствуют, не отправляем при INFO_OUTPUT_FORMAT_CHANGED.");
                        }

                    } else if (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                        if (outputBuffer == null) {
                            Log.e(TAG, "drainEncoder: videoEncoder.getOutputBuffer(" + outputBufferIndex + ") вернул null!");
                        } else {
                            if (bufferInfo.size > 0) { // Отправляем, только если есть данные
                                if (tcpServer != null && tcpServer.isClientConnected()) {
                                    byte[] data = new byte[bufferInfo.size];
                                    outputBuffer.get(data, bufferInfo.offset, bufferInfo.size);
                                    tcpServer.sendData(data, (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0);
                                }
                            }
                            videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                        }
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "drainEncoder: IllegalStateException в цикле. isRunning=" + isRunning.get(), e);
                    if (!isRunning.get()) break;
                    Log.w(TAG, "drainEncoder: Попали в IllegalStateException, возможно, кодек был перенастроен. Остановка цикла drainEncoder.");
                    break; // Выходим из цикла, чтобы избежать дальнейших ошибок
                } catch (Exception e_drain) {
                    Log.e(TAG, "drainEncoder: Общая ошибка в цикле.", e_drain);
                    if (!isRunning.get()) break; // Если сервис останавливается, выходим
                }
            } // end synchronized
        } // end while
        Log.i(TAG, "drainEncoder: Цикл извлечения данных завершен. isRunning=" + isRunning.get());
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy: Сервис уничтожается.");
        // Гарантируем, что stopCaptureAndSelf вызывается, если сервис еще не был остановлен корректно.
        if (isRunning.get()) {
            Log.w(TAG, "onDestroy: isRunning все еще true при уничтожении сервиса. Вызов stopCaptureAndSelf.");
            stopCaptureAndSelf();
        } else {
            Log.d(TAG, "onDestroy: isRunning уже false. Ресурсы должны быть освобождены.");
            // Дополнительная проверка и попытка скрыть рамку, если она еще есть
            if (borderView != null && borderView.getWindowToken() != null && windowManager != null) {
                mainThreadHandler.post(this::hideBorderView);
            }
        }
    }

    // --- Методы для управления BorderView ---
    private void showBorderView(int color) {
        if (borderView != null) { // Если view уже существует
            borderView.setBorderColor(color);
            if (borderView.getWindowToken() == null) { // Но не добавлено в WindowManager
                try {
                    if (windowManager != null && borderViewParams != null) {
                        windowManager.addView(borderView, borderViewParams);
                        Log.d(TAG, "BorderView добавлен в WindowManager (повторно).");
                    } else {
                        Log.e(TAG, "showBorderView: windowManager или borderViewParams is null при повторном добавлении.");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при повторном добавлении BorderView в WindowManager", e);
                }
            } else { // Уже добавлено, просто цвет обновили
                Log.d(TAG, "BorderView уже был добавлен, цвет обновлен.");
            }
            return;
        }

        // View еще не создан
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Нет разрешения на рисование поверх других окон. Рамка не будет показана.");
            return;
        }
        if (windowManager == null) {
            Log.e(TAG, "showBorderView: WindowManager is null. Невозможно показать рамку.");
            return;
        }

        borderView = new BorderView(this);
        borderView.setBorderColor(color);
        float borderWidthDp = 5f;
        float borderWidthPx = borderWidthDp * getResources().getDisplayMetrics().density;
        borderView.setBorderWidth(borderWidthPx);

        int layoutParamsType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        borderViewParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            windowManager.addView(borderView, borderViewParams);
            Log.d(TAG, "BorderView УСПЕШНО добавлен в WindowManager.");
        } catch (Exception e) {
            Log.e(TAG, "КРИТИЧЕСКАЯ ОШИБКА при добавлении BorderView в WindowManager", e);
            borderView = null; // Сбрасываем, если не удалось добавить
            borderViewParams = null;
        }
    }

    private void hideBorderView() {
        Log.d(TAG, "hideBorderView: Попытка скрыть рамку.");
        if (borderView != null && borderView.getWindowToken() != null && windowManager != null) {
            try {
                windowManager.removeView(borderView);
                Log.d(TAG, "BorderView удален из WindowManager.");
            } catch (Exception e) {
                Log.e(TAG, "Ошибка при удалении BorderView из WindowManager", e);
            }
        } else {
            if (borderView == null) Log.d(TAG, "hideBorderView: borderView is null.");
            else if (borderView.getWindowToken() == null) Log.d(TAG, "hideBorderView: borderView не был добавлен в window (token is null).");
            if (windowManager == null) Log.d(TAG, "hideBorderView: windowManager is null.");
        }
        borderView = null;
        borderViewParams = null;
    }

    private void updateBorderColor(int color) {
        if (borderView != null && borderView.getWindowToken() != null) {
            borderView.setBorderColor(color);
            Log.d(TAG, "Цвет BorderView обновлен на: " + (color == Color.RED ? "RED" : (color == Color.GREEN ? "GREEN" : "UNKNOWN")));
        } else {
            Log.d(TAG, "Попытка обновить цвет, но BorderView не существует или не прикреплен. Попытка показать новый.");
            showBorderView(color); // Если рамки нет, а нужно обновить цвет, создаем ее
        }
    }

    public void onClientConnectedStateChanged(boolean isConnected) {
        mainThreadHandler.post(() -> {
            Log.d(TAG, "onClientConnectedStateChanged (UI Thread): isConnected=" + isConnected + ", isRunning=" + isRunning.get());
            if (!isRunning.get()) {
                if (isConnected) {
                    Log.w(TAG, "onClientConnectedStateChanged: Получено isConnected=true, но сервис НЕ запущен. Игнор UI обновления для RED.");
                } else {
                    Log.d(TAG, "onClientConnectedStateChanged: isConnected=false, сервис НЕ запущен. Скрываем рамку, если есть.");
                    hideBorderView(); // Если сервис остановлен, а клиент отключается, рамки быть не должно
                }
                return;
            }

            // Сервис запущен (isRunning = true)
            if (isConnected) {
                Log.d(TAG, "UI Thread: Клиент ПОДКЛЮЧЕН. Обновляем цвет рамки на RED.");
                updateBorderColor(Color.RED);
            } else {
                Log.d(TAG, "UI Thread: Клиент ОТКЛЮЧЕН. Сервис работает. Обновляем цвет рамки на GREEN.");
                updateBorderColor(Color.GREEN);
            }
        });
    }

    public int getCurrentScreenWidth() { return screenWidth; }
    public int getCurrentScreenHeight() { return screenHeight; }
    public byte[] getLastSps() { return lastSps; }
    public byte[] getLastPps() { return lastPps; }

    public void requestSyncFrame() {
        synchronized (encoderLock) {
            if (videoEncoder != null && isRunning.get()) {
                try {
                    Bundle params = new Bundle();
                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    videoEncoder.setParameters(params);
                    Log.i(TAG, "requestSyncFrame: Ключевой кадр успешно запрошен.");
                } catch (Exception e) {
                    Log.e(TAG, "requestSyncFrame: Ошибка при запросе ключевого кадра.", e);
                }
            } else {
                Log.w(TAG, "requestSyncFrame: Не удалось запросить, кодек не готов или сервис не запущен.");
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static Notification getNotification(final Context context, String contentText) {
        String NOTIFICATION_CHANNEL_ID = "osp.moon.clonescreen_service_channel"; // Уникальный ID канала
        String channelName = "Screen Mirroring Service"; // Отображаемое имя канала
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_MIN);
        chan.setDescription("Channel for screen mirroring service foreground notification");
        chan.setLightColor(Color.CYAN);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(chan);
        }

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID);
        return notificationBuilder.setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher) // Убедитесь, что эта иконка существует
                .setContentTitle(context.getString(R.string.app_name)) // Используйте строковый ресурс
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Скрывать на заблокированном экране, если не хотим показывать детали
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

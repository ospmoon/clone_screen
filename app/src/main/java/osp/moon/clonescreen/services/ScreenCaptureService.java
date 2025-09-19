package osp.moon.clonescreen.services;

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
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.customviews.BorderView;

public class ScreenCaptureService extends Service {

    private static final String TAG = ScreenCaptureService.class.getName();

    public static final String ACTION_SHOW_BORDER_GREEN = "osp.moon.clonescreen.SHOW_BORDER_GREEN";
    public static final String ACTION_SHOW_BORDER_RED = "osp.moon.clonescreen.SHOW_BORDER_RED";
    public static final String ACTION_HIDE_BORDER = "osp.moon.clonescreen.HIDE_BORDER";

    private static final int SERVICE_ID = 123;
    public static final String ACTION_PREPARE = "osp.moon.clonescreen.PREPARE";
    public static final String ACTION_START = "osp.moon.clonescreen.START";
    public static final String ACTION_STOP = "osp.moon.clonescreen.STOP";

    public static MediaProjection mediaProjection;

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private int screenWidth, screenHeight, screenDpi;
    private static final int BIT_RATE = 6000000, FRAME_RATE = 30, I_FRAME_INTERVAL = 2;

    private MediaCodec videoEncoder;
    private VirtualDisplay virtualDisplay;
    private Surface inputSurface;
    private Thread workerThread;
    private TcpServer tcpServer;
    private Thread resolutionChangeDetector;
    private WindowManager windowManager;
    private BorderView borderView;
    private Handler mainThreadHandler;
    private WindowManager.LayoutParams borderViewParams;

    // --- КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: ОБЪЕКТ ДЛЯ БЛОКИРОВКИ ---
    private final Object encoderLock = new Object();

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Сервис создан.");
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mainThreadHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: Получен пустой intent или action.");
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: Получена команда: " + action);
        switch (action) {
            case ACTION_PREPARE:
                Log.d(TAG, "onStartCommand: Обработка ACTION_PREPARE.");
                startForegroundService();
                break;
            case ACTION_START:
                if (mediaProjection != null && !isRunning.get()) {
                    Log.d(TAG, "onStartCommand: Обработка ACTION_START.");
                    startCapture();
                } else {
                    Log.e(TAG, "onStartCommand: ACTION_START получен, но mediaProjection=null или сервис уже запущен!");
                }
                break;
            case ACTION_STOP:
                Log.d(TAG, "onStartCommand: Обработка ACTION_STOP.");
                stopCapture();
                break;
            case ACTION_SHOW_BORDER_GREEN:
                Log.d(TAG, "onStartCommand: Обработка ACTION_SHOW_BORDER_GREEN.");
                mainThreadHandler.post(() -> showBorderView(Color.GREEN)); // Обернули
                break;
            case ACTION_SHOW_BORDER_RED: // Этот case больше не нужен, т.к. управляется onClientConnectedStateChanged
                // Log.d(TAG, "onStartCommand: Обработка ACTION_SHOW_BORDER_RED.");
                //mainThreadHandler.post(() -> updateBorderColor(Color.RED)); // Обернули
                break;
            case ACTION_HIDE_BORDER:
                Log.d(TAG, "onStartCommand: Обработка ACTION_HIDE_BORDER.");
                mainThreadHandler.post(this::hideBorderView); // Обернули (ссылка на метод)
                break;

        }
        return START_NOT_STICKY;
    }

    private void startForegroundService() {
        Log.d(TAG, "startForegroundService: Переводим сервис в режим Foreground.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification notification = getNotification(this, "Подготовка к трансляции...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(SERVICE_ID, notification);
            }
        } else {
            startForeground(SERVICE_ID, new Notification());
        }
    }

    private void startCapture() {
        Log.d(TAG, "startCapture: Устанавливаем isRunning=true и запускаем workerThread.");
        isRunning.set(true);

        // Рамка УЖЕ должна быть показана командой из ServerFragment (ACTION_SHOW_BORDER_GREEN)
        // Если здесь ее нет, значит что-то пошло не так с разрешением или командой.
        // Можно добавить проверку:
        if (borderView == null || borderView.getWindowToken() == null) {
            Log.w(TAG, "startCapture: BorderView не отображается при старте захвата. Возможно, нет разрешения.");
            // Можно попробовать показать здесь еще раз, но это дублирование логики.
            // showBorderView(Color.GREEN);
        }

        workerThread = new Thread(() -> {
            Log.d(TAG, "workerThread: Поток запущен.");
            try {
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.e(TAG, "!!! MediaProjection.onStop() был вызван системой! Это приведет к остановке. !!!");
                        if(isRunning.get()){
                            stopCapture();
                        }
                    }
                }, null);

                Log.d(TAG, "workerThread: Создаем и запускаем TcpServer.");
                tcpServer = new TcpServer(this);
                tcpServer.start();

                Log.d(TAG, "workerThread: Ожидаем подключения клиента...");
                tcpServer.waitForClient();
                Log.d(TAG, "workerThread: Клиент подключился!");

                reconfigureEncoder();

                Log.i(TAG, "workerThread: Настройка завершена. Запускаем drainEncoder.");
                drainEncoder();

            } catch (Exception e) {
                if (isRunning.get()) Log.e(TAG, "workerThread: Критическая ошибка в потоке.", e);
            } finally {
                Log.d(TAG, "workerThread: Поток завершает работу, освобождаем все ресурсы.");
                releaseAllResources();
            }
        });
        workerThread.start();
    }

    private void stopCapture() {
        Log.d(TAG, "stopCapture: Начало полной остановки сервиса.");
        if (!isRunning.getAndSet(false)) {
            Log.d(TAG, "stopCapture: Сервис уже был в процессе остановки.");
            return;
        }
        stopSelf();
    }

    private void reconfigureEncoder() throws IOException {
        // --- КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: БЛОКИРУЕМ ДОСТУП ---
        synchronized (encoderLock) {
            Log.i(TAG, "reconfigureEncoder: НАЧАЛО ПЕРЕНАСТРОЙКИ КОДЕКА (ЗАБЛОКИРОВАНО).");
            if (!isRunning.get()) {
                Log.e(TAG, "reconfigureEncoder: Перенастройка отменена, сервис не запущен.");
                return;
            }

            Log.d(TAG, "reconfigureEncoder: 1. Получаем новые размеры экрана.");
            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDpi = metrics.densityDpi;
            Log.i(TAG, "reconfigureEncoder: Новые размеры: " + screenWidth + "x" + screenHeight);

            Log.d(TAG, "reconfigureEncoder: 2. Освобождаем ТОЛЬКО старый кодек и его Surface.");
            if (videoEncoder != null) {
                try { videoEncoder.stop(); } catch (Exception e) { Log.w(TAG, "reconfigureEncoder: Ошибка при videoEncoder.stop() (не критично)"); }
                videoEncoder.release();
                Log.d(TAG, "reconfigureEncoder: Старый videoEncoder освобожден.");
            }
            if (inputSurface != null) {
                inputSurface.release();
                Log.d(TAG, "reconfigureEncoder: Старый inputSurface освобожден.");
            }

            Log.d(TAG, "reconfigureEncoder: 3. Отправляем клиенту новое разрешение.");
            if (tcpServer != null && tcpServer.isClientConnected()) {
                Log.i(TAG, "reconfigureEncoder: ОТПРАВКА ПАКЕТА ТИП 2 с разрешением " + screenWidth + "x" + screenHeight);
                tcpServer.sendResolution(screenWidth, screenHeight);
            } else {
                Log.w(TAG, "reconfigureEncoder: Не удалось отправить разрешение, клиент не подключен.");
            }

            Log.d(TAG, "reconfigureEncoder: 4. Создаем и настраиваем НОВЫЙ кодек.");
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, screenWidth, screenHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();
            Log.d(TAG, "reconfigureEncoder: Новый кодек настроен и запущен.");

            Log.d(TAG, "reconfigureEncoder: 5. Настраиваем VirtualDisplay.");
            if (virtualDisplay == null) {
                Log.d(TAG, "reconfigureEncoder: Создаем новый VirtualDisplay.");
                virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture", screenWidth, screenHeight, screenDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null);
            } else {
                Log.d(TAG, "reconfigureEncoder: Перенаправляем существующий VirtualDisplay на новый Surface и меняем размер.");
                virtualDisplay.setSurface(inputSurface);
                virtualDisplay.resize(screenWidth, screenHeight, screenDpi);
            }
            Log.i(TAG, "reconfigureEncoder: ЗАВЕРШЕНИЕ ПЕРЕНАСТРОЙКИ (РАЗБЛОКИРОВАНО).");
        }
    }

    private void drainEncoder() {
        Log.d(TAG, "drainEncoder: Запускаем resolutionChangeDetector.");
        resolutionChangeDetector = new Thread(() -> {
            Log.d(TAG, "resolutionChangeDetector: Поток запущен.");
            while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1000);
                    WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                    if (windowManager == null) continue;

                    DisplayMetrics metrics = new DisplayMetrics();
                    windowManager.getDefaultDisplay().getRealMetrics(metrics);

                    if (metrics.widthPixels != screenWidth || metrics.heightPixels != screenHeight) {
                        Log.i(TAG, "!!! resolutionChangeDetector: ОБНАРУЖЕНО ИЗМЕНЕНИЕ РАЗРЕШЕНИЯ! Старое: " + screenWidth + "x" + screenHeight + ", Новое: " + metrics.widthPixels + "x" + metrics.heightPixels + " !!!");
                        reconfigureEncoder();
                    }
                } catch (InterruptedException e) {
                    Log.d(TAG, "resolutionChangeDetector: Поток прерван.");
                    break;
                } catch (Exception e) {
                    if (isRunning.get()) Log.e(TAG, "resolutionChangeDetector: Ошибка в потоке.", e);
                }
            }
            Log.d(TAG, "resolutionChangeDetector: Поток остановлен.");
        });
        resolutionChangeDetector.start();

        Log.d(TAG, "drainEncoder: Начало цикла извлечения данных.");
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
            // --- КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: БЛОКИРУЕМ ДОСТУП ---
            synchronized (encoderLock) {
                if (videoEncoder == null) {
                    // Кодек может быть null в момент перенастройки, просто ждем
                    continue;
                }
                try {
                    int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000);

                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "drainEncoder: Формат кодировщика изменился. Отправляем SPS/PPS.");
                        MediaFormat newFormat = videoEncoder.getOutputFormat();
                        ByteBuffer sps = newFormat.getByteBuffer("csd-0");
                        ByteBuffer pps = newFormat.getByteBuffer("csd-1");
                        if (tcpServer != null && sps != null && pps != null) {
                            byte[] spsData = new byte[sps.remaining()];
                            sps.get(spsData);
                            byte[] ppsData = new byte[pps.remaining()];
                            pps.get(ppsData);
                            tcpServer.sendData(spsData, true);
                            tcpServer.sendData(ppsData, true);
                        }
                    } else if (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                        if (outputBuffer != null && bufferInfo.size > 0 && tcpServer != null) {
                            byte[] data = new byte[bufferInfo.size];
                            outputBuffer.get(data);
                            tcpServer.sendData(data, false);
                        }
                        videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                } catch (Exception e) {
                    // Это исключение теперь не должно приводить к краху всего сервиса
                    Log.e(TAG, "drainEncoder: Ошибка в цикле (возможно, кодек был остановлен).", e);
                }
            }
        }
        Log.d(TAG, "drainEncoder: Цикл извлечения данных завершен.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Сервис уничтожается, освобождаем ВСЕ ресурсы.");
        releaseAllResources();
    }

    private void releaseAllResources() {
        Log.d(TAG, "releaseAllResources: Начало освобождения ВСЕХ ресурсов.");

        if (resolutionChangeDetector != null) {
            Log.d(TAG, "releaseAllResources: Прерываем resolutionChangeDetector.");
            resolutionChangeDetector.interrupt();
            resolutionChangeDetector = null;
        }
        if (workerThread != null) {
            Log.d(TAG, "releaseAllResources: Прерываем workerThread.");
            workerThread.interrupt();
            workerThread = null;
        }
        if (tcpServer != null) {
            Log.d(TAG, "releaseAllResources: Останавливаем TcpServer.");
            tcpServer.stopServer();
            tcpServer = null;
        }

        synchronized (encoderLock) {
            Log.d(TAG, "releaseAllResources: Входим в synchronized блок.");
            if (virtualDisplay != null) {
                Log.d(TAG, "releaseAllResources: Освобождаем VirtualDisplay.");
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (videoEncoder != null) {
                Log.d(TAG, "releaseAllResources: Освобождаем MediaCodec.");
                try { videoEncoder.stop(); videoEncoder.release(); } catch (Exception e) { /* ignore */ }
                videoEncoder = null;
            }
            if (inputSurface != null) {
                Log.d(TAG, "releaseAllResources: Освобождаем Surface.");
                inputSurface.release();
                inputSurface = null;
            }
            if (mediaProjection != null) {
                Log.d(TAG, "releaseAllResources: Отписываемся и останавливаем MediaProjection.");
                try { mediaProjection.unregisterCallback(new MediaProjection.Callback() {}); } catch(Exception e) {/*ignore*/}
                try { mediaProjection.stop(); } catch(Exception e) {/*ignore*/}
                mediaProjection = null;
            }
            Log.d(TAG, "releaseAllResources: Выходим из synchronized блока.");
        }
        if (mainThreadHandler != null) { // Проверка на null, если сервис быстро уничтожается
            mainThreadHandler.post(this::hideBorderView); // Убираем рамку при остановке сервиса
        }
        Log.i(TAG, "releaseAllResources: Все ресурсы освобождены.");
    }

    private void showBorderView(int color) {
        if (borderView != null) { // Если уже есть, просто меняем цвет
            borderView.setBorderColor(color);
            if (borderView.getWindowToken() == null) { // Проверяем, добавлено ли View в WindowManager
                try {
                    windowManager.addView(borderView, borderViewParams);
                    Log.d(TAG, "BorderView добавлен в WindowManager (после пересоздания).");
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при повторном добавлении BorderView в WindowManager", e);
                }
            } else {
                Log.d(TAG, "BorderView уже был добавлен, цвет обновлен.");
            }
            return;
        }

        // Проверяем разрешение перед созданием (хотя лучше это делать до вызова команды сервису)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Нет разрешения на рисование поверх других окон. Рамка не будет показана.");
            // Можно отправить Toast или уведомление, но из сервиса это сложнее.
            return;
        }

        borderView = new BorderView(this);
        borderView.setBorderColor(color);
        // Устанавливаем толщину рамки. Вы можете сделать это настраиваемым.
        float borderWidthDp = 5f; // Толщина в dp
        float borderWidthPx = borderWidthDp * getResources().getDisplayMetrics().density;
        borderView.setBorderWidth(borderWidthPx);


        int layoutParamsType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParamsType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParamsType = WindowManager.LayoutParams.TYPE_PHONE; // Или TYPE_SYSTEM_ALERT для старых версий
        }

        borderViewParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        try {
            windowManager.addView(borderView, borderViewParams);
            Log.d(TAG, "BorderView добавлен в WindowManager.");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка при добавлении BorderView в WindowManager", e);
            borderView = null; // Сбрасываем, если не удалось добавить
        }
    }

    private void hideBorderView() {
        if (borderView != null && borderView.getWindowToken() != null) {
            try {
                windowManager.removeView(borderView);
                Log.d(TAG, "BorderView удален из WindowManager.");
            } catch (Exception e) {
                Log.e(TAG, "Ошибка при удалении BorderView из WindowManager", e);
            }
        }
        borderView = null; // В любом случае обнуляем ссылку
    }

    private void updateBorderColor(int color) {
        if (borderView != null && borderView.getWindowToken() != null) {
            borderView.setBorderColor(color);
            Log.d(TAG, "Цвет BorderView обновлен.");
        } else if (borderView != null && borderView.getWindowToken() == null) {
            // Если view есть, но не в окне (например, после ошибки добавления), попробуем показать заново
            Log.d(TAG, "BorderView существует, но не в окне. Попытка показать с новым цветом.");
            showBorderView(color);
        } else {
            Log.d(TAG, "Попытка обновить цвет, но BorderView не существует или не добавлен.");
            // Можно решить, нужно ли создавать рамку, если ее нет, при попытке обновить цвет.
            // showBorderView(color); // Показать с новым цветом, если ранее не было
        }
    }

    public void onClientConnectedStateChanged(boolean isConnected) {
        if (!isRunning.get()) return;

        mainThreadHandler.post(() -> { // Отправляем задачу в UI поток
            if (isConnected) {
                Log.d(TAG, "UI Thread: Клиент подключился. Обновляем цвет рамки на красный.");
                updateBorderColor(Color.RED);
            } else {
                Log.d(TAG, "UI Thread: Клиент отключился. Обновляем цвет рамки на зеленый.");
                updateBorderColor(Color.GREEN);
            }
        });
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static Notification getNotification(final Context context, String contentText) {
        String NOTIFICATION_CHANNEL_ID = "osp.moon.clonescreen";
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "ScreenCopy Service", NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(chan);
        }

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID);
        return notificationBuilder.setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("ScreenCopy")
                .setContentText(contentText)
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}


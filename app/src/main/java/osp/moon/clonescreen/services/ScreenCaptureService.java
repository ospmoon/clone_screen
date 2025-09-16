package osp.moon.clonescreen.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.nio.ByteBuffer;

import osp.moon.clonescreen.R;

public class ScreenCaptureService extends Service {

    private static final String TAG = ScreenCaptureService.class.getName();
    private static final int SERVICE_ID = 123;

    public static final String ACTION_PREPARE = "osp.moon.clonescreen.PREPARE";
    public static final String ACTION_START = "osp.moon.clonescreen.START";
    public static final String ACTION_STOP = "osp.moon.clonescreen.STOP";

    public static MediaProjection mediaProjection;

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC; // H.264
    private int screenWidth;
    private int screenHeight;
    private int screenDpi;
    private static final int BIT_RATE = 6000000;
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 2;

    private MediaCodec videoEncoder;
    private VirtualDisplay virtualDisplay;
    private Surface inputSurface;
    private Thread workerThread;
    private TcpServer tcpServer;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Сервис создан");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "Получена команда: " + action);

        switch (action) {
            case ACTION_PREPARE:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification notification = getNotification(getApplicationContext(), "Подготовка к трансляции...");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                    } else {
                        startForeground(SERVICE_ID, notification);
                    }
                } else {
                    startForeground(SERVICE_ID, new Notification());
                }

                Log.d(TAG, "Сервис переведен в режим Foreground (PREPARE).");
                break;

            case ACTION_START:
                if (mediaProjection != null && workerThread == null) {
                    Log.d(TAG, "Получена команда START, начинаем захват.");
                    startCapture();
                } else {
                    Log.e(TAG, "Команда START получена, но mediaProjection == null или workerThread уже работает!");
                }
                break;

            case ACTION_STOP:
                Log.d(TAG, "Получена команда на остановку.");
                stopSelf();
                break;
        }

        return START_NOT_STICKY;
    }

    // ================== НАЧАЛО ВАЖНЫХ ИЗМЕНЕНИЙ ==================
    private void startCapture() {
        Log.d(TAG, "MediaProjection получен. Начинаем настройку...");

        // Регистрируем Callback для MediaProjection, чтобы отслеживать его состояние.
        // Это ОБЯЗАТЕЛЬНО для новых версий Android, чтобы избежать падения.
        if (mediaProjection != null) {
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    super.onStop();
                    Log.w(TAG, "MediaProjection остановлен извне (пользователем или системой). Останавливаем сервис.");
                    // Если захват был прерван, мы должны остановить наш сервис,
                    // чтобы корректно освободить все ресурсы.
                    // Вызов stopSelf() приведет к вызову onDestroy() и releaseResources().
                    stopSelf();
                }
            }, null); // Второй параметр 'handler' можно оставить null, callback будет на главном потоке.
        }

        workerThread = new Thread(() -> {
            try {
                tcpServer = new TcpServer();
                tcpServer.start();

                prepareVideoEncoder();

                // Теперь этот вызов будет безопасным
                virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                        screenWidth, screenHeight, screenDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        inputSurface, null, null);

                tcpServer.waitForClient();

                Log.i(TAG, "Настройка завершена и клиент подключен. Начинаем захват и кодирование...");
                drainEncoder();

            } catch (IOException e) {
                Log.e(TAG, "Ошибка при настройке кодировщика", e);
            } catch (InterruptedException e) {
                Log.d(TAG, "Поток был прерван во время ожидания клиента.");
                Thread.currentThread().interrupt();
            } finally {
                Log.d(TAG, "Worker-поток завершает работу, освобождаем ресурсы.");
                releaseResources();
            }
        });
        workerThread.start();
    }
    // =================== КОНЕЦ ВАЖНЫХ ИЗМЕНЕНИЙ ===================

    private void prepareVideoEncoder() throws IOException {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDpi = metrics.densityDpi;

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, screenWidth, screenHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        Log.d(TAG, "Формат видео: " + format);
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();
        Log.d(TAG, "Кодировщик настроен и запущен.");
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        Log.d(TAG, "drainEncoder: Начинаем цикл извлечения данных из кодировщика...");

        while (!Thread.interrupted()) {
            try {
                int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000);

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = videoEncoder.getOutputFormat();
                    Log.i(TAG, "drainEncoder: ФОРМАТ КОДИРОВЩИКА ИЗМЕНИЛСЯ: " + newFormat.toString());

                    ByteBuffer sps = newFormat.getByteBuffer("csd-0");
                    ByteBuffer pps = newFormat.getByteBuffer("csd-1");

                    if (tcpServer != null && sps != null && pps != null) {
                        byte[] spsData = new byte[sps.remaining()];
                        sps.get(spsData);
                        byte[] ppsData = new byte[pps.remaining()];
                        pps.get(ppsData);
                        Log.i(TAG, "drainEncoder: Найдены SPS (" + spsData.length + " байт) и PPS (" + ppsData.length + " байт). Отправляем...");
                        tcpServer.sendData(spsData);
                        tcpServer.sendData(ppsData);
                    }
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] data = new byte[bufferInfo.size];
                        outputBuffer.get(data);
                        if (tcpServer != null) {
                            tcpServer.sendData(data);
                        }
                    }
                    videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                }
            } catch (Exception e) {
                Log.e(TAG, "drainEncoder: Ошибка в цикле кодирования", e);
                break;
            }
        }
        Log.w(TAG, "drainEncoder: Цикл кодирования завершен.");
    }

    private void releaseResources() {
        Log.d(TAG, "Освобождение ресурсов...");
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        if (tcpServer != null) {
            tcpServer.stopServer();
            tcpServer = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
                videoEncoder.release();
            } catch (Exception e) { Log.e(TAG, "Error stopping video encoder", e); }
            videoEncoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (mediaProjection != null) {
            // Callback, который мы зарегистрировали, нужно отрегистрировать.
            // Хотя система и так остановит MediaProjection, хорошая практика - делать это явно.
            // Но так как мы его полностью уничтожаем, это не критично.
            try {
                mediaProjection.stop();
            } catch(Exception e) {/*ignore*/}
            mediaProjection = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseResources();
        Log.d(TAG, "Сервис уничтожен");
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
                .setContentText(contentText) // Используем переданный текст
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

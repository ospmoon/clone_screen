package osp.moon.clonescreen.services;

import static android.app.Activity.RESULT_OK;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.app.Notification;
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
import android.media.projection.MediaProjectionManager;
import android.os.Build;
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
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.customviews.BorderView;
import osp.moon.clonescreen.helpers.AppHelper;

public class MyCaptureService extends Service implements MySocketServer.ServerCallback {

    private final String TAG = MyCaptureService.class.getName();

    public static final String ACTION_PREPARE = "osp.moon.clonescreen.ACTION_PREPARE";
    public static final String ACTION_START = "osp.moon.clonescreen.ACTION_START";
    public static final String ACTION_STOP = "osp.moon.clonescreen.ACTION_STOP";
    public static final String ACTION_SHOW_BORDER_GREEN = "osp.moon.clonescreen.ACTION_SHOW_BORDER_GREEN";
    public static final String ACTION_HIDE_BORDER = "osp.moon.clonescreen.ACTION_HIDE_BORDER";
    public static final String RESULT_CODE = "osp.moon.clonescreen.RESULT_CODE";
    public static final String RESULT_DATA = "osp.moon.clonescreen.RESULT_DATA";

    private static final int SERVICE_ID = 1;
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private int mScreenWidth = 0;
    private int mScreenHeight = 0;
    private int mScreenDpi = 0;
    private static final int BIT_RATE = 6000000;
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 2;
    private byte[] lastSps = null;
    private byte[] lastPps = null;
    private MediaCodec mVideoEncoder;
    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;
    private BorderView mBorderView;
    private Handler mHandler;
    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    MediaProjection.Callback mMediaProjectioCallback;
    private Thread mWorkerThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private MySocketServer mSocketServer;


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate()");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification notification = AppHelper.getNotification(getApplicationContext(), getApplicationContext().getString(R.string.app_name));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(SERVICE_ID, notification);
            }
        } else {
            startForeground(SERVICE_ID, new Notification());
        }

        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        mProjectionManager = ContextCompat.getSystemService(this, MediaProjectionManager.class);
        mBorderView = new BorderView(this);
        mBorderView.setVisibility(GONE);
        int layoutParamsType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams borderViewParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            windowManager.addView(mBorderView, borderViewParams);
        } catch (Exception e) {
            Log.e(TAG, "КРИТИЧЕСКАЯ ОШИБКА при добавлении BorderView в WindowManager", e);
        }
        mSocketServer = new MySocketServer(getApplicationContext(), this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: Получена команда: " + action);

        switch (action) {
            case ACTION_PREPARE:
                break;
            case ACTION_START:
                int resultCode = intent.getIntExtra(RESULT_CODE, -999);
                Intent resultData = intent.getParcelableExtra(RESULT_DATA);
                if (resultCode != RESULT_OK || resultData == null) {
                    stopCaptureAndSelf();
                    break;
                }

                mMediaProjection = mProjectionManager.getMediaProjection(resultCode, resultData);
                if (mMediaProjection != null) {
                    mMediaProjectioCallback = new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            Log.e(TAG, "!!! MediaProjection.onStop() был вызван системой! Остановка захвата. !!!");
                            stopCaptureAndSelf();
                        }
                    };
                    mMediaProjection.registerCallback(mMediaProjectioCallback, mHandler);
                    Log.d(TAG, "workerThread: MediaProjection.Callback зарегистрирован.");
                    startCapture();
                } else  {
                    Log.e(TAG, "onStartCommand: Не удалось получить MediaProjection из данных Intent (mediaProjectionManager.getMediaProjection вернул null).");
                    Toast.makeText(this, getString(R.string.failed_start_screen_capture_toast_message), Toast.LENGTH_LONG).show();
                    stopCaptureAndSelf();
                }
                break;
            case ACTION_STOP:
                Log.d(TAG, "onStartCommand: Обработка ACTION_STOP.");
                stopCaptureAndSelf();
                break;
            case ACTION_SHOW_BORDER_GREEN:
                Log.d(TAG, "onStartCommand: Обработка ACTION_SHOW_BORDER_GREEN.");
                showBorderView(Color.GREEN);
                break;
            case ACTION_HIDE_BORDER:
                Log.d(TAG, "onStartCommand: Обработка ACTION_HIDE_BORDER.");
                hideBorderView();
                break;
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");
        stopCaptureAndSelf();
    }

    private void startCapture() {
        Log.i(TAG, "startCapture()");

        mWorkerThread = new Thread(() -> {
            Log.d(TAG, "workerThread: Поток запущен.");
            try {
                isRunning.set(true);
                Log.d(TAG, "workerThread: Ожидаем первого подключения клиента...");
                if (mSocketServer.start()) {
                    reconfigureEncoder();
                    drainEncoder();
                }
            } catch (Exception e) { // Ловим все ошибки, чтобы записать в лог
                Log.e(TAG, "workerThread: КРИТИЧЕСКАЯ ошибка в потоке workerThread.", e);
                Thread.currentThread().interrupt();
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
        mWorkerThread.setName("ScreenCaptureWorker");
        mWorkerThread.start();
    }

    private void reconfigureEncoder() throws IOException {
        Log.i(TAG, "reconfigureEncoder()");

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) { Log.e(TAG, "reconfigureEncoder: WindowManager is null!"); throw new IOException("WindowManager is null"); }
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
        mScreenDpi = metrics.densityDpi;
        Log.i(TAG, "reconfigureEncoder: размеры экрана: " + mScreenWidth + "x" + mScreenHeight);
        mSocketServer.sendResolution(mScreenWidth, mScreenHeight);

        Log.d(TAG, "reconfigureEncoder: Создаем НОВЫЙ кодек.");
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mScreenWidth, mScreenHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        try {
            mVideoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();
            Log.d(TAG, "reconfigureEncoder: Новый кодек настроен и запущен. InputSurface создан.");
        } catch (Exception e_codec) {
            Log.e(TAG, "reconfigureEncoder: КРИТИЧЕСКАЯ ОШИБКА при создании/настройке кодека.", e_codec);
            if (mVideoEncoder != null) { mVideoEncoder.release(); mVideoEncoder = null; }
            if (mInputSurface != null) { mInputSurface.release(); mInputSurface = null; }
            throw new IOException("Failed to create/configure MediaCodec", e_codec); // Перебрасываем, чтобы прервать
        }

        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay("ScreenCapture",
                        mScreenWidth, mScreenHeight, mScreenDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        mInputSurface, null, null);
            Log.i(TAG, "reconfigureEncoder: VirtualDisplay создан.");
        } catch (Exception e) {
            Log.e(TAG, "reconfigureEncoder: Общая ошибка при создании VirtualDisplay.", e);
            if (isRunning.get()) stopCaptureAndSelf();
            throw new IOException("Failed to create VirtualDisplay", e);
        }
        Log.i(TAG, "reconfigureEncoder: ЗАВЕРШЕНИЕ.");
    }

    private void drainEncoder() {
        Log.d(TAG, "drainEncoder()");
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
            if (mVideoEncoder == null || !isRunning.get()) {
                Log.d(TAG, "drainEncoder: isRunning=false, выход из цикла.");
                break;
            }
            try {
                int outputBufferIndex = mVideoEncoder.dequeueOutputBuffer(bufferInfo, 10000);

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "drainEncoder: Формат кодировщика изменился.");
                    MediaFormat newFormat = mVideoEncoder.getOutputFormat();
                    Log.d(TAG, "drainEncoder: Новый формат: " + newFormat);
                    ByteBuffer spsBuffer = newFormat.getByteBuffer("csd-0");
                    lastSps = null; lastPps = null;
                    if (spsBuffer != null) {
                        lastSps = new byte[spsBuffer.remaining()];
                        spsBuffer.get(lastSps);
                        spsBuffer.rewind();
                        Log.d(TAG, "drainEncoder: SPS сохранен, " + lastSps.length + " bytes.");
                    } else Log.w(TAG, "drainEncoder: spsBuffer is null в INFO_OUTPUT_FORMAT_CHANGED.");

                    ByteBuffer ppsBuffer = newFormat.getByteBuffer("csd-1");
                    if (ppsBuffer != null) {
                        lastPps = new byte[ppsBuffer.remaining()];
                        ppsBuffer.get(lastPps); ppsBuffer.rewind();
                        Log.d(TAG, "drainEncoder: PPS сохранен, " + lastPps.length + " bytes.");
                    } else Log.w(TAG, "drainEncoder: ppsBuffer is null в INFO_OUTPUT_FORMAT_CHANGED.");

                    if (mSocketServer != null && mSocketServer.isClientConnected() && lastSps != null && lastPps != null) {
                        Log.i(TAG, "drainEncoder: Отправка SPS/PPS клиенту при INFO_OUTPUT_FORMAT_CHANGED.");
                        mSocketServer.sendData(lastSps, true);
                        mSocketServer.sendData(lastPps, true);
                    } else {
                        Log.w(TAG, "drainEncoder: Клиент не подключен или SPS/PPS отсутствуют, не отправляем при INFO_OUTPUT_FORMAT_CHANGED.");
                    }

                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mVideoEncoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer == null) {
                        Log.e(TAG, "drainEncoder: videoEncoder.getOutputBuffer(" + outputBufferIndex + ") вернул null!");
                    } else {
                        if (bufferInfo.size > 0) { // Отправляем, только если есть данные
                            if (mSocketServer != null && mSocketServer.isClientConnected()) {
                                byte[] data = new byte[bufferInfo.size];
                                outputBuffer.get(data, bufferInfo.offset, bufferInfo.size);
                                mSocketServer.sendData(data, (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0);
                            }
                        }
                        mVideoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "drainEncoder: Общая ошибка в цикле.", e);
                break;
            }
        } // end while

        Log.i(TAG, "drainEncoder: Цикл извлечения данных завершен. isRunning=" + isRunning.get());
    }

    private void stopCaptureAndSelf() {
        Log.i(TAG, "stopCaptureAndSelf()");

        if (mWorkerThread != null) {
            Log.d(TAG, "stopCaptureAndSelf: Прерываем workerThread.");
            mWorkerThread.interrupt();
            try { mWorkerThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            mWorkerThread = null;
        }

        if (mSocketServer != null) {
            Log.d(TAG, "stopCaptureAndSelf: Останавливаем TcpServer.");
            mSocketServer.stop("stopCaptureAndSelf()");
            mSocketServer = null;
        }

        releaseEncoderAndProjection(); // Освобождаем кодек и проекцию

        Log.i(TAG, "stopCaptureAndSelf: Остановка завершена, вызываем stopSelf().");
        stopSelf(); // Останавливаем сам сервис
    }

    private void releaseEncoderAndProjection() {
        Log.d(TAG, "releaseEncoderAndProjection()");

            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
                Log.d(TAG, "releaseEncoderAndProjection: VirtualDisplay освобожден.");
            }
            if (mInputSurface != null) {
                mInputSurface.release();
                mInputSurface = null;
                Log.d(TAG, "releaseEncoderAndProjection: InputSurface освобожден.");
            }
            if (mVideoEncoder != null) {
                try {
                    mVideoEncoder.stop();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "releaseEncoderAndProjection: Ошибка при videoEncoder.stop() (возможно, уже остановлен).", e);
                }
                mVideoEncoder.release();
                mVideoEncoder = null;
                Log.d(TAG, "releaseEncoderAndProjection: VideoEncoder освобожден.");
            }


        if (mMediaProjection != null) {
            Log.d(TAG, "releaseEncoderAndProjection: Остановка currentMediaProjectionInstance.");
            if (mMediaProjectioCallback != null) {
                try {
                    mMediaProjection.unregisterCallback(mMediaProjectioCallback);
                } catch (Exception e) { /* Игнорируем, если уже отписан или ошибка */ }
                mMediaProjectioCallback = null;
            }
            try {
                mMediaProjection.stop();
            } catch (Exception e) { /* Игнорируем, если уже остановлен или ошибка */ }
            mMediaProjection = null;
            Log.d(TAG, "releaseEncoderAndProjection: currentMediaProjectionInstance обнулен.");
        }
    }

    private void showBorderView(int color) {
        Log.d(TAG, "showBorderView: color: " + color);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mBorderView.setBorderColor(color);
                mBorderView.setVisibility(VISIBLE);
            }
        });
    }

    private void hideBorderView() {
        Log.d(TAG, "hideBorderView: Попытка скрыть рамку.");
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mBorderView.setVisibility(GONE);
            }
        });
    }

    @Override
    public void onStarted() {
        Log.d(TAG, "callback onStarted()");
    }

    @Override
    public void onStoped(String reason) {
        Log.d(TAG, "callback onStoped()");
    }

    @Override
    public void onClientConnected() {
        Log.d(TAG, "callback onClientConnected()");
    }

    @Override
    public void onClientDisconnected() {
        Log.d(TAG, "callback onClientDisconnected()");
    }
}

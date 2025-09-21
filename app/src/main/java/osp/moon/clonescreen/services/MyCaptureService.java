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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

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
    MediaProjection.Callback mMediaProjectionCallback;
    private Thread mWorkerThread;
    //private final AtomicBoolean isRunning = new AtomicBoolean(false);
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
        Log.i(TAG, "onStartCommand: " + action);

        switch (action) {
            case ACTION_START:
                int resultCode = intent.getIntExtra(RESULT_CODE, -999);
                Intent resultData = intent.getParcelableExtra(RESULT_DATA);
                if (resultCode != RESULT_OK || resultData == null) {
                    stopCaptureAndSelf();
                    break;
                }
                mMediaProjection = mProjectionManager.getMediaProjection(resultCode, resultData);
                if (mMediaProjection != null) {
                    mMediaProjectionCallback = new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            Log.e(TAG, "!!! mMediaProjectionCallback.onStop() !!!");
                            stopCaptureAndSelf();
                        }
                    };
                    mMediaProjection.registerCallback(mMediaProjectionCallback, mHandler);

                    startWaitingClientThread();
                } else  {
                    Log.e(TAG, "onStartCommand: mMediaProjection != null");
                    Toast.makeText(this, getString(R.string.failed_start_screen_capture_toast_message), Toast.LENGTH_LONG).show();
                    stopCaptureAndSelf();
                }
                break;
            case ACTION_STOP:
                stopCaptureAndSelf();
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
        //stopCaptureAndSelf();
        hideBorderView();
    }

    private void startWaitingClientThread() {
        Log.i(TAG, "startWaitingClientThread()");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mSocketServer != null) mSocketServer.startAndWaitClient();
                } catch (Exception e) {
                    Log.e(TAG, "startWaitingClientThread: Exception", e);
                    Thread.currentThread().interrupt();
                }
            }
        });
        thread.start();
    }

    private void startCapture() {
        Log.i(TAG, "startCapture()");
        mWorkerThread = new Thread(() -> {
            try {
                if (reconfigureEncoder()) {
                    mainLoop();
                } else {
                    stopCaptureAndSelf();
                }
            } catch (Exception e) {
                Log.e(TAG, "workerThread: Exception1", e);
                Thread.currentThread().interrupt();
            } finally {
                stopCaptureAndSelf();
            }
        });
        mWorkerThread.setName("ScreenCaptureWorker");
        mWorkerThread.start();
    }

    private boolean reconfigureEncoder() {
        Log.i(TAG, "reconfigureEncoder()");
        MediaFormat format;
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            mScreenWidth = metrics.widthPixels;
            mScreenHeight = metrics.heightPixels;
            mScreenDpi = metrics.densityDpi;
            Log.i(TAG, "reconfigureEncoder: размеры экрана: " + mScreenWidth + "x" + mScreenHeight);
            mSocketServer.sendResolution(mScreenWidth, mScreenHeight);

            Log.d(TAG, "reconfigureEncoder: Создаем НОВЫЙ кодек.");
            format = MediaFormat.createVideoFormat(MIME_TYPE, mScreenWidth, mScreenHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        } catch (Exception e) {
            Log.e(TAG, "reconfigureEncoder: Общая ошибка при создании MediaFormat.", e);
            return false;
        }

        try {
            mVideoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();
            Log.d(TAG, "reconfigureEncoder: Новый кодек настроен и запущен. InputSurface создан.");
        } catch (Exception e) {
            Log.e(TAG, "reconfigureEncoder: КРИТИЧЕСКАЯ ОШИБКА при создании/настройке кодека.", e);
            if (mVideoEncoder != null) { mVideoEncoder.release(); mVideoEncoder = null; }
            if (mInputSurface != null) { mInputSurface.release(); mInputSurface = null; }
            return false;
        }

        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay("ScreenCapture",
                        mScreenWidth, mScreenHeight, mScreenDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        mInputSurface, null, null);
            Log.i(TAG, "reconfigureEncoder: VirtualDisplay создан.");
        } catch (Exception e) {
            Log.e(TAG, "reconfigureEncoder: Общая ошибка при создании VirtualDisplay.", e);
            stopCaptureAndSelf();
            return false;
        }
        Log.i(TAG, "reconfigureEncoder: ЗАВЕРШЕНИЕ.");
        return true;
    }

    private void mainLoop() {
        Log.d(TAG, "mainLoop()");
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (mSocketServer != null && mSocketServer.isClientConnected() && !Thread.currentThread().isInterrupted()) {
            if (mVideoEncoder == null) {
                Log.d(TAG, "mainLoop: mVideoEncoder == null. Exit");
                break;
            }
            try {
                int outputBufferIndex = mVideoEncoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "mainLoop: INFO_OUTPUT_FORMAT_CHANGED.");
                    MediaFormat newFormat = mVideoEncoder.getOutputFormat();
                    ByteBuffer spsBuffer = newFormat.getByteBuffer("csd-0");
                    lastSps = null; lastPps = null;
                    if (spsBuffer != null) {
                        lastSps = new byte[spsBuffer.remaining()];
                        spsBuffer.get(lastSps);
                        spsBuffer.rewind();
                    }
                    ByteBuffer ppsBuffer = newFormat.getByteBuffer("csd-1");
                    if (ppsBuffer != null) {
                        lastPps = new byte[ppsBuffer.remaining()];
                        ppsBuffer.get(lastPps);
                        ppsBuffer.rewind();
                    }

                    if (mSocketServer != null && mSocketServer.isClientConnected() && lastSps != null && lastPps != null) {
                        mSocketServer.sendData(lastSps, true);
                        mSocketServer.sendData(lastPps, true);
                    }

                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mVideoEncoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer == null) {
                        Log.e(TAG, "drainEncoder: videoEncoder.getOutputBuffer(" + outputBufferIndex + ") вернул null!");
                    } else {
                        if (bufferInfo.size > 0) {
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

        Log.i(TAG, "mainLoop----> EXIT");
    }

    private void stopCaptureAndSelf() {
        Log.i(TAG, "stopCaptureAndSelf()");

        if (mWorkerThread != null) {
            mWorkerThread.interrupt();
            try { mWorkerThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            mWorkerThread = null;
        }

        if (mSocketServer != null) {
            mSocketServer.stop("stopCaptureAndSelf()");
            //mSocketServer = null;
        }

        releaseEncoderAndProjection();
        stopSelf();
    }

    private void releaseEncoderAndProjection() {
        Log.d(TAG, "releaseEncoderAndProjection()");

            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
            if (mInputSurface != null) {
                mInputSurface.release();
                mInputSurface = null;
            }
            if (mVideoEncoder != null) {
                try {
                    mVideoEncoder.stop();
                    mVideoEncoder.release();
                    mVideoEncoder = null;
                } catch (Exception e) {
                    Log.w(TAG, "releaseEncoderAndProjection: Exception", e);
                }
            }


        if (mMediaProjection != null) {
            if (mMediaProjectionCallback != null) {
                try {
                    mMediaProjection.unregisterCallback(mMediaProjectionCallback);
                } catch (Exception e) { /* ignore */ }
                mMediaProjectionCallback = null;
            }
            try {
                mMediaProjection.stop();
            } catch (Exception e) { /* ignore */ }
            mMediaProjection = null;
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
        Log.d(TAG, "hideBorderView()");
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
        showBorderView(Color.GREEN);
    }

    @Override
    public void onStopped(String reason) {
        Log.d(TAG, "callback onStoped()");
    }

    @Override
    public void onClientConnected() {
        Log.d(TAG, "callback onClientConnected()");
        showBorderView(Color.RED);
        startCapture();
    }

    @Override
    public void onClientDisconnected() {
        Log.d(TAG, "callback onClientDisconnected()");
        showBorderView(Color.GREEN);
        //startWaitingClientThread();
        stopSelf();
    }
}

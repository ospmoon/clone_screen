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
import android.os.Bundle;
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

import osp.moon.clonescreen.App;
import osp.moon.clonescreen.R;
import osp.moon.clonescreen.customviews.BorderView;
import osp.moon.clonescreen.helpers.AppHelper;

public class MyCaptureService extends Service implements MySocketServer.ServerCallback {

    private final String TAG = MyCaptureService.class.getName();
    public static final String ACTION_START = "osp.moon.clonescreen.ACTION_START";
    public static final String ACTION_STOP = "osp.moon.clonescreen.ACTION_STOP";
    public static final String RESULT_CODE = "osp.moon.clonescreen.RESULT_CODE";
    public static final String RESULT_DATA = "osp.moon.clonescreen.RESULT_DATA";

    private static final int SERVICE_ID = 1;
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int BIT_RATE = 6000000;
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 2;
    private MediaCodec mVideoEncoder;
    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;
    private BorderView mBorderView;
    private Handler mHandler;
    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mMediaProjectionCallback;
    private Thread mWorkerThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private MySocketServer mSocketServer;


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate()");

        try {
            init();
        } catch (Exception e) {
            Log.e(TAG, "onCreate: Exception.", e);
        }
    }

    private void init() {
        Log.d(TAG, "init()");
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
            Log.e(TAG, "WindowManager. Exception.", e);
        }

        mSocketServer = new MySocketServer(getApplicationContext(), MyCaptureService.this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: " + action);

        switch (action) {
            case ACTION_START:
                if (isRunning.get()) {
                    Log.w(TAG, "The service is already launched. Exit");
                    Toast.makeText(this, getString(R.string.service_already_launched_toast_message), Toast.LENGTH_LONG).show();
                    return START_NOT_STICKY;
                }
                int resultCode = intent.getIntExtra(RESULT_CODE, -999);
                Intent resultData = intent.getParcelableExtra(RESULT_DATA);
                if (resultCode != RESULT_OK || resultData == null) {
                    stopCaptureAndSelf("resultCode != RESULT_OK || resultData == null");
                    break;
                }
                mMediaProjection = mProjectionManager.getMediaProjection(resultCode, resultData);
                if (mMediaProjection != null) {
                    mMediaProjectionCallback = new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            Log.e(TAG, "!!! mMediaProjectionCallback.onStop() !!!");
                            stopCaptureAndSelf("mMediaProjectionCallback.onStop()");
                        }
                    };
                    mMediaProjection.registerCallback(mMediaProjectionCallback, mHandler);
                    isRunning.set(true);

                    startCapture();

                    mSocketServer.start();
                } else  {
                    Log.e(TAG, "onStartCommand: mMediaProjection == null");
                    Toast.makeText(this, getString(R.string.failed_start_screen_capture_toast_message), Toast.LENGTH_LONG).show();
                    stopCaptureAndSelf("onStartCommand: mMediaProjection == null");
                }
                break;
            case ACTION_STOP:
                stopCaptureAndSelf("ACTION_STOP");
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
        Log.w(TAG, "onDestroy()");
        hideBorderView();
    }

    private void startCapture() {
        Log.d(TAG, "startCapture()");
        if (mWorkerThread != null && mWorkerThread.isAlive()) {
            Log.w(TAG, "startCapture(): mWorkerThread is already launched.");
            return;
        }
        mWorkerThread = new Thread(() -> {
            try {
                String reconfigureEncoderError = setupEncoderAndVirtualDisplay();
                if (reconfigureEncoderError != null) {
                    stopCaptureAndSelf(reconfigureEncoderError);
                } else {
                    mainLoop();
                }
            } catch (Exception e) {
                Log.e(TAG, "workerThread: Exception1", e);
                Thread.currentThread().interrupt();
                stopCaptureAndSelf(e.getMessage());
            }
        });
        mWorkerThread.setName("ScreenCaptureWorker");
        mWorkerThread.start();
    }

    private String setupEncoderAndVirtualDisplay() {
        Log.d(TAG, "setupEncoderAndVirtualDisplay()");

        String error = getScreenResolution();
        if (error != null) {
            return error;
        }
        //if (mSocketServer != null) mSocketServer.sendResolution();

        error = createInputSurface(createMediaFormat());
        if (error != null) {
            return error;
        }

        return createVirtualDisplay();
    }

    private String getScreenResolution() {
        Log.d(TAG, "getScreenResolution()");
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            AppHelper.setScreenWidth(metrics.widthPixels);
            AppHelper.setScreenHeight(metrics.heightPixels);
            AppHelper.setScreenDpi(metrics.densityDpi);
            Log.i(TAG, "Screen Resolution: " + AppHelper.getScreenWidth() + "x" + AppHelper.getScreenHeight());
        } catch (Exception e) {
            Log.e(TAG, "getScreenResolution: Exception.", e);
            return e.getMessage();
        }
        return null;
    }

    private MediaFormat createMediaFormat() {
        Log.d(TAG, "createMediaFormat()");
        MediaFormat format = null;
        try {
            format = MediaFormat.createVideoFormat(MIME_TYPE, AppHelper.getScreenWidth(), AppHelper.getScreenHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        } catch (Exception e) {
            Log.e(TAG, "createMediaFormat: Exception.", e);
        }
        return format;
    }

    private String createInputSurface(MediaFormat format) {
        Log.d(TAG, "createInputSurface()");
        if (format == null) {
            return "MediaFormat == null";
        }
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();
        } catch (Exception e) {
            Log.e(TAG, "createVirtualDisplay: Exception.", e);
            if (mVideoEncoder != null) { mVideoEncoder.release(); mVideoEncoder = null; }
            if (mInputSurface != null) { mInputSurface.release(); mInputSurface = null; }
            return e.getMessage();
        }
        return null;
    }

    private String createVirtualDisplay() {
        Log.d(TAG, "createVirtualDisplay()");
        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay("ScreenCapture",
                    AppHelper.getScreenWidth(), AppHelper.getScreenHeight(), AppHelper.getScreenDpi(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mInputSurface, null, null);
            Log.i(TAG, "VirtualDisplay created.");
        } catch (Exception e) {
            Log.e(TAG, "createVirtualDisplay: Exception.", e);
            return e.getMessage();
        }
        return null;
    }

    private void mainLoop() {
        Log.d(TAG, "mainLoop()");
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (mVideoEncoder != null && !Thread.currentThread().isInterrupted()) {
            try {
                int outputBufferIndex = mVideoEncoder.dequeueOutputBuffer(bufferInfo, 10000);
                //if (outputBufferIndex != -1) Log.i(TAG, "mainLoop: index: " + outputBufferIndex);
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "mainLoop: INFO_OUTPUT_FORMAT_CHANGED.");
                    MediaFormat newFormat = mVideoEncoder.getOutputFormat();
                    ByteBuffer spsBuffer = newFormat.getByteBuffer("csd-0");
                    AppHelper.setSps(null);
                    AppHelper.setPps(null);
                    if (spsBuffer != null) {
                        AppHelper.setSps(new byte[spsBuffer.remaining()]);
                        spsBuffer.get(AppHelper.getSps());
                        spsBuffer.rewind();
                    }
                    ByteBuffer ppsBuffer = newFormat.getByteBuffer("csd-1");
                    if (ppsBuffer != null) {
                        AppHelper.setPps(new byte[ppsBuffer.remaining()]);
                        ppsBuffer.get(AppHelper.getPps());
                        ppsBuffer.rewind();
                    }

                    if (mSocketServer != null) {
                        mSocketServer.sendData(AppHelper.getSps(), true);
                        mSocketServer.sendData(AppHelper.getPps(), true);
                    }
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mVideoEncoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        if (mSocketServer != null) {
                            byte[] data = new byte[bufferInfo.size];
                            outputBuffer.get(data, bufferInfo.offset, bufferInfo.size);
                            mSocketServer.sendData(data, (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0);
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

    private void stopCaptureAndSelf(String reason) {
        Log.d(TAG, "stopCaptureAndSelf(), reason: " + reason);

        stopWorkerThread(reason);

        stopSocketServer(reason);

        releaseEncoderAndProjection();

        stopSelf();
    }

    private void stopWorkerThread(String reason) {
        Log.d(TAG, "stopWorkerThread(), reason: " + reason);
        if (mWorkerThread != null) {
            mWorkerThread.interrupt();
            try { mWorkerThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            mWorkerThread = null;
        }
    }

    private void stopSocketServer(String reason) {
        Log.d(TAG, "stopSocketServer(), reason: " + reason);
        if (mSocketServer != null) {
            mSocketServer.stop(reason);
            mSocketServer = null;
        }
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
        if (mHandler != null) {
            mHandler.post(() -> {
                if (mBorderView != null) {
                    mBorderView.setBorderColor(color);
                    mBorderView.setVisibility(VISIBLE);
                }
            });
        }
    }

    private void hideBorderView() {
        Log.d(TAG, "hideBorderView()");
        if (mHandler != null) {
            mHandler.post(() -> {
                if (mBorderView != null) mBorderView.setVisibility(GONE);
            });
        }
    }

    @Override
    public void onClientConnected(String ip) {
        Log.d(TAG, "callback onClientConnected(): client IP: " + ip);
        showBorderView(Color.RED);
    }

    @Override
    public void onClientDisconnected(String reason) {
        Log.d(TAG, "callback onClientDisconnected(), reason: " + reason);
    }

    @Override
    public void onServerStopped(String reason) {
        Log.d(TAG, "callback onServerStopped(), reason: " + reason);
        stopCaptureAndSelf(reason);
    }

    @Override
    public void onWaitingForClient() {
        Log.d(TAG, "callback onWaitingForClient()");
        showBorderView(Color.GREEN);
    }
}

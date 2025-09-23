package osp.moon.clonescreen.fragments;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import osp.moon.clonescreen.customviews.AutoFitSurfaceView;
import osp.moon.clonescreen.R;
import osp.moon.clonescreen.helpers.AppHelper;

public class ClientFragment extends Fragment implements SurfaceHolder.Callback {

    private static final String TAG = ClientFragment.class.getName();
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private AutoFitSurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private MediaCodec videoDecoder;
    private Thread networkThread;
    private final AtomicBoolean shouldBeConnecting = new AtomicBoolean(true);

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView()");
        View root = inflater.inflate(R.layout.fragment_client, container, false);
        surfaceView = root.findViewById(R.id.client_surface_view);
        surfaceView.getHolder().addCallback(this);
        return root;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated()");
        this.surfaceHolder = holder;
        startClient();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed()");
        this.surfaceHolder = null;
        stopClient();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged: format=" + format + ", width=" + width + ", height=" + height);
    }

    private void startClient() {
        Log.d(TAG, "startClient()");
        if (this.surfaceHolder == null || this.surfaceHolder.getSurface() == null || !this.surfaceHolder.getSurface().isValid()) {
            Log.w(TAG, "startClient: SurfaceHolder or Surface is not ready, launch postponed.");
            return;
        }
        if (networkThread != null && networkThread.isAlive()) {
            Log.w(TAG, "startClient: The network stream is already running, we are not creating a new one.");
            return;
        }

        networkThread = new Thread(() -> {
            try {
                while (shouldBeConnecting.get() && !Thread.currentThread().isInterrupted()) {
                    try (Socket socket = new Socket(AppHelper.getServerIp(), AppHelper.getPort())) {
                        Log.i(TAG, "networkThread: SUCCESSFULLY CONNECTED to" + AppHelper.getServerIp());
                        requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity(), requireActivity().getString(R.string.connected_toast_message), Toast.LENGTH_SHORT).show());

                        try (InputStream inputStream = socket.getInputStream()) {
                            // Настраиваем декодер один раз с "заглушкой", реальный размер придет из потока.
                            setupDecoder(1, 1);

                            while (shouldBeConnecting.get() && !Thread.currentThread().isInterrupted()) {
                                int packetType = inputStream.read();
                                if (packetType == -1) {
                                    throw new IOException("Сервер корректно закрыл соединение (read returned -1)");
                                }

                                if (packetType == 2) {
                                    Log.i(TAG, "!!! networkThread: (Resolution packet) !!!");
                                    byte[] widthBytes = readNBytes(inputStream, 4);
                                    byte[] heightBytes = readNBytes(inputStream, 4);
                                    int receivedWidth = ByteBuffer.wrap(widthBytes).asIntBuffer().get();
                                    int receivedHeight = ByteBuffer.wrap(heightBytes).asIntBuffer().get();
                                    Log.i(TAG, "networkThread: new Resolution: " + receivedWidth + "x" + receivedHeight);

                                    // Перенастраиваем декодер с новым разрешением
                                    setupDecoder(receivedWidth, receivedHeight);

                                } else if (packetType == 0 || packetType == 1) {
                                    if (videoDecoder == null) {
                                        continue;
                                    }
                                    byte[] sizeBuffer = readNBytes(inputStream, 4);
                                    int packetSize = ByteBuffer.wrap(sizeBuffer).asIntBuffer().get();

                                    if (packetSize <= 0 || packetSize > 2_000_000)
                                        throw new IOException("Invalid packet size: " + packetSize);

                                    byte[] packetBuffer = readNBytes(inputStream, packetSize);
                                    feedDecoder(packetBuffer, packetType == 0);
                                }
                            }
                        }
                    } catch (Exception e) {
                            Log.e(TAG, "networkThread: Exception ", e);
                            Log.w(TAG, "networkThread: 5 second pause before reconnecting...");
                            requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity(), requireActivity().getString(R.string.reconnecting_toast_message), Toast.LENGTH_SHORT).show());
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException interruptedException) {
                                Thread.currentThread().interrupt();
                            }
                    }
                }
                Log.d(TAG, "networkThread: Exited the main loop. Thread terminates.");
            } catch (Exception e) {
                Log.e(TAG, "networkThread: Exception", e);
            } finally {
                if (isAdded() && getActivity() != null) { // Проверяем, что фрагмент присоединен
                    getActivity().runOnUiThread(() -> {
                        requireActivity().runOnUiThread(this::openWelcomeFragment);
                    });
                }
            }
        });
        networkThread.start();
    }

    private void openWelcomeFragment() {
        Log.w(TAG, "openWelcomeFragment()");
        Navigation.findNavController(requireActivity(), R.id.nav_host_fragment).navigate(R.id.welcomeFragment);
    }

    private byte[] readNBytes(InputStream in, int n) throws IOException {
        byte[] buffer = new byte[n];
        int totalRead = 0;
        while(totalRead < n) {
            int bytesRead = in.read(buffer, totalRead, n - totalRead);
            if (bytesRead == -1) throw new IOException("The connection was closed while reading " + n + " bytes.");
            totalRead += bytesRead;
        }
        return buffer;
    }

    private void setupDecoder(int width, int height) {
        Log.d(TAG, "setupDecoder()");
        Surface currentSurface = null;
        if (this.surfaceHolder != null) {
            currentSurface = this.surfaceHolder.getSurface();
        }

        if (currentSurface == null || !currentSurface.isValid()) {
            if (videoDecoder != null) {
                try {
                    videoDecoder.stop();
                    videoDecoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "setupDecoder: Exception1.", e);
                }
                videoDecoder = null;
            }
            return;
        }

        try {
            if (videoDecoder != null) {
                videoDecoder.stop();
                videoDecoder.release();
                videoDecoder = null;
            }
            Log.i(TAG, "setupDecoder: Setting up with permission: " + width + "x" + height + " on surface: " + currentSurface);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            videoDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
            videoDecoder.configure(format, currentSurface, null, 0);
            videoDecoder.start();
        } catch (Exception e) {
            Log.e(TAG, "setupDecoder: Exception2.", e);
            if (videoDecoder != null) {
                try { videoDecoder.release(); } catch (Exception e2) { /* ignore */ }
                videoDecoder = null;
            }
        }
    }


    private void feedDecoder(byte[] data, boolean isConfig) {
        if (!shouldBeConnecting.get() || videoDecoder == null) {
            return;
        }
        try {
            if (!shouldBeConnecting.get()) return;
            int inputBufferIndex = videoDecoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                if (!shouldBeConnecting.get() || videoDecoder == null) {
                    return;
                }
                ByteBuffer inputBuffer = videoDecoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data);
                    int flags = isConfig ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                    videoDecoder.queueInputBuffer(inputBufferIndex, 0, data.length, System.nanoTime() / 1000, flags);
                }
            }

            if (!shouldBeConnecting.get() || videoDecoder == null) {
                return;
            }
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 0);

            while (outputBufferIndex >= 0) {
                if (!shouldBeConnecting.get() || videoDecoder == null) break;
                videoDecoder.releaseOutputBuffer(outputBufferIndex, true);
                if (!shouldBeConnecting.get() || videoDecoder == null) break;
                outputBufferIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }

            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "INFO_OUTPUT_FORMAT_CHANGED: " + videoDecoder.getOutputFormat());
                MediaFormat newFormat = videoDecoder.getOutputFormat();
                int newWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                int newHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                Log.d(TAG, "New sizes from the decoder: " + newWidth + "x" + newHeight);

                requireActivity().runOnUiThread(() -> {
                    surfaceView.setAspectRatio(newWidth, newHeight);
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "feedDecoder: Exception.", e);
        }
    }

    private void stopClient() {
        Log.d(TAG, "stopClient()");
        shouldBeConnecting.set(false);
        if (networkThread != null) {
            networkThread.interrupt();
            try {
                networkThread.join(500); // Let's give the Thread some time to complete.
            } catch (Exception e) {
                Log.e(TAG, "stopClient: Exception1.", e);
                Thread.currentThread().interrupt();
            }
            networkThread = null;
        }
        if (videoDecoder != null) {
            try {
                videoDecoder.stop();
                videoDecoder.release();
            } catch (Exception e) {
                Log.e(TAG, "stopClient: Exception2.", e);
            }
            videoDecoder = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");
        stopClient();
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume()");
    }
}

package osp.moon.clonescreen;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ClientActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = ClientActivity.class.getName();
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int PORT = 12345;

    private AutoFitSurfaceView surfaceView; // ИЗМЕНЕНИЕ
    private Surface surface;
    private MediaCodec videoDecoder;
    private Thread networkThread;
    private EditText ipInput;
    private LinearLayout controlsContainer;
    private String masterIpAddress = "192.168.43.1"; // Типичный IP точки доступа
    private boolean isConnectionRequested = false;

    // Поля для динамического разрешения
    private int videoWidth = 0;
    private int videoHeight = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);

        ipInput = findViewById(R.id.ip_address_input);
        controlsContainer = findViewById(R.id.controls_container);
        Button connectButton = findViewById(R.id.connect_button);
        surfaceView = findViewById(R.id.client_surface_view); // ИЗМЕНЕНИЕ

        ipInput.setText(masterIpAddress);
        surfaceView.getHolder().addCallback(this);

        connectButton.setOnClickListener(v -> {
            masterIpAddress = ipInput.getText().toString();
            if (masterIpAddress.isEmpty()) {
                Toast.makeText(this, "Введите IP-адрес", Toast.LENGTH_SHORT).show();
                return;
            }
            isConnectionRequested = true;
            controlsContainer.setVisibility(View.GONE);
            Toast.makeText(this, "Подключение...", Toast.LENGTH_SHORT).show();
            startClient();
        });
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface создан.");
        surface = holder.getSurface();
        if (isConnectionRequested) {
            startClient();
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface уничтожен.");
        stopClient();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    private void startClient() {
        if (surface == null || !surface.isValid() || (networkThread != null && networkThread.isAlive())) {
            return;
        }

        networkThread = new Thread(() -> {
            try (Socket socket = new Socket(masterIpAddress, PORT);
                 InputStream inputStream = socket.getInputStream()) {
                Log.d(TAG, "Подключено к серверу: " + masterIpAddress);
                runOnUiThread(() -> Toast.makeText(ClientActivity.this, "Подключено!", Toast.LENGTH_SHORT).show());

                while (!Thread.currentThread().isInterrupted()) {
                    int packetType = inputStream.read(); // Читаем тип пакета (1 байт)
                    if (packetType == -1) break;

                    if (packetType == 2) { // Тип 2: Пакет с разрешением
                        byte[] widthBytes = readNBytes(inputStream, 4);
                        byte[] heightBytes = readNBytes(inputStream, 4);
                        videoWidth = ByteBuffer.wrap(widthBytes).asIntBuffer().get();
                        videoHeight = ByteBuffer.wrap(heightBytes).asIntBuffer().get();
                        Log.i(TAG, "Получено разрешение от сервера: " + videoWidth + "x" + videoHeight);
                        runOnUiThread(() -> surfaceView.setAspectRatio(videoWidth, videoHeight));
                        // Настраиваем декодер с этим разрешением
                        setupDecoder();
                    } else if (packetType == 0 || packetType == 1) { // Тип 0 (конфиг) или 1 (кадр)
                        if (videoDecoder == null) continue; // Ждем настройки декодера

                        byte[] sizeBuffer = readNBytes(inputStream, 4);
                        int packetSize = ByteBuffer.wrap(sizeBuffer).asIntBuffer().get();
                        if (packetSize <= 0 || packetSize > 1_000_000) throw new IOException("Неверный размер пакета: " + packetSize);

                        byte[] packetBuffer = readNBytes(inputStream, packetSize);
                        feedDecoder(packetBuffer, packetType == 0); // true если конфиг
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка сети или подключения", e);
                runOnUiThread(() -> {
                    Toast.makeText(ClientActivity.this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    controlsContainer.setVisibility(View.VISIBLE);
                });
            } finally {
                Log.d(TAG, "Сетевой поток завершен.");
                isConnectionRequested = false;
            }
        });
        networkThread.start();
    }

    // Вспомогательный метод для надежного чтения
    private byte[] readNBytes(InputStream in, int n) throws IOException {
        byte[] buffer = new byte[n];
        int totalRead = 0;
        while(totalRead < n) {
            int bytesRead = in.read(buffer, totalRead, n - totalRead);
            if (bytesRead == -1) throw new IOException("Соединение закрыто");
            totalRead += bytesRead;
        }
        return buffer;
    }

    private void setupDecoder() throws IOException {
        if (videoDecoder != null) videoDecoder.release(); // Освобождаем старый, если есть
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, videoWidth, videoHeight);
        videoDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
        videoDecoder.configure(format, surface, null, 0);
        videoDecoder.start();
        Log.d(TAG, "Декодер настроен с разрешением " + videoWidth + "x" + videoHeight);
    }

    private void feedDecoder(byte[] data, boolean isConfig) {
        try {
            int inputBufferIndex = videoDecoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = videoDecoder.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                inputBuffer.put(data);
                int flags = isConfig ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                videoDecoder.queueInputBuffer(inputBufferIndex, 0, data.length, System.nanoTime() / 1000, flags);
            }
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 0);
            if (outputBufferIndex >= 0) {
                videoDecoder.releaseOutputBuffer(outputBufferIndex, true);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Формат декодера изменился: " + videoDecoder.getOutputFormat());
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка при подаче данных в декодер", e);
        }
    }


    private void stopClient() {
        isConnectionRequested = false;
        if (networkThread != null) {
            networkThread.interrupt();
            networkThread = null;
        }
        if (videoDecoder != null) {
            try {
                videoDecoder.stop();
                videoDecoder.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Ошибка при остановке декодера", e);
            }
            videoDecoder = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopClient();
    }
}

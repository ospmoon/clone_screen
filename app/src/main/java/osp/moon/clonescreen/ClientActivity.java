package osp.moon.clonescreen;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC; // H.264
    private static final int VIDEO_WIDTH = 1080;
    private static final int VIDEO_HEIGHT = 2340;
    private static final int PORT = 12345;

    private String masterIpAddress = "192.168.0.100";

    private SurfaceView surfaceView;
    private Surface surface;
    private MediaCodec videoDecoder;
    private Thread networkThread;

    private EditText ipInput;
    private LinearLayout controlsContainer;

    private boolean isConnectionRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);

        ipInput = findViewById(R.id.ip_address_input);
        controlsContainer = findViewById(R.id.controls_container);
        Button connectButton = findViewById(R.id.connect_button);

        ipInput.setText(masterIpAddress);

        surfaceView = findViewById(R.id.client_surface_view);
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

            if (surface != null && surface.isValid()) {
                startClient();
            }
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
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        // Не используем
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface уничтожен.");
        stopClient();
    }

    private void startClient() {
        if (surface == null || !surface.isValid()) {
            Toast.makeText(this, "Surface еще не готов", Toast.LENGTH_SHORT).show();
            return;
        }

        if (networkThread != null && networkThread.isAlive()) {
            return;
        }

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);

        try {
            videoDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
            videoDecoder.configure(format, surface, null, 0);
            videoDecoder.start();
            Log.d(TAG, "Декодер настроен и запущен.");

            // ================== НАЧАЛО КЛЮЧЕВЫХ ИЗМЕНЕНИЙ ==================
            networkThread = new Thread(() -> {
                try (Socket socket = new Socket(masterIpAddress, PORT);
                     InputStream inputStream = socket.getInputStream()) {

                    Log.d(TAG, "Подключено к серверу: " + masterIpAddress);
                    runOnUiThread(() -> Toast.makeText(ClientActivity.this, "Подключено!", Toast.LENGTH_SHORT).show());

                    while (!Thread.currentThread().isInterrupted()) {
                        // 1. Читаем первые 4 байта, чтобы узнать размер следующего пакета
                        byte[] sizeBuffer = new byte[4];
                        int totalRead = 0;
                        while(totalRead < 4) {
                            int bytesRead = inputStream.read(sizeBuffer, totalRead, 4 - totalRead);
                            if (bytesRead == -1) {
                                throw new IOException("Соединение закрыто при чтении размера пакета.");
                            }
                            totalRead += bytesRead;
                        }

                        int packetSize = ByteBuffer.wrap(sizeBuffer).asIntBuffer().get();

                        // Проверка на адекватность размера
                        if (packetSize <= 0 || packetSize > 1_000_000) { // 1MB limit
                            throw new IOException("Получен неверный размер пакета: " + packetSize);
                        }

                        // 2. Теперь читаем ровно packetSize байт
                        byte[] packetBuffer = new byte[packetSize];
                        totalRead = 0;
                        while(totalRead < packetSize) {
                            int bytesRead = inputStream.read(packetBuffer, totalRead, packetSize - totalRead);
                            if (bytesRead == -1) {
                                throw new IOException("Соединение закрыто при чтении данных пакета.");
                            }
                            totalRead += bytesRead;
                        }

                        // 3. Теперь у нас есть чистый пакет packetBuffer. Отправляем его в декодер.
                        int inputBufferIndex = videoDecoder.dequeueInputBuffer(10000);
                        if (inputBufferIndex >= 0) {
                            ByteBuffer inputBuffer = videoDecoder.getInputBuffer(inputBufferIndex);
                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                inputBuffer.put(packetBuffer);

                                // Если пакет очень маленький (меньше ~100 байт), скорее всего это SPS/PPS
                                if (packetSize < 100) {
                                    Log.i(TAG, "Отправка конфигурационного пакета (SPS/PPS) в декодер. Размер: " + packetSize);
                                    videoDecoder.queueInputBuffer(inputBufferIndex, 0, packetSize, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                                } else {
                                    Log.d(TAG, "Отправка видеокадра в декодер. Размер: " + packetSize);
                                    videoDecoder.queueInputBuffer(inputBufferIndex, 0, packetSize, System.nanoTime() / 1000, 0);
                                }
                            }
                        }

                        // Получаем декодированные кадры и отправляем их на Surface
                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        int outputBufferIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 0);

                        if (outputBufferIndex >= 0) {
                            Log.d(TAG, "КАДР ГОТОВ! Рендерим на Surface.");
                            videoDecoder.releaseOutputBuffer(outputBufferIndex, true);
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            Log.i(TAG, "Формат выходного потока декодера изменился: " + videoDecoder.getOutputFormat());
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Ошибка сети или подключения", e);
                    runOnUiThread(() -> {
                        Toast.makeText(ClientActivity.this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        controlsContainer.setVisibility(View.VISIBLE); // Показываем UI обратно
                    });
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Ошибка состояния MediaCodec", e);
                } finally {
                    Log.d(TAG, "Сетевой поток завершен.");
                    isConnectionRequested = false;
                }
            });
            networkThread.start();
            // =================== КОНЕЦ КЛЮЧЕВЫХ ИЗМЕНЕНИЙ ===================

        } catch (IOException e) {
            Log.e(TAG, "Не удалось настроить декодер", e);
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

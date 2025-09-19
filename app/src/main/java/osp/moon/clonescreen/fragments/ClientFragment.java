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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import osp.moon.clonescreen.customviews.AutoFitSurfaceView;
import osp.moon.clonescreen.R;

public class ClientFragment extends Fragment implements SurfaceHolder.Callback {

    private static final String TAG = ClientFragment.class.getName();

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int PORT = 12345;

    private AutoFitSurfaceView surfaceView;
    private Surface surface;
    private MediaCodec videoDecoder;
    private Thread networkThread;
    private EditText ipInput;
    private LinearLayout controlsContainer;
    private String masterIpAddress = "192.168.1.136";

    private final AtomicBoolean shouldBeConnecting = new AtomicBoolean(false);

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

        ipInput = root.findViewById(R.id.ip_address_input);
        ipInput.setText(masterIpAddress);

        controlsContainer = root.findViewById(R.id.controls_container);
        surfaceView = root.findViewById(R.id.client_surface_view);
        surfaceView.getHolder().addCallback(this);

        Button connectButton = root.findViewById(R.id.connect_button);
        connectButton.setOnClickListener(v -> {
            Log.d(TAG, "onClick: Нажата кнопка 'Подключиться'.");
            masterIpAddress = ipInput.getText().toString();
            if (masterIpAddress.isEmpty()) {
                Toast.makeText(requireActivity(), requireActivity().getString(R.string.enter_ip_toast_message), Toast.LENGTH_SHORT).show();
                return;
            }
            shouldBeConnecting.set(true);
            controlsContainer.setVisibility(View.GONE);
            startClient();
        });
        return root;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated: Surface создан и готов.");
        surface = holder.getSurface();
        if (shouldBeConnecting.get()) {
            Log.d(TAG, "surfaceCreated: Запускаем клиент, так как подключение уже было запрошено.");
            startClient();
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed: Surface уничтожен. Останавливаем клиент.");
        stopClient();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged: format=" + format + ", width=" + width + ", height=" + height);
    }

    private void startClient() {
        Log.d(TAG, "startClient: Проверка условий для запуска.");
        if (surface == null || !surface.isValid()) {
            Log.w(TAG, "startClient: Surface не готов, запуск отложен.");
            return;
        }
        if (networkThread != null && networkThread.isAlive()) {
            Log.w(TAG, "startClient: Сетевой поток уже запущен, новый не создаем.");
            return;
        }
        Log.d(TAG, "startClient: Все условия выполнены, запускаем сетевой поток.");

        networkThread = new Thread(() -> {
            Log.d(TAG, "networkThread: Поток запущен. Входим в цикл переподключения.");
            while (shouldBeConnecting.get() && !Thread.currentThread().isInterrupted()) {
                try (Socket socket = new Socket(masterIpAddress, PORT)) {
                    Log.i(TAG, "networkThread: УСПЕШНО ПОДКЛЮЧЕНО к " + masterIpAddress);
                    requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity(), requireActivity().getString(R.string.connected_toast_message), Toast.LENGTH_SHORT).show());

                    try (InputStream inputStream = socket.getInputStream()) {
                        Log.d(TAG, "networkThread: Начинаем цикл чтения данных из сокета.");
                        // Настраиваем декодер один раз с "заглушкой", реальный размер придет из потока.
                        setupDecoder(1, 1);

                        while (shouldBeConnecting.get() && !Thread.currentThread().isInterrupted()) {
                            int packetType = inputStream.read();
                            if (packetType == -1) {
                                throw new IOException("Сервер корректно закрыл соединение (read returned -1)");
                            }

                            if (packetType == 2) {
                                Log.i(TAG, "!!! networkThread: ПОЛУЧЕН ПАКЕТ ТИП 2 (Разрешение) !!!");
                                byte[] widthBytes = readNBytes(inputStream, 4);
                                byte[] heightBytes = readNBytes(inputStream, 4);
                                int receivedWidth = ByteBuffer.wrap(widthBytes).asIntBuffer().get();
                                int receivedHeight = ByteBuffer.wrap(heightBytes).asIntBuffer().get();
                                Log.i(TAG, "networkThread: Новое разрешение от сервера: " + receivedWidth + "x" + receivedHeight);

                                // Перенастраиваем декодер с новым разрешением
                                setupDecoder(receivedWidth, receivedHeight);

                            } else if (packetType == 0 || packetType == 1) {
                                if (videoDecoder == null) {
                                    Log.w(TAG, "networkThread: Получен пакет с видео, но декодер еще не готов. Пропускаем.");
                                    continue;
                                }
                                byte[] sizeBuffer = readNBytes(inputStream, 4);
                                int packetSize = ByteBuffer.wrap(sizeBuffer).asIntBuffer().get();

                                if (packetSize <= 0 || packetSize > 2_000_000) throw new IOException("Неверный размер пакета: " + packetSize);

                                byte[] packetBuffer = readNBytes(inputStream, packetSize);
                                feedDecoder(packetBuffer, packetType == 0);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (shouldBeConnecting.get()) {
                        Log.e(TAG, "networkThread: Ошибка в цикле подключения: " + e.getMessage());
                        Log.w(TAG, "networkThread: Пауза 2 секунды перед переподключением...");
                        requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity(), requireActivity().getString(R.string.reconnecting_toast_message), Toast.LENGTH_SHORT).show());
                        try { Thread.sleep(2000); } catch (InterruptedException interruptedException) {
                            Log.w(TAG, "networkThread: Поток прерван во время паузы.");
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            Log.d(TAG, "networkThread: Вышли из основного цикла. Поток завершается.");
            requireActivity().runOnUiThread(() -> {
                Log.d(TAG, "UI Thread: Показываем панель управления.");
                controlsContainer.setVisibility(View.VISIBLE);
            });
        });
        networkThread.start();
    }

    private byte[] readNBytes(InputStream in, int n) throws IOException {
        byte[] buffer = new byte[n];
        int totalRead = 0;
        while(totalRead < n) {
            int bytesRead = in.read(buffer, totalRead, n - totalRead);
            if (bytesRead == -1) throw new IOException("Соединение закрыто во время чтения " + n + " байт.");
            totalRead += bytesRead;
        }
        return buffer;
    }

    private void setupDecoder(int width, int height) {
        Log.d(TAG, "setupDecoder: Начало настройки/перенастройки декодера.");
        try {
            if (videoDecoder != null) {
                Log.d(TAG, "setupDecoder: Освобождаем старый декодер.");
                videoDecoder.stop();
                videoDecoder.release();
            }
            Log.i(TAG, "setupDecoder: Настройка с разрешением: " + width + "x" + height);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            videoDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
            videoDecoder.configure(format, surface, null, 0);
            videoDecoder.start();
            Log.i(TAG, "setupDecoder: Декодер успешно настроен и запущен.");
        } catch (Exception e) {
            Log.e(TAG, "setupDecoder: КРИТИЧЕСКАЯ ОШИБКА при настройке декодера.", e);
        }
    }

    private void feedDecoder(byte[] data, boolean isConfig) {
        if (videoDecoder == null) return;
        try {
            int inputBufferIndex = videoDecoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = videoDecoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data);
                    int flags = isConfig ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                    videoDecoder.queueInputBuffer(inputBufferIndex, 0, data.length, System.nanoTime() / 1000, flags);
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 0);

            while (outputBufferIndex >= 0) {
                videoDecoder.releaseOutputBuffer(outputBufferIndex, true);
                outputBufferIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }

            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "!!! feedDecoder: Формат декодера изменился: " + videoDecoder.getOutputFormat());
                MediaFormat newFormat = videoDecoder.getOutputFormat();
                int newWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                int newHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                Log.d(TAG, "feedDecoder: Новые размеры от декодера: " + newWidth + "x" + newHeight);

                // Теперь, когда мы доверяем данным от декодера, мы используем ИХ для установки AspectRatio
                requireActivity().runOnUiThread(() -> {
                    Log.d(TAG, "UI Thread: Устанавливаем пропорции " + newWidth + "x" + newHeight + " из данных декодера.");
                    surfaceView.setAspectRatio(newWidth, newHeight);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "feedDecoder: Ошибка при работе с декодером.", e);
        }
    }

    private void stopClient() {
        Log.d(TAG, "stopClient: Начало остановки клиента.");
        shouldBeConnecting.set(false);
        if (networkThread != null) {
            Log.d(TAG, "stopClient: Прерываем сетевой поток.");
            networkThread.interrupt();
            networkThread = null;
        }
        if (videoDecoder != null) {
            Log.d(TAG, "stopClient: Освобождаем декодер.");
            try {
                videoDecoder.stop();
                videoDecoder.release();
            } catch (Exception e) {
                Log.e(TAG, "stopClient: Ошибка при остановке декодера.", e);
            }
            videoDecoder = null;
        }
        Log.d(TAG, "stopClient: Остановка клиента завершена.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Активити уничтожается.");
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

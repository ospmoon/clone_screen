package osp.moon.clonescreen.services;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class TcpServer extends Thread {

    private static final String TAG = TcpServer.class.getName();
    public static final int SERVER_PORT = 12345;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream outputStream;
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    private final Object clientConnectionLock = new Object();
    private volatile boolean isClientConnected = false;
    private final ScreenCaptureService serviceCallback;

    public boolean isClientConnected() {
        return isClientConnected;
    }

    public TcpServer(ScreenCaptureService callback) { // Конструктор
        this.serviceCallback = callback;
    }

    @Override
    public void run() {
        Log.d(TAG, "run: Сервер запускается на порту: " + SERVER_PORT);
        try {
            serverSocket = new ServerSocket(SERVER_PORT);
        } catch (Exception e) {
            Log.e(TAG, "run: Ошибка при создании ServerSocket.", e);
            isRunning.set(false);
            if (serviceCallback != null) {
                // Уведомить сервис, что TCP сервер не смог стартануть, если это необходимо
            }
            return;
        }

        while (isRunning.get() && !serverSocket.isClosed()) {
            Log.d(TAG, "run: Ожидание нового подключения клиента в serverSocket.accept()...");
            try {
                clientSocket = serverSocket.accept(); // Блокирующий вызов
                Log.i(TAG, "run: КЛИЕНТ ПОДКЛЮЧЕН: " + clientSocket.getInetAddress());
                outputStream = clientSocket.getOutputStream(); // Получаем стрим

                synchronized (clientConnectionLock) {
                    isClientConnected = true; // Устанавливаем флаг
                    clientConnectionLock.notifyAll();
                }

                // === НАЧАЛО: ИНИЦИАЛИЗАЦИЯ ДЛЯ НОВОГО КЛИЕНТА ===
                if (serviceCallback != null) {
                    // 1. Уведомить сервис (для обновления UI рамки)
                    // Это должно быть сделано из ScreenCaptureService, когда он узнает о подключении
                    serviceCallback.onClientConnectedStateChanged(true);

                    // 2. Получить текущее разрешение и SPS/PPS от сервиса и отправить
                    // Эти операции теперь выполняются в потоке TcpServer, что безопасно
                    int currentWidth = serviceCallback.getCurrentScreenWidth();
                    int currentHeight = serviceCallback.getCurrentScreenHeight();
                    byte[] sps = serviceCallback.getLastSps();
                    byte[] pps = serviceCallback.getLastPps();

                    if (currentWidth > 0 && currentHeight > 0) {
                        Log.i(TAG, "run: Отправка разрешения новому клиенту: " + currentWidth + "x" + currentHeight);
                        sendResolution(currentWidth, currentHeight);
                    } else {
                        Log.w(TAG, "run: Не удалось получить актуальное разрешение от сервиса для отправки.");
                    }

                    if (sps != null && pps != null) {
                        Log.i(TAG, "run: Отправка SPS/PPS новому клиенту. SPS: " + sps.length + " bytes, PPS: " + pps.length + " bytes.");
                        sendData(sps, true);
                        sendData(pps, true);
                    } else {
                        Log.w(TAG, "run: SPS/PPS еще не готовы, клиент может получить их позже из drainEncoder.");
                        serviceCallback.requestSyncFrame(); // Запрашиваем генерацию SPS/PPS
                    }
                }
                // === КОНЕЦ: ИНИЦИАЛИЗАЦИЯ ДЛЯ НОВОГО КЛИЕНТА ===

                // Просто ждем, пока соединение не будет разорвано (heartbeat)
                while (isClientConnected && clientSocket != null && clientSocket.isConnected() && !clientSocket.isClosed()) {
                    try {
                        clientSocket.sendUrgentData(0xFF); // Heartbeat
                        Thread.sleep(2000);
                    } catch (Exception e) {
                        Log.w(TAG, "run: Соединение с клиентом потеряно (heartbeat/sleep). " + e.getMessage());
                        break;
                    }
                }

            } catch (Exception e) {
                if (isRunning.get() && !serverSocket.isClosed()) { // Проверяем, что ошибка не из-за закрытия serverSocket
                    Log.w(TAG, "run: IOException в цикле принятия клиента или heartbeat: " + e.getMessage());
                } else if (!isRunning.get()){
                    Log.d(TAG, "run: ServerSocket был закрыт во время accept() из-за остановки сервера.");
                }
            } finally {
                Log.d(TAG, "run: Блок finally (внутренний цикл). Закрываем ресурсы текущего клиента.");
                // onClientConnectedStateChanged(false) теперь вызывается из closeClientResources
                closeClientResources();
            }
        }

        if (serverSocket == null || serverSocket.isClosed()) {
            Log.d(TAG, "run: ServerSocket не был создан или уже закрыт штатно. TcpServer поток завершается.");
        }
        Log.i(TAG, "TcpServer: Поток run() завершает свою работу. isRunning=" + isRunning.get());
    }

    public void waitForClient() throws InterruptedException {
        synchronized (clientConnectionLock) {
            while (!isClientConnected) {
                Log.d(TAG, "waitForClient: Поток кодировщика ждет подключения клиента...");
                clientConnectionLock.wait();
            }
            Log.i(TAG, "waitForClient: Поток кодировщика 'проснулся'. Клиент на месте.");
        }
    }

    public synchronized void sendResolution(int width, int height) {
        if (!isClientConnected) {
            Log.w(TAG, "sendResolution: Попытка отправки разрешения, но клиент НЕ ПОДКЛЮЧЕН (isClientConnected=false).");
            return;
        }
        if (outputStream == null) {
            Log.e(TAG, "sendResolution: КРИТИЧЕСКАЯ ОШИБКА: outputStream is NULL для отправки разрешения, хотя клиент считается подключенным!");
            // Не вызываем closeClientResources() здесь, чтобы не было рекурсии, если это вызвано из closeClientResources
            return;
        }
        try {
            Log.d(TAG, "sendResolution: Отправка пакета ТИП 2: " + width + "x" + height);
            outputStream.write(2);
            outputStream.write(ByteBuffer.allocate(4).putInt(width).array());
            outputStream.write(ByteBuffer.allocate(4).putInt(height).array());
            outputStream.flush();
            Log.d(TAG, "sendResolution: Пакет ТИП 2 успешно отправлен.");
        } catch (Exception e) {
            Log.e(TAG, "sendResolution: НЕОЖИДАННАЯ Ошибка при отправке разрешения.", e);
            closeClientResources();
        }
    }

    public synchronized void sendData(byte[] data, boolean isConfig) {
        if (!isClientConnected) {
            // Log.w(TAG, "sendData: Попытка отправки данных, но клиент НЕ ПОДКЛЮЧЕН (isClientConnected=false).");
            return; // Тихо выходим, если некуда слать (например, drainEncoder пытается слать, а клиент только что отвалился)
        }
        if (outputStream == null) {
            Log.e(TAG, "sendData: КРИТИЧЕСКАЯ ОШИБКА: outputStream is NULL для отправки данных, хотя клиент считается подключенным!");
            return;
        }
        try {
            int packetType = isConfig ? 0 : 1;
            Log.i(TAG, " TcpServer.sendData: ПОПЫТКА ЗАПИСИ. Тип: " + packetType + ", Размер: " + data.length + ", Thread: " + Thread.currentThread().getName());
            outputStream.write(packetType);
            outputStream.write(ByteBuffer.allocate(4).putInt(data.length).array());
            outputStream.write(data);
            outputStream.flush();
        } catch (Throwable t) { // Ловим Throwable для максимальной информации
            Log.e(TAG, " TcpServer.sendData: КРИТИЧЕСКАЯ ОШИБКА Throwable при отправке данных. Тип ошибки: " + t.getClass().getSimpleName(), t);
            closeClientResources();
        }
    }

    private synchronized void closeClientResources() {
        if (!isClientConnected && outputStream == null && clientSocket == null) {
            // Ресурсы уже закрыты или не были открыты для этого клиента
            // Log.d(TAG, "closeClientResources: Попытка закрыть уже закрытые/неинициализированные ресурсы.");
            return;
        }

        Log.i(TAG, "closeClientResources: Закрытие ресурсов КЛИЕНТА. Текущий isClientConnected=" + isClientConnected);
        boolean wasConnected = isClientConnected;
        isClientConnected = false; // Сначала флаг

        // Закрываем outputStream
        if (outputStream != null) {
            try {
                outputStream.close();
                Log.d(TAG, "closeClientResources: outputStream закрыт.");
            } catch (Exception e) {
                Log.e(TAG, "closeClientResources: Ошибка при закрытии outputStream.", e);
            } finally {
                outputStream = null;
            }
        }

        // Закрываем clientSocket
        if (clientSocket != null) {
            try {
                if (!clientSocket.isClosed()) {
                    clientSocket.close();
                    Log.d(TAG, "closeClientResources: clientSocket закрыт.");
                } else {
                    Log.d(TAG, "closeClientResources: clientSocket уже был закрыт.");
                }
            } catch (Exception e) {
                Log.e(TAG, "closeClientResources: Ошибка при закрытии clientSocket.", e);
            } finally {
                clientSocket = null;
            }
        }

        // Уведомляем сервис об отключении
        if (wasConnected && serviceCallback != null) {
            Log.d(TAG, "closeClientResources: Уведомляем сервис об отключении клиента (wasConnected=true).");
            serviceCallback.onClientConnectedStateChanged(false);
        } else if (serviceCallback != null && outputStream == null && clientSocket == null) {
            // Если клиент не был formal'но isConnected, но ресурсы теперь точно закрыты,
            // а сервис мог думать, что он еще есть (маловероятно с текущей логикой, но для полноты)
            // Log.d(TAG, "closeClientResources: Уведомляем сервис об отключении клиента (wasConnected=false, но ресурсы закрыты).");
            // serviceCallback.onClientConnectedStateChanged(false);
        }
    }

    public void stopServer() {
        Log.i(TAG, "stopServer: Начало полной остановки TCP сервера.");
        isRunning.set(false);
        // Прерываем поток, чтобы он вышел из accept() или sleep()
        // Это должно быть сделано до закрытия serverSocket, чтобы избежать SocketException в accept(),
        // которую мы могли бы обработать как обычное завершение.
        interrupt();

        // Закрываем ServerSocket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                Log.d(TAG, "stopServer: ServerSocket закрыт.");
            }
        } catch (Exception e) {
            Log.e(TAG, "stopServer: Ошибка при закрытии ServerSocket.", e);
        }

        // Закрываем ресурсы активного клиента, если он есть
        // closeClientResources() здесь может быть избыточен, так как он вызовется из finally в run(),
        // когда interrupt() или serverSocket.close() разблокируют accept().
        // Но для надежности, если поток по какой-то причине не выйдет из цикла run штатно.
        if (isClientConnected || clientSocket != null) {
            Log.d(TAG, "stopServer: Дополнительный вызов closeClientResources при остановке сервера.");
            closeClientResources(); // Убедимся, что последний клиент точно отключается
        }

        Log.i(TAG, "stopServer: Остановка TCP сервера завершена.");
    }
}

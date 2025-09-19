package osp.moon.clonescreen.services;

import android.util.Log;

import java.io.IOException;import java.io.OutputStream;
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
        try {
            Log.d(TAG, "run: Сервер запускается на порту: " + SERVER_PORT);
            serverSocket = new ServerSocket(SERVER_PORT);

            while (isRunning.get() && !serverSocket.isClosed()) {
                Log.d(TAG, "run: Ожидание нового подключения клиента в serverSocket.accept()...");
                try {
                    clientSocket = serverSocket.accept();
                    Log.i(TAG, "run: КЛИЕНТ ПОДКЛЮЧЕН: " + clientSocket.getInetAddress());
                    if (serviceCallback != null) {
                        serviceCallback.onClientConnectedStateChanged(true);
                    }
                    outputStream = clientSocket.getOutputStream();

                    synchronized (clientConnectionLock) {
                        isClientConnected = true;
                        clientConnectionLock.notifyAll(); // "Пробуждаем" поток, который ждет
                    }

                    // Просто ждем, пока соединение не будет разорвано
                    while (isClientConnected && clientSocket != null && clientSocket.isConnected()) {
                        try {
                            // Отправка "heartbeat" для быстрой детекции разрыва
                            clientSocket.sendUrgentData(0xFF);
                            Thread.sleep(2000);
                        } catch (IOException e) {
                            Log.w(TAG, "run: Соединение с клиентом потеряно (sendUrgentData провалился). " + e.getMessage());
                            break; // Выходим из внутреннего цикла, чтобы закрыть ресурсы
                        }
                    }

                } catch (IOException e) {
                    if (isRunning.get()) {
                        Log.w(TAG, "run: Ошибка принятия клиента или во внутреннем цикле. " + e.getMessage());
                    }
                } finally {
                    Log.d(TAG, "run: Блок finally. Закрываем ресурсы текущего клиента.");
                    closeClientResources();
                    if (serviceCallback != null) {
                        serviceCallback.onClientConnectedStateChanged(false);
                    }
                }
            }
        } catch (Exception e) {
            if (isRunning.get()) Log.e(TAG, "run: Критическая ошибка в TcpServer (не удалось создать ServerSocket?).", e);
        } finally {
            Log.d(TAG, "run: Блок finally. Остановка всего сервера.");
            stopServer();
        }
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
        if (outputStream != null && isClientConnected) {
            try {
                Log.d(TAG, "sendResolution: Отправка пакета ТИП 2: " + width + "x" + height);
                outputStream.write(2); // Тип пакета 2: разрешение
                outputStream.write(ByteBuffer.allocate(4).putInt(width).array());
                outputStream.write(ByteBuffer.allocate(4).putInt(height).array());
                outputStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "sendResolution: Ошибка при отправке.", e);
                closeClientResources();
            }
        } else {
            Log.w(TAG, "sendResolution: Попытка отправки, но клиент не подключен.");
        }
    }

    public synchronized void sendData(byte[] data, boolean isConfig) {
        if (outputStream != null && isClientConnected) {
            try {
                int packetType = isConfig ? 0 : 1;
                outputStream.write(packetType); // Тип пакета 0 (конфиг) или 1 (кадр)
                outputStream.write(ByteBuffer.allocate(4).putInt(data.length).array());
                outputStream.write(data);
                outputStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "sendData: Ошибка при отправке.", e);
                closeClientResources();
            }
        }
    }

    private synchronized void closeClientResources() {
        if (isClientConnected) {
            Log.i(TAG, "closeClientResources: Закрытие ресурсов КЛИЕНТА.");
            isClientConnected = false;
            try {
                if (outputStream != null) outputStream.close();
                if (clientSocket != null) clientSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "closeClientResources: Ошибка при закрытии.", e);
            } finally {
                outputStream = null;
                clientSocket = null;
            }
        }
    }

    public void stopServer() {
        Log.i(TAG, "stopServer: Начало полной остановки TCP сервера.");
        isRunning.set(false);
        closeClientResources();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                Log.d(TAG, "stopServer: ServerSocket закрыт.");
            }
        } catch (IOException e) {
            Log.e(TAG, "stopServer: Ошибка при закрытии ServerSocket.", e);
        }
        interrupt(); // Прерываем сам поток сервера
        Log.i(TAG, "stopServer: Остановка TCP сервера завершена.");
    }
}

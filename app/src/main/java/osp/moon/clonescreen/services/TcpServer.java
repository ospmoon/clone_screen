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

    // --- ИЗМЕНЕНИЕ 1: Добавляем объект-замок ---
    private final Object clientConnectionLock = new Object();
    private boolean isClientConnected = false;

    @Override
    public void run() {
        try {
            Log.d(TAG, "Сервер запускается на порту: " + SERVER_PORT);
            serverSocket = new ServerSocket(SERVER_PORT);

            while (isRunning.get()) {
                Log.d(TAG, "Ожидание подключения клиента...");
                clientSocket = serverSocket.accept(); // Ждем клиента
                Log.d(TAG, "Клиент подключен: " + clientSocket.getInetAddress());

                outputStream = clientSocket.getOutputStream();

                // --- ИЗМЕНЕНИЕ 2: Сигнализируем, что клиент подключен ---
                synchronized (clientConnectionLock) {
                    isClientConnected = true;
                    clientConnectionLock.notifyAll(); // "Пробуждаем" поток, который ждет этого события
                }

                // Цикл проверки активности клиента
                while (isRunning.get() && clientSocket != null && clientSocket.isConnected()) {
                    try {
                        clientSocket.sendUrgentData(0xFF);
                        Thread.sleep(2000);
                    } catch (Exception e) {
                        Log.w(TAG, "Соединение с клиентом потеряно.");
                        break;
                    }
                }

                closeClientResources();
            }
        } catch (IOException e) {
            Log.e(TAG, "Ошибка в работе TcpServer", e);
        } finally {
            stopServer();
        }
    }

    // --- ИЗМЕНЕНИЕ 3: Метод для ожидания клиента ---
    public void waitForClient() throws InterruptedException {
        synchronized (clientConnectionLock) {
            while (!isClientConnected) {
                Log.d(TAG, "Поток кодировщика ждет подключения клиента...");
                clientConnectionLock.wait();
            }
            Log.d(TAG, "Поток кодировщика 'проснулся'. Клиент на месте.");
        }
    }

    public synchronized void sendData(byte[] data) {
        if (outputStream != null && isClientConnected) {
            try {
                // --- НОВАЯ ЛОГИКА ---
                // 1. Получаем размер данных (длину массива).
                int size = data.length;
                // 2. Преобразуем int в массив из 4 байт.
                byte[] sizeBytes = ByteBuffer.allocate(4).putInt(size).array();

                // 3. Сначала отправляем 4 байта с размером.
                outputStream.write(sizeBytes);
                // 4. Затем отправляем сами данные.
                outputStream.write(data);

                outputStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "Ошибка при отправке данных", e);
                closeClientResources();
            }
        }
    }

    private synchronized void closeClientResources() {
        isClientConnected = false; // Сбрасываем флаг
        try {
            if (outputStream != null) outputStream.close();
            if (clientSocket != null) clientSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка при закрытии ресурсов", e);
        } finally {
            outputStream = null;
            clientSocket = null;
        }
    }

    public void stopServer() {
        isRunning.set(false);
        closeClientResources();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) { /* ignore */ }
        interrupt();
    }
}

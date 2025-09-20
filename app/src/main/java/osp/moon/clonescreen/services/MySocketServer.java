package osp.moon.clonescreen.services;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import osp.moon.clonescreen.R;

public class MySocketServer {

    private final String TAG = MySocketServer.class.getName();
    public static final int SERVER_PORT = 5000;
    private ServerSocket mServer;
    private Socket mClient;
    private OutputStream mOutputStream;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicReference<Context> mContext = new AtomicReference<>();
    private final AtomicBoolean isClientConnected = new AtomicBoolean(false);
    private final ServerCallback mCallback;
    public interface ServerCallback {
        void onStarted();
        void onStoped(String reason);
        void onClientConnected();
        void onClientDisconnected();
    }

    public boolean isClientConnected() {
        return isClientConnected.get();
    }

    public MySocketServer(final Context context, final ServerCallback callback) {
        this.mContext.set(context);
        this.mCallback = callback;
    }

    public boolean start() {
        Log.d(TAG, "run(): port: " + SERVER_PORT);
        if (!createServer()) {
            stop(mContext.get().getString(R.string.failed_create_server_error_message));
            return false;
        }

        isRunning.set(true);
        if (mCallback != null) mCallback.onStarted();

        if (!waitingConnection()) {
            stop(mContext.get().getString(R.string.waiting_connection_error_message));
            return false;
        }

        if (!getOutputStream()) {
            stop(mContext.get().getString(R.string.failed_create_server_error_message));
            return false;
        }

        isClientConnected.set(true);
        if (mCallback != null) mCallback.onClientConnected();

        return true;

        /*while (mClient.isConnected() && !mClient.isClosed()) {
            try {
                mClient.sendUrgentData(0xFF); // Heartbeat
                Thread.sleep(2000);
            } catch (Exception e) {
                Log.e(TAG, "run: Connection with the client was lost. " + e);
                break;
            }
        }

        closeClientResources();
        if (mCallback != null) mCallback.onClientDisconnected();*/
    }

    private boolean createServer() {
        try {
            mServer = new ServerSocket(SERVER_PORT);
        } catch (Exception e) {
            Log.e(TAG, "run(): new ServerSocket(SERVER_PORT)", e);
            return false;
        }
        return true;
    }

    private boolean waitingConnection() {
        try {
            mClient = mServer.accept(); // BLOCK THREAD
        } catch (Exception e) {
            Log.e(TAG, "run(): mServer.accept()", e);
            return false;
        }
        return true;
    }

    private boolean getOutputStream() {
        try {
            mOutputStream = mClient.getOutputStream();
        } catch (Exception e) {
            Log.e(TAG, "run(): mClient.getOutputStream()", e);
            return false;
        }
        return true;
    }

    public synchronized void sendResolution(int width, int height) {
        if (!isClientConnected.get()) {
            Log.w(TAG, "sendResolution: Попытка отправки разрешения, но клиент НЕ ПОДКЛЮЧЕН (isClientConnected=false).");
            return;
        }
        if (mOutputStream == null) {
            Log.e(TAG, "sendResolution: КРИТИЧЕСКАЯ ОШИБКА: outputStream is NULL для отправки разрешения, хотя клиент считается подключенным!");
            // Не вызываем closeClientResources() здесь, чтобы не было рекурсии, если это вызвано из closeClientResources
            return;
        }
        try {
            Log.d(TAG, "sendResolution: Отправка пакета ТИП 2: " + width + "x" + height);
            mOutputStream.write(2);
            mOutputStream.write(ByteBuffer.allocate(4).putInt(width).array());
            mOutputStream.write(ByteBuffer.allocate(4).putInt(height).array());
            mOutputStream.flush();
            Log.d(TAG, "sendResolution: Пакет ТИП 2 успешно отправлен.");
        } catch (Exception e) {
            Log.e(TAG, "sendResolution: НЕОЖИДАННАЯ Ошибка при отправке разрешения.", e);
            closeClientResources();
        }
    }

    public synchronized void sendData(byte[] data, boolean isConfig) {
        if (!isClientConnected.get()) {
            // Log.w(TAG, "sendData: Попытка отправки данных, но клиент НЕ ПОДКЛЮЧЕН (isClientConnected=false).");
            return; // Тихо выходим, если некуда слать (например, drainEncoder пытается слать, а клиент только что отвалился)
        }
        if (mOutputStream == null) {
            Log.e(TAG, "sendData: КРИТИЧЕСКАЯ ОШИБКА: outputStream is NULL для отправки данных, хотя клиент считается подключенным!");
            return;
        }
        try {
            int packetType = isConfig ? 0 : 1;
            Log.i(TAG, " TcpServer.sendData: ПОПЫТКА ЗАПИСИ. Тип: " + packetType + ", Размер: " + data.length + ", Thread: " + Thread.currentThread().getName());
            mOutputStream.write(packetType);
            mOutputStream.write(ByteBuffer.allocate(4).putInt(data.length).array());
            mOutputStream.write(data);
            mOutputStream.flush();
        } catch (Exception e) {
            Log.e(TAG, " TcpServer.sendData: КРИТИЧЕСКАЯ ОШИБКА Throwable при отправке данных.", e);
            closeClientResources();
        }
    }

    private synchronized void closeClientResources() {
        if (mOutputStream != null) {
            try {
                mOutputStream.close();
                Log.d(TAG, "closeClientResources: outputStream закрыт.");
            } catch (Exception e) {
                Log.e(TAG, "closeClientResources: Ошибка при закрытии outputStream.", e);
            } finally {
                mOutputStream = null;
            }
        }

        if (mClient != null) {
            try {
                if (!mClient.isClosed()) {
                    mClient.close();
                    Log.d(TAG, "closeClientResources: clientSocket закрыт.");
                } else {
                    Log.d(TAG, "closeClientResources: clientSocket уже был закрыт.");
                }
            } catch (Exception e) {
                Log.e(TAG, "closeClientResources: Ошибка при закрытии clientSocket.", e);
            } finally {
                mClient = null;
            }
        }
        isClientConnected.set(false);
        if (mCallback != null) mCallback.onClientDisconnected();
    }

    public void stop(String reason) {
        closeClientResources();

        if (mServer != null) {
            try {
                mServer.close();
            } catch (Exception e) {
                Log.e(TAG, "stop(): mServer.close()", e);
            }
        }
        isRunning.set(false);
        if (mCallback != null) mCallback.onStoped(reason);
    }

}

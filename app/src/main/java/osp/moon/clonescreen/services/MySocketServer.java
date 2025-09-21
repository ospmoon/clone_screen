package osp.moon.clonescreen.services;

import android.content.Context;
import android.util.Log;

import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
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
    private Socket mClientSocket;
    private OutputStream mOutputStream;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicReference<Context> mContext = new AtomicReference<>();
    private final AtomicBoolean isClientConnected = new AtomicBoolean(false);
    private final ServerCallback mCallback;
    public interface ServerCallback {
        void onStarted();
        void onStopped(String reason);
        void onClientConnected();
        void onClientDisconnected();
    }

    public boolean isClientConnected() {
        return isClientConnected.get();
    }

    public MySocketServer(final Context context, final ServerCallback callback) {
        Log.d(TAG, "MySocketServer() constructor");
        this.mContext.set(context);
        this.mCallback = callback;
    }

    public boolean startAndWaitClient() throws InterruptedException {
        Log.d(TAG, "startAndWaitClient()");
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
    }

    private boolean createServer() throws InterruptedException {
        Log.d(TAG, "createServer()");
        try {
            mServer = new ServerSocket();
            mServer.setReuseAddress(true);
            mServer.bind(new InetSocketAddress(SERVER_PORT));
        } catch (Exception e) {
            Log.e(TAG, "createServer(): new ServerSocket(SERVER_PORT)", e);
            return false;
        }
        return true;
    }

    private boolean waitingConnection() {
        Log.d(TAG, "waitingConnection()");
        try {
            mClientSocket = mServer.accept(); // BLOCK THREAD
        } catch (Exception e) {
            Log.e(TAG, "run(): mServer.accept()", e);
            return false;
        }
        return true;
    }

    private boolean getOutputStream() {
        Log.d(TAG, "getOutputStream()");
        try {
            mOutputStream = mClientSocket.getOutputStream();
        } catch (Exception e) {
            Log.e(TAG, "run(): mClient.getOutputStream()", e);
            return false;
        }
        return true;
    }

    public synchronized void sendResolution(int width, int height) {
        Log.d(TAG, "sendResolution(): " + width + "x" + height);
        if (!isClientConnected.get() || mOutputStream == null) {
            return;
        }
        try {
            mOutputStream.write(2);
            mOutputStream.write(ByteBuffer.allocate(4).putInt(width).array());
            mOutputStream.write(ByteBuffer.allocate(4).putInt(height).array());
            mOutputStream.flush();
        } catch (Exception e) {
            Log.e(TAG, "sendResolution: Exception", e);
            closeClientResources();
        }
    }

    public synchronized void sendData(byte[] data, boolean isConfig) {
        if (!isClientConnected.get() || mOutputStream == null) {
            return;
        }
        try {
            int packetType = isConfig ? 0 : 1;
            //Log.i(TAG, " TcpServer.sendData: ПОПЫТКА ЗАПИСИ. Тип: " + packetType + ", Размер: " + data.length + ", Thread: " + Thread.currentThread().getName());
            mOutputStream.write(packetType);
            mOutputStream.write(ByteBuffer.allocate(4).putInt(data.length).array());
            mOutputStream.write(data);
            mOutputStream.flush();
        } catch (Exception e) {
            Log.e(TAG, " TcpServer.sendData: Exception", e);
            closeClientResources();
        }
    }

    private synchronized void closeClientResources() {
        Log.d(TAG, "closeClientResources()");
        if (mOutputStream != null) {
            try {
                mOutputStream.close();
            } catch (Exception e) {
                Log.e(TAG, "closeClientResources: Exception1.", e);
            } finally {
                mOutputStream = null;
            }
        }

        if (mClientSocket != null) {
            try {
                if (!mClientSocket.isClosed()) {
                    mClientSocket.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "closeClientResources: Exception2.", e);
            } finally {
                //mClient = null;
            }
        }
        isClientConnected.set(false);
        if (mCallback != null) mCallback.onClientDisconnected();
    }

    public void stop(String reason) {
        Log.d(TAG, "stop(): reason: " + reason);
        closeClientResources();

        if (mServer != null) {
            try {
                mServer.close();
            } catch (Exception e) {
                Log.e(TAG, "stop(): Exception.", e);
            }
        }
        isRunning.set(false);
        if (mCallback != null) mCallback.onStopped(reason);
    }

}

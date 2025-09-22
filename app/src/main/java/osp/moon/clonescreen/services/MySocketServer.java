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
    private ServerSocket mServerSocket;
    private Socket mClientSocket;
    private OutputStream mOutputStream;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicReference<Context> mContext = new AtomicReference<>();
    private final AtomicBoolean isClientConnected = new AtomicBoolean(false);
    private final ServerCallback mCallback;
    public interface ServerCallback {
        void onServerCreated();
        void onFailedCreateServer(String reason);
        void onWaitingForClient();
        void onClientConnected();
        void onClientDisconnected(String reason);
        void onStopped();
    }

    public boolean isClientConnected() {
        return isClientConnected.get();
    }

    public MySocketServer(final Context context, final ServerCallback callback) {
        Log.d(TAG, "MySocketServer() constructor");
        this.mContext.set(context);
        this.mCallback = callback;
    }

    public boolean createServer(int port) {
        Log.d(TAG, "createServer()");
        try {
            mServerSocket = new ServerSocket();
            mServerSocket.setReuseAddress(true);
            mServerSocket.bind(new InetSocketAddress(port));
        } catch (Exception e) {
            Log.e(TAG, "createServer(): new ServerSocket(SERVER_PORT)", e);
            closeServer(mContext.get().getString(R.string.failed_create_server_error_message));
            if (mCallback != null) mCallback.onFailedCreateServer(e.getMessage());
            return false;
        }
        if (mCallback != null) mCallback.onServerCreated();
        return true;
    }

    public void startAndWaitClient() {
        Log.d(TAG, "startAndWaitClient()");

        if (mServerSocket == null || mServerSocket.isClosed()) {
            Log.e(TAG, "startAndWaitClient(): mServerSocket == null || mServerSocket.isClosed()");
            closeServer("mServerSocket == null || mServerSocket.isClosed()");
            return;
        }

        isRunning.set(true);
        if (mCallback != null) mCallback.onWaitingForClient();

        if (waitingForClient() && getOutputStream()) {
            isClientConnected.set(true);
            if (mCallback != null) mCallback.onClientConnected();
        } else {
            isRunning.set(false);
            if (mCallback != null) mCallback.onStopped();
        }
    }

    private boolean waitingForClient() {
        Log.d(TAG, "waitingForClient()");
        try {
            mClientSocket = mServerSocket.accept(); // BLOCK THREAD
        } catch (Exception e) {
            Log.e(TAG, "waitingForClient(): mServer.accept()", e);
            closeServer(mContext.get().getString(R.string.waiting_connection_error_message));
            return false;
        }
        return true;
    }

    private boolean getOutputStream() {
        Log.d(TAG, "getOutputStream()");
        try {
            mOutputStream = mClientSocket.getOutputStream();
        } catch (Exception e) {
            Log.e(TAG, "getOutputStream(): mClient.getOutputStream()", e);
            closeClient(mContext.get().getString(R.string.failed_create_server_error_message));
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
            //closeClientResources("sendResolution(), " + e.getMessage());
            isClientConnected.set(false);
            if (mCallback != null) mCallback.onClientDisconnected(e.getMessage());
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
            //closeClientResources("sendData(), " + e.getMessage());
            isClientConnected.set(false);
            if (mCallback != null) mCallback.onClientDisconnected(e.getMessage());
        }
    }

    private synchronized void closeClientResources(String reason) {
        Log.d(TAG, "closeClientResources()");
        closeStream(reason);
        closeClient(reason);
    }

    public void stop(String reason) {
        Log.d(TAG, "stop(): reason: " + reason);
        closeClientResources(reason);
        closeServer(reason);
        isRunning.set(false);
        if (mCallback != null) mCallback.onStopped();
    }

    private synchronized void closeServer(String reason) {
        Log.d(TAG, "closeServer(), reason: " + reason);
        if (mServerSocket != null && !mServerSocket.isClosed()) {
            try {
                mServerSocket.close();
            } catch (Exception e2) {
                Log.e(TAG, "createServer(): mServerSocket.close()", e2);
            }
        }
        mServerSocket = null;
    }

    private synchronized void closeClient(String reason) {
        Log.d(TAG, "closeClient(), reason: " + reason);
        if (mClientSocket != null && !mClientSocket.isClosed()) {
            try {
                mClientSocket.close();
            } catch (Exception e2) {
                Log.e(TAG, "createServer(): mClientSocket.close()", e2);
            }
        }
        mClientSocket = null;
    }

    private synchronized void closeStream(String reason) {
        Log.d(TAG, "closeStream(), reason: " + reason);
        if (mOutputStream != null) {
            try {
                mOutputStream.close();
            } catch (Exception e2) {
                Log.e(TAG, "closeStream(): mClientSocket.close()", e2);
            }
        }
        mOutputStream = null;
    }

}

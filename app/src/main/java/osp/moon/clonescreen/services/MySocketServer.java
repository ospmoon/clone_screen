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

import osp.moon.clonescreen.App;
import osp.moon.clonescreen.R;
import osp.moon.clonescreen.helpers.AppHelper;

public class MySocketServer extends Thread {

    private final String TAG = MySocketServer.class.getName();
    private ServerSocket mServerSocket;
    private Socket mClientSocket;
    private OutputStream mOutputStream;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicReference<Context> mContext = new AtomicReference<>();
    private final AtomicBoolean isClientConnected = new AtomicBoolean(false);

    private final ServerCallback mCallback;
    public interface ServerCallback {
        void onServerStopped(String reason);
        void onWaitingForClient();
        void onClientConnected(String ip);
        void onClientDisconnected(String reason);
    }

    public boolean isClientConnected() {
        return isClientConnected.get();
    }

    public MySocketServer(final Context context, final ServerCallback callback) {
        Log.d(TAG, "MySocketServer() constructor");
        this.mContext.set(context);
        this.mCallback = callback;
    }

    private boolean createServer() {
        Log.d(TAG, "createServer()");
        try {
            mServerSocket = new ServerSocket();
            mServerSocket.setReuseAddress(true);
            mServerSocket.bind(new InetSocketAddress(AppHelper.getPort()));
        } catch (Exception e) {
            Log.e(TAG, "createServer(): new ServerSocket(SERVER_PORT)", e);
            stop(mContext.get().getString(R.string.failed_create_server_error_message));
            if (mCallback != null) mCallback.onServerStopped(e.getMessage());
            return false;
        }
        isRunning.set(true);
        return true;
    }

    @Override
    public void run() {
        Log.d(TAG, "run()");
        if (!createServer()) {
            return;
        }
        while (isRunning.get() && !mServerSocket.isClosed()) {

            if (mCallback != null) mCallback.onWaitingForClient();
            if (!waitingForClient()) {
                break;
            }

            if (!getOutputStream()) {
                continue;
            }
            isClientConnected.set(true);
            if (mCallback != null) mCallback.onClientConnected(mClientSocket.getInetAddress().toString());

            sendResolution();
            sendData(AppHelper.getSps(), true);
            sendData(AppHelper.getPps(), true);

            // Просто ждем, пока соединение не будет разорвано (heartbeat)
            while (isClientConnected.get()
                    && mClientSocket != null
                    && mClientSocket.isConnected()
                    && !mClientSocket.isClosed()) {
                try {
                    mClientSocket.sendUrgentData(0xFF); // Heartbeat
                    Thread.sleep(2000);
                } catch (Exception e) {
                    Log.w(TAG, "startAsync: heartbeat Exception. " + e.getMessage());
                    isClientConnected.set(false);
                    break;
                }
            } //heartbeat while
        } // main while

        Log.d(TAG, "run() EXIT");
    }

    private boolean waitingForClient() {
        Log.d(TAG, "waitingForClient()");
        try {
            mClientSocket = mServerSocket.accept(); // BLOCK THREAD
            Log.i(TAG, "Client connected: " + mClientSocket.getInetAddress());
        } catch (Exception e) {
            Log.e(TAG, "waitingForClient(): mServer.accept()", e);
            stop(mContext.get().getString(R.string.waiting_connection_error_message));
            if (mCallback != null) mCallback.onServerStopped(e.getMessage());
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
            if (mCallback != null) mCallback.onClientDisconnected(e.getMessage());
            return false;
        }
        return true;
    }

    public synchronized void sendResolution() {
        Log.d(TAG, "sendResolution(): " + AppHelper.getScreenWidth() + "x" + AppHelper.getScreenHeight());

        if (!isClientConnected.get() || mOutputStream == null) {
            return;
        }
        if (AppHelper.getScreenWidth() > 0 && AppHelper.getScreenHeight() > 0) {
            try {
                mOutputStream.write(2);
                mOutputStream.write(ByteBuffer.allocate(4).putInt(AppHelper.getScreenWidth()).array());
                mOutputStream.write(ByteBuffer.allocate(4).putInt(AppHelper.getScreenHeight()).array());
                mOutputStream.flush();
            } catch (Exception e) {
                Log.e(TAG, "sendResolution: Exception", e);
                isClientConnected.set(false);
                if (mCallback != null) mCallback.onClientDisconnected(e.getMessage());
            }
        } else {
            Log.w(TAG, "Unable to obtain the current resolution for submission.");
        }
    }

    public synchronized void sendData(byte[] data, boolean isConfig) {
        if (!isClientConnected.get() || mOutputStream == null || data == null) {
            return;
        }
        try {
            int packetType = isConfig ? 0 : 1;
            if (isConfig) Log.i(TAG, "sendData. type: " + packetType + ", length: " + data.length + ", Thread: " + Thread.currentThread().getName());
            mOutputStream.write(packetType);
            mOutputStream.write(ByteBuffer.allocate(4).putInt(data.length).array());
            mOutputStream.write(data);
            mOutputStream.flush();
        } catch (Exception e) {
            Log.e(TAG, " TcpServer.sendData: Exception", e);
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
        isRunning.set(false);
        closeClientResources(reason);
        closeServer(reason);
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

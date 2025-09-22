package osp.moon.clonescreen.helpers;

import static android.content.Context.WIFI_SERVICE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.services.MyCaptureService;

public class AppHelper {

    private static final String TAG = AppHelper.class.getName();
    private static int _screenWidth = -1;
    private static int _screenHeight = -1;
    private static int _screenDpi = -1;
    private static byte[] _sps = null;
    private static byte[] _pps = null;
    public static void setScreenWidth(int width) {
        _screenWidth = width;
    }
    public static void setScreenHeight(int height) {
        _screenHeight = height;
    }
    public static void setScreenDpi(int dpi) {
        _screenDpi = dpi;
    }
    public static void setSps(byte[] sps) {
        _sps = sps;
    }
    public static void setPps(byte[] pps) {
        _pps = pps;
    }
    public static int getScreenWidth() {
        return _screenWidth;
    }
    public static int getScreenHeight() {
        return _screenHeight;
    }
    public static int getScreenDpi() {
        return _screenDpi;
    }
    public static byte[] getSps() {
        return _sps;
    }
    public static byte[] getPps() {
        return _pps;
    }
    private static int _port = 5000;
    public static void setServerPort(int port) {
        _port = port;
    }
    public static int getPort() {
        return _port;
    }

    public static boolean checkDrawOverlayPermission(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    public static void startCaptureService(final Context context, int resultCode, Intent resultData) {
        Log.d(TAG, "startCaptureService ACTION_START.");
        Intent serviceIntent = new Intent(context, MyCaptureService.class);
        serviceIntent.setAction(MyCaptureService.ACTION_START);
        serviceIntent.putExtra(MyCaptureService.RESULT_CODE, resultCode);
        serviceIntent.putExtra(MyCaptureService.RESULT_DATA, resultData);
        ContextCompat.startForegroundService(context, serviceIntent);
    }

    public static void stopCaptureService(final Context context) {
        Log.d(TAG, "startCaptureService ACTION_STOP.");
        Intent serviceIntent = new Intent(context, MyCaptureService.class);
        serviceIntent.setAction(MyCaptureService.ACTION_STOP);
        ContextCompat.startForegroundService(context, serviceIntent);
    }

    /**
     * Получает IP-адрес устройства.
     * Корректно работает как в режиме Wi-Fi клиента, так и в режиме Точки Доступа (Hotspot).
     */
    public static String getIpAddress(final Context context) {
        Log.d(TAG, "getIpAddress() called.");
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                if (intf.getName().contains("wlan") || intf.getName().contains("ap")) {
                    for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                        java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                            Log.d(TAG, "Found (Hotspot) IP: " + inetAddress.getHostAddress());
                            return inetAddress.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getIpAddress() Exception1", e);
        }

        Log.d(TAG, "The access point's IP address was not found. We're looking for the IP address on a regular Wi-Fi network...");
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            if (!wifiManager.isWifiEnabled()) {
                return context.getString(R.string.wifi_off_message);
            }

            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ip = wifiInfo.getIpAddress();
            if (ip != 0) {
                String ipAddress = String.format(Locale.getDefault(), "%d.%d.%d.%d",
                        (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                Log.d(TAG, "IP address found on Wi-Fi network:" + ipAddress);
                return ipAddress;
            }
        }

        return context.getString(R.string.ip_not_found_message);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static Notification getNotification(Context context, String serviceName) {
        String NOTIFICATION_CHANNEL_ID = "osp.moon.clonescreen";
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, serviceName, NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(chan);
        }

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID);
        return notificationBuilder.setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(serviceName)
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}

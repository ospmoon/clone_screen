package osp.moon.clonescreen.helpers;

import static android.content.Context.WIFI_SERVICE;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.util.Locale;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.services.ScreenCaptureService;

public class AppHelper {

    private static final String TAG = AppHelper.class.getName();

    public static void prepareCaptureService(final Context context) {
        Log.d(TAG, "Сервис запущен с ACTION_PREPARE.");
        Intent serviceIntent = new Intent(context, ScreenCaptureService.class);
        serviceIntent.setAction(ScreenCaptureService.ACTION_PREPARE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    public static void startCaptureService(final Context context) {
        Log.d(TAG, "Сервис запущен с ACTION_START.");
        Intent serviceIntent = new Intent(context, ScreenCaptureService.class);
        serviceIntent.setAction(ScreenCaptureService.ACTION_START);
        context.startService(serviceIntent);
    }

    public static void stopCaptureService(final Context context) {
        Log.d(TAG, "Сервис остановлен с ACTION_STOP.");
        Intent serviceIntent = new Intent(context, ScreenCaptureService.class);
        serviceIntent.setAction(ScreenCaptureService.ACTION_STOP);
        context.startService(serviceIntent);
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
                        // Ищем IPv4 адрес, который не является loopback
                        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                            Log.d(TAG, "Найден IP адрес точки доступа: " + inetAddress.getHostAddress());
                            return inetAddress.getHostAddress();
                        }
                    }
                }
            }
        } catch (java.net.SocketException ex) {
            Log.e(TAG, "Ошибка при получении IP адреса точки доступа", ex);
        }

        // Если в режиме точки доступа найти не удалось, пробуем старый способ (режим клиента Wi-Fi)
        Log.d(TAG, "IP точки доступа не найден, ищем IP в обычной Wi-Fi сети...");
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
                Log.d(TAG, "Найден IP адрес в Wi-Fi сети: " + ipAddress);
                return ipAddress;
            }
        }

        return context.getString(R.string.ip_not_found_message);
    }
}

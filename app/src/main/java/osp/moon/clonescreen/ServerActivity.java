package osp.moon.clonescreen;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

import osp.moon.clonescreen.services.ScreenCaptureService;

public class ServerActivity extends AppCompatActivity {

    private static final String TAG = ServerActivity.class.getName();

    private MediaProjectionManager mediaProjectionManager;
    private ActivityResultLauncher<Intent> mediaProjectionLauncher;
    private TextView ipAddressTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server);

        // 1. Находим наш TextView для IP-адреса
        //    Убедитесь, что ID "ip_address_text" совпадает с вашим XML-файлом.
        ipAddressTextView = findViewById(R.id.ip_address_text);

        // 2. Получаем и отображаем IP-адрес
        ipAddressTextView.setText(getIpAddress());

        // --- Остальной код для MediaProjection ---
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        mediaProjectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Log.d(TAG, "Разрешение на захват экрана получено.");

                        MediaProjection mediaProjection = mediaProjectionManager.getMediaProjection(
                                result.getResultCode(), result.getData()
                        );
                        if (mediaProjection == null) {
                            Log.e(TAG, "MediaProjection is null, stopping service.");
                            stopCaptureService();
                            return;
                        }

                        ScreenCaptureService.mediaProjection = mediaProjection;

                        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                        serviceIntent.setAction(ScreenCaptureService.ACTION_START);
                        startService(serviceIntent);

                        Log.d(TAG, "Команда ACTION_START отправлена в сервис.");
                        Toast.makeText(this, "Трансляция началась!", Toast.LENGTH_SHORT).show();

                        // finish(); // НЕ ВЫЗЫВАЕМ, чтобы активность оставалась на экране

                    } else {
                        Log.w(TAG, "Пользователь отклонил запрос. Останавливаем сервис.");
                        stopCaptureService();
                    }
                });

        // Убедитесь, что ID кнопки "start_button" совпадает с вашим XML-файлом
        Button startButton = findViewById(R.id.start_button);
        startButton.setOnClickListener(v -> {
            Log.d(TAG, "Кнопка 'Начать трансляцию' нажата.");

            Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
            serviceIntent.setAction(ScreenCaptureService.ACTION_PREPARE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d(TAG, "Сервис запущен с ACTION_PREPARE.");

            Log.d(TAG, "Запрашиваем разрешение на захват экрана...");
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent());
        });

        Button stopButton = findViewById(R.id.stop_button);
        stopButton.setOnClickListener(v -> {
            Log.d(TAG, "Кнопка 'Завершить трансляцию' нажата.");
            stopCaptureService();
        });
    }

    /**
     * Получает IP-адрес устройства.
     * Корректно работает как в режиме Wi-Fi клиента, так и в режиме Точки Доступа (Hotspot).
     */
    private String getIpAddress() {        // Сначала пытаемся найти IP в режиме Точки Доступа
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                // Ищем интерфейс точки доступа (обычно wlan0 или ap0)
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
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            // Проверяем, включен ли Wi-Fi
            if (!wifiManager.isWifiEnabled()) {
                return "Wi-Fi выключен";
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

        return "IP не найден";
    }


    private void stopCaptureService() {
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        serviceIntent.setAction(ScreenCaptureService.ACTION_STOP);
        startService(serviceIntent);
    }
}

package osp.moon.clonescreen.fragments;

import static android.app.Activity.RESULT_OK;

import android.content.Context;
import android.content.Intent;
// import android.media.projection.MediaProjection; // Больше не нужен здесь
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
// import android.graphics.PixelFormat; // Не используется
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
// import android.view.WindowManager; // Не используется

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.helpers.AppHelper;
import osp.moon.clonescreen.services.ScreenCaptureService;

public class ServerFragment extends Fragment {

    private final String TAG = ServerFragment.class.getName();
    private static final int REQUEST_CODE_DRAW_OVERLAY_PERMISSION = 1234;

    private MediaProjectionManager mMediaProjectionManager;
    private final ActivityResultLauncher<Intent> mMediaProjectionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Log.d(TAG, "Разрешение на захват экрана получено.");
                    // Передаем resultCode и resultData в сервис
                    startScreenCaptureService(requireActivity(), result.getResultCode(), result.getData());
                } else {
                    Log.w(TAG, "Пользователь отклонил запрос на захват экрана.");
                    Toast.makeText(requireActivity(), "Разрешение на захват экрана не предоставлено.", Toast.LENGTH_SHORT).show();
                    // Останавливать сервис здесь не нужно, так как он мог быть запущен для рамки,
                    // но без MediaProjection он не сможет начать трансляцию.
                    // Если была показана рамка, и захват не удался, сервис сам разберется или можно послать команду на скрытие рамки.
                    sendOverlayCommandToService(ScreenCaptureService.ACTION_HIDE_BORDER);
                    AppHelper.stopCaptureService(requireActivity()); // Если сервис был запущен для рамки и мы точно хотим его остановить
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate()");

        mMediaProjectionManager = ContextCompat.getSystemService(requireActivity(), MediaProjectionManager.class);
        if (mMediaProjectionManager == null) {
            Log.e(TAG, "MediaProjectionManager not available");
            Toast.makeText(requireActivity(), requireActivity().getString(R.string.service_not_available_message), Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView()");
        View root = inflater.inflate(R.layout.fragment_server, container, false);

        TextView ipAddressTextView = root.findViewById(R.id.ip_address_text);
        ipAddressTextView.setText(AppHelper.getIpAddress(requireContext()));

        Button startButton = root.findViewById(R.id.start_button);
        startButton.setOnClickListener(v -> {
            Log.d(TAG, "Кнопка 'Начать трансляцию' нажата.");
            if (checkDrawOverlayPermission()) {
                Log.d(TAG, "Разрешение на рисование поверх других окон есть.");
                AppHelper.prepareCaptureService(requireActivity());
                sendOverlayCommandToService(ScreenCaptureService.ACTION_SHOW_BORDER_GREEN);

                if (mMediaProjectionManager != null) {
                    Log.d(TAG, "Запрашиваем разрешение на захват экрана...");
                    mMediaProjectionLauncher.launch(mMediaProjectionManager.createScreenCaptureIntent());
                } else {
                    Log.e(TAG, "mMediaProjectionManager is null, cannot launch screen capture intent.");
                    Toast.makeText(requireActivity(), "Ошибка: MediaProjectionManager не инициализирован.", Toast.LENGTH_SHORT).show();
                    sendOverlayCommandToService(ScreenCaptureService.ACTION_HIDE_BORDER);
                    AppHelper.stopCaptureService(requireActivity()); // Останавливаем, если не можем начать
                }
            } else {
                Log.d(TAG, "Разрешения на рисование поверх других окон нет. Запрашиваем.");
                requestDrawOverlayPermission();
            }
        });

        Button stopButton = root.findViewById(R.id.stop_button);
        stopButton.setOnClickListener(v -> {
            Log.d(TAG, "Кнопка 'Завершить трансляцию' нажата.");
            AppHelper.stopCaptureService(requireActivity()); // Эта команда должна остановить все, включая рамку
            // sendOverlayCommandToService(ScreenCaptureService.ACTION_HIDE_BORDER); // Избыточно, если stopCaptureService корректно работает
        });
        return root;
    }

    private boolean checkDrawOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(requireContext());
        }
        return true;
    }

    private void requestDrawOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireActivity().getPackageName()));
            startActivityForResult(intent, REQUEST_CODE_DRAW_OVERLAY_PERMISSION); // Используем startActivityForResult для простоты, т.к. уже есть onActivityResult
            Toast.makeText(requireContext(), "Пожалуйста, предоставьте разрешение на рисование поверх других окон", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        // Делегируем результат mMediaProjectionLauncher, если это его запрос.
        // ActivityResultLauncher сам обработает это, если он был запущен.
        // Нам нужно обработать только результат от REQUEST_CODE_DRAW_OVERLAY_PERMISSION.
        // mMediaProjectionLauncher.onActivityResult(requestCode, resultCode, data); // Не нужно так делать

        super.onActivityResult(requestCode, resultCode, data); // Важно вызвать super

        if (requestCode == REQUEST_CODE_DRAW_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Задержка для того, чтобы система успела обновить состояние Settings.canDrawOverlays
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (Settings.canDrawOverlays(requireContext())) {
                        Log.d(TAG, "Разрешение на рисование поверх других окон ПОЛУЧЕНО после запроса.");
                        Toast.makeText(requireContext(), "Разрешение получено. Нажмите 'Начать трансляцию' еще раз.", Toast.LENGTH_LONG).show();
                    } else {
                        Log.w(TAG, "Разрешение на рисование поверх других окон ОТКЛОНЕНО после запроса.");
                        Toast.makeText(requireContext(), "Разрешение не предоставлено. Рамка не будет отображаться.", Toast.LENGTH_LONG).show();
                    }
                }, 500); // 500 мс задержка
            }
        }
    }

    private void sendOverlayCommandToService(String action) {
        Intent intent = new Intent(requireContext(), ScreenCaptureService.class);
        intent.setAction(action);
        ContextCompat.startForegroundService(requireContext(), intent);
    }

// В ServerFragment.java, метод startScreenCaptureService

    private void startScreenCaptureService(final Context context, int resultCode, Intent resultData) {
        Log.d(TAG, "startScreenCaptureService: Preparing to start service.");
        Log.d(TAG, "startScreenCaptureService: resultCode = " + resultCode);
        Log.d(TAG, "startScreenCaptureService: resultData is null? " + (resultData == null));
        if (resultData != null) {
            Log.d(TAG, "startScreenCaptureService: resultData extras: " + resultData.getExtras());
        }

        if (resultCode != RESULT_OK) { // RESULT_OK обычно -1
            Log.e(TAG, "startScreenCaptureService: resultCode is NOT RESULT_OK! Value: " + resultCode);
            // Можно здесь показать Toast или обработать ошибку, если это не ожидается
        }
        if (resultData == null) {
            Log.e(TAG, "startScreenCaptureService: resultData is NULL! Cannot start service properly.");
            Toast.makeText(context, "Ошибка: Некорректные данные для запуска (resultData null).", Toast.LENGTH_LONG).show();
            // Возможно, здесь стоит остановить сервис, если он был запущен для рамки
            sendOverlayCommandToService(ScreenCaptureService.ACTION_HIDE_BORDER);
            AppHelper.stopCaptureService(context);
            return;
        }

        Intent serviceIntent = new Intent(context, ScreenCaptureService.class);
        serviceIntent.setAction(ScreenCaptureService.ACTION_START);
        serviceIntent.putExtra("media_projection_result_code", resultCode);
        serviceIntent.putExtra("media_projection_result_data", resultData);

        Log.d(TAG, "Отправка команды ACTION_START с данными MediaProjection в сервис.");
        ContextCompat.startForegroundService(context, serviceIntent);

        Toast.makeText(context, context.getString(R.string.screen_cloning_has_begun_toast_message), Toast.LENGTH_SHORT).show();
    }



    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume()");
        // Можно добавить проверку разрешений здесь, если пользователь вернулся из настроек
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");
    }
}

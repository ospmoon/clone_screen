package osp.moon.clonescreen.fragments;

import static android.app.Activity.RESULT_OK;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.WindowManager;

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
                    getProjectionAndStartService(requireActivity(), result.getResultCode(), result.getData());
                } else {
                    Log.w(TAG, "Пользователь отклонил запрос. Останавливаем сервис.");
                    AppHelper.stopCaptureService(requireActivity());
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
                // Теперь можно запустить сервис и сказать ему показать рамку
                AppHelper.prepareCaptureService(requireActivity()); // Это запускает сервис в foreground
                sendOverlayCommandToService(ScreenCaptureService.ACTION_SHOW_BORDER_GREEN); // Новая команда
                // Остальная логика запуска проекции
                if (mMediaProjectionManager != null) {
                    mMediaProjectionLauncher.launch(mMediaProjectionManager.createScreenCaptureIntent());
                } else {
                    Log.e(TAG, "mMediaProjectionManager is null, cannot launch screen capture intent.");
                    Toast.makeText(requireActivity(), "Ошибка: MediaProjectionManager не инициализирован.", Toast.LENGTH_SHORT).show();
                    sendOverlayCommandToService(ScreenCaptureService.ACTION_HIDE_BORDER); // Скрываем, если ошибка
                }
            } else {
                Log.d(TAG, "Разрешения на рисование поверх других окон нет. Запрашиваем.");
                requestDrawOverlayPermission();
            }
        });

        Button stopButton = root.findViewById(R.id.stop_button);
        stopButton.setOnClickListener(v -> {
            Log.d(TAG, "Кнопка 'Завершить трансляцию' нажата.");
            AppHelper.stopCaptureService(requireActivity());
            sendOverlayCommandToService(ScreenCaptureService.ACTION_HIDE_BORDER); // Команда сервису убрать рамку
        });
        return root;
    }

    private boolean checkDrawOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(requireContext());
        }
        return true; // На версиях до M разрешение дается при установке
    }

    private void requestDrawOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireActivity().getPackageName()));
            // Вместо startActivityForResult, который устарел, лучше использовать новый ActivityResultLauncher,
            // но для простоты этого примера, если вы уже используете mMediaProjectionLauncher,
            // можно запустить так, но результат нужно будет проверять в onResume или другом колбэке.
            // Для этого конкретного разрешения обычно достаточно просто открыть настройки.
            startActivityForResult(intent, REQUEST_CODE_DRAW_OVERLAY_PERMISSION);
            Toast.makeText(requireContext(), "Пожалуйста, предоставьте разрешение на рисование поверх других окон", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_DRAW_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(requireContext())) {
                    Log.d(TAG, "Разрешение на рисование поверх других окон ПОЛУЧЕНО после запроса.");
                    // Можно автоматически нажать "старт" или просто уведомить пользователя, что теперь можно.
                    // Для простоты, пользователь должен будет нажать "старт" снова.
                    Toast.makeText(requireContext(), "Разрешение получено. Нажмите 'Начать трансляцию' еще раз.", Toast.LENGTH_LONG).show();
                } else {
                    Log.w(TAG, "Разрешение на рисование поверх других окон ОТКЛОНЕНО после запроса.");
                    Toast.makeText(requireContext(), "Разрешение не предоставлено. Рамка не будет отображаться.", Toast.LENGTH_LONG).show();
                }
            }
        }
        // Не забываем обработку для mMediaProjectionLauncher
        // Это место немного усложняется, если уже есть registerForActivityResult.
        // Возможно, для REQUEST_CODE_DRAW_OVERLAY_PERMISSION не нужно ждать результата,
        // а просто проверять разрешение при следующей попытке старта.
    }

    private void sendOverlayCommandToService(String action) {
        Intent intent = new Intent(requireContext(), ScreenCaptureService.class);
        intent.setAction(action);
        requireContext().startService(intent);
    }

    private void getProjectionAndStartService(final Context context, int code, Intent data) {
        MediaProjection mediaProjection = mMediaProjectionManager.getMediaProjection(code, data);
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null, stopping service.");
            AppHelper.stopCaptureService(context);
            return;
        }
        ScreenCaptureService.mediaProjection = mediaProjection;
        AppHelper.startCaptureService(context);

        Log.d(TAG, "Команда ACTION_START отправлена в сервис.");
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
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");
    }
}
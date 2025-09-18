package osp.moon.clonescreen.fragments;

import static android.app.Activity.RESULT_OK;
import static androidx.core.content.ContextCompat.getSystemService;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.helpers.AppHelper;
import osp.moon.clonescreen.services.ScreenCaptureService;

public class ServerFragment extends Fragment {

    private final String TAG = ServerFragment.class.getName();

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

        mMediaProjectionManager = (MediaProjectionManager) getSystemService(requireActivity(), Context.MEDIA_PROJECTION_SERVICE);
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
            AppHelper.prepareCaptureService(requireActivity());
            Log.d(TAG, "Запрашиваем разрешение на захват экрана...");
            mMediaProjectionLauncher.launch(mMediaProjectionManager.createScreenCaptureIntent());
        });

        Button stopButton = root.findViewById(R.id.stop_button);
        stopButton.setOnClickListener(v -> {
            Log.d(TAG, "Кнопка 'Завершить трансляцию' нажата.");
            AppHelper.stopCaptureService(requireActivity());
        });
        return root;
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
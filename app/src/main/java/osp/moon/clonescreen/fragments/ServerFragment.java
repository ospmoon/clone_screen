package osp.moon.clonescreen.fragments;

import static android.app.Activity.RESULT_OK;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.helpers.AppHelper;

public class ServerFragment extends Fragment {

    private final String TAG = ServerFragment.class.getName();

    private MediaProjectionManager mMediaProjectionManager;
    private final ActivityResultLauncher<Intent> mMediaProjectionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    AppHelper.startCaptureService(requireActivity(), result.getResultCode(), result.getData());
                } else {
                    Toast.makeText(requireActivity(), requireActivity().getString(R.string.permission_not_granted_toast_message), Toast.LENGTH_SHORT).show();
                    AppHelper.stopCaptureService(requireActivity());
                }
            });

    final private ActivityResultLauncher<Intent> mOverlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Log.i(TAG, "onActivityResult?: " + result);

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
            if (AppHelper.checkDrawOverlayPermission(requireActivity())) {
                if (mMediaProjectionManager != null) {
                    mMediaProjectionLauncher.launch(mMediaProjectionManager.createScreenCaptureIntent());
                } else {
                    Log.e(TAG, "mMediaProjectionManager is null, cannot launch screen capture intent.");
                    Toast.makeText(requireActivity(), requireActivity().getString(R.string.mMediaProjectionManager_not_initialized_toast_message), Toast.LENGTH_SHORT).show();
                    AppHelper.stopCaptureService(requireActivity());
                }
            } else {
                requestDrawOverlayPermission();
            }
        });

        Button stopButton = root.findViewById(R.id.stop_button);
        stopButton.setOnClickListener(v -> {
            AppHelper.stopCaptureService(requireActivity());
        });
        return root;
    }

    private void requestDrawOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(requireContext(), requireActivity().getString(R.string.please_grant_overlay_permission_toast_message), Toast.LENGTH_LONG).show();
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:"+ requireActivity().getPackageName())
            );
            mOverlayPermissionLauncher.launch(intent);
        }
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

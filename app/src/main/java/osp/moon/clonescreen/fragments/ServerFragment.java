package osp.moon.clonescreen.fragments;

import static android.app.Activity.RESULT_OK;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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


    private boolean isServiceRunning = false;
    private Button mStartButton;
    private Button mStopButton;
    private boolean mProjectionManagerNotAvailable = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate()");

        mMediaProjectionManager = ContextCompat.getSystemService(requireActivity(), MediaProjectionManager.class);
        if (mMediaProjectionManager == null) {
            Log.e(TAG, "MediaProjectionManager not available");
            mProjectionManagerNotAvailable = true;
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

        initStartAndStopButton(root);

        return root;
    }

    private void initStartAndStopButton(View root) {
        mStartButton = root.findViewById(R.id.start_button);
        if (mProjectionManagerNotAvailable) {
            mStartButton.setText(R.string.service_not_available_message);
            mStartButton.setEnabled(false);
            return;
        }
        mStartButton.setOnClickListener(view -> {
            if (mMediaProjectionManager != null) {
                mMediaProjectionLauncher.launch(mMediaProjectionManager.createScreenCaptureIntent());
            } else {
                Log.e(TAG, "mMediaProjectionManager is null, cannot launch screen capture intent.");
                Toast.makeText(requireActivity(), requireActivity().getString(R.string.mMediaProjectionManager_not_initialized_toast_message), Toast.LENGTH_SHORT).show();
                AppHelper.stopCaptureService(requireActivity());
            }
        });
        mStopButton = root.findViewById(R.id.stop_button);
        mStopButton.setOnClickListener(view -> AppHelper.stopCaptureService(requireActivity()));
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");
        try {
            requireActivity().unregisterReceiver(mStatusReceiver);
        } catch (Exception e) {}
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume()");
        ContextCompat.registerReceiver(requireActivity(), mStatusReceiver, new IntentFilter(AppHelper.INTENT_ACTION_SERVICE_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");
    }

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (action != null && action.equals(AppHelper.INTENT_ACTION_SERVICE_STATUS)) {
                isServiceRunning = intent.getBooleanExtra(AppHelper.IS_RUNNING_KEY, false);
                Log.i(TAG, "Service is running?: " + isServiceRunning);
            }
        }
    };
}

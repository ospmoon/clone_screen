package osp.moon.clonescreen.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.helpers.AppHelper;

public class WelcomeFragment extends Fragment implements ConnectToServerDialog.ConnectToServerDialogListener {

    private final String TAG = WelcomeFragment.class.getName();

    final private ActivityResultLauncher<Intent> mOverlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Log.i(TAG, "onActivityResult?: " + result);
                openServerFragment();
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate()");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView()");
        View root = inflater.inflate(R.layout.fragment_welcome, container, false);

        root.findViewById(R.id.server_button).setOnClickListener(v -> {
            if (AppHelper.checkDrawOverlayPermission(requireActivity())) {
                openServerFragment();
            } else {
                requestDrawOverlayPermission();
            }
        });
        root.findViewById(R.id.client_button).setOnClickListener(v -> openConnectDialog());
        return root;
    }

    private void openServerFragment() {
        Log.w(TAG, "openServerFragment()");
        Navigation.findNavController(requireActivity(), R.id.nav_host_fragment).navigate(R.id.serverFragment);
    }

    private void openClientFragment() {
        Log.w(TAG, "openClientFragment()");
        Navigation.findNavController(requireActivity(), R.id.nav_host_fragment).navigate(R.id.clientFragment);
    }

    private void openConnectDialog() {
        Log.w(TAG, "openConnectDialog()");
        ConnectToServerDialog fragment = ConnectToServerDialog.newInstance();
        fragment.show(getChildFragmentManager(), "ConnectToServerDialog");
    }

    private void requestDrawOverlayPermission() {
        Log.w(TAG, "requestDrawOverlayPermission()");
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

    @Override
    public void onClickConnect(String ipAddress) {
        Log.i(TAG, "onClickConnect(): " + ipAddress);
        AppHelper.saveServerIp(ipAddress);
        openClientFragment();
    }
}

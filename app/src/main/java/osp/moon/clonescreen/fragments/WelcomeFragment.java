package osp.moon.clonescreen.fragments;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import osp.moon.clonescreen.R;

public class WelcomeFragment extends Fragment {

    private final String TAG = WelcomeFragment.class.getName();

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

        root.findViewById(R.id.server_button).setOnClickListener(v -> startServer());
        root.findViewById(R.id.client_button).setOnClickListener(v -> startClient());
        return root;
    }

    private void startClient() {
        Navigation.findNavController(requireActivity(), R.id.nav_host_fragment).navigate(R.id.clientFragment);
    }

    private void startServer() {
        Navigation.findNavController(requireActivity(), R.id.nav_host_fragment).navigate(R.id.serverFragment);
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

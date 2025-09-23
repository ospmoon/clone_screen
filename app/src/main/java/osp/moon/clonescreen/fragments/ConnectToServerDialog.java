package osp.moon.clonescreen.fragments;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import osp.moon.clonescreen.R;
import osp.moon.clonescreen.helpers.AppHelper;

public class ConnectToServerDialog extends DialogFragment {
    private final String TAG = ConnectToServerDialog.class.getName();
    private EditText mMasterIpInput;
    private ConnectToServerDialogListener _callback;
    public interface ConnectToServerDialogListener {
        void onClickConnect(String ipAddress);
    }
    public static ConnectToServerDialog newInstance() {
        return new ConnectToServerDialog();
    }
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate()");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView()");
        View root = inflater.inflate(R.layout.connect_dialog, container, false);

        mMasterIpInput = root.findViewById(R.id.ip_address_input);
        mMasterIpInput.setText(AppHelper.getServerIp());

        Button connectButton = root.findViewById(R.id.connect_button);
        connectButton.setOnClickListener(v -> {
            Log.d(TAG, "onClick: connectButton.");
            String ip = mMasterIpInput.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(requireActivity(), requireActivity().getString(R.string.enter_ip_toast_message), Toast.LENGTH_SHORT).show();
                return;
            }
            if (_callback != null) _callback.onClickConnect(ip);
            dismiss();
        });

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.i(TAG, "onViewCreated()");
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            if (getParentFragment() != null) {
                _callback = (ConnectToServerDialogListener) getParentFragment();
            } else {
                _callback = (ConnectToServerDialogListener) context;
            }
        } catch (Exception e) {
            Log.e(TAG, "onAttach: Exception: ", e);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        _callback = null;
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

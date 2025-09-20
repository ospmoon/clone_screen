package osp.moon.clonescreen;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

public class App extends Application {

    private final String TAG = App.class.getName();

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "APPLICATION CREATED");
        Log.i(TAG, getDeviceInfo());

        setUncaughtExceptionHandler();
    }

    private void setUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(@NonNull Thread thread, @NonNull Throwable exception) {
                Log.e(TAG, "!!!!!!!!!!!!!Uncaught Exception", exception);
                System.exit(0);
            }
        });
    }

    private String getDeviceInfo() {
        String LINE_SEPARATOR = "\n";
        StringBuilder errorReport = new StringBuilder();
        try {
            errorReport.append("************ DEVICE INFORMATION ***********");
            errorReport.append(LINE_SEPARATOR);
            errorReport.append("Brand: ");
            errorReport.append(Build.BRAND);
            errorReport.append(LINE_SEPARATOR);
            errorReport.append("Device: ");
            errorReport.append(Build.DEVICE);
            errorReport.append(LINE_SEPARATOR);
            errorReport.append("Model: ");
            errorReport.append(Build.MODEL);
            errorReport.append(LINE_SEPARATOR);
            errorReport.append("Id: ");
            errorReport.append(Build.ID);
            errorReport.append(LINE_SEPARATOR);
            errorReport.append("Product: ");
            errorReport.append(Build.PRODUCT);
            errorReport.append(LINE_SEPARATOR);
            errorReport.append("************ FIRMWARE ************");
            errorReport.append(LINE_SEPARATOR);
            errorReport.append("Android SDK version: ");
            errorReport.append(Build.VERSION.SDK_INT);
            errorReport.append(LINE_SEPARATOR);
            errorReport.append("Android version: ");
            errorReport.append(Build.VERSION.RELEASE);
            errorReport.append(LINE_SEPARATOR);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return errorReport.toString();
    }
}

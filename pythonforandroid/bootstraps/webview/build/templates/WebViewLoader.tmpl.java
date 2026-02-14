// In your FORKED python-for-android:
// path: pythonforandroid/bootstraps/webview/build/src/main/java/org/kivy/android/WebViewLoader.tmpl.java
// (Or if the actual template used by broccoliboy/develop is just WebViewLoader.java, modify that)

package org.kivy.android;

import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.net.InetSocketAddress;

// No need for SystemClock here if not used from original
// import android.os.SystemClock;

import android.os.Handler;

// These imports are crucial for our modification
import android.content.Context;
import android.content.res.Configuration;
import android.app.UiModeManager;
import android.net.Uri; // For Uri.encode

// This import should already be there or implicitly available
// import org.kivy.android.PythonActivity;

public class WebViewLoader {
    private static final String TAG = "WebViewLoader"; // Changed TAG for clarity

    // Helper method to check if running on TV
    private static boolean isRunningOnTv(Context context) {
        if (context == null) {
            Log.w(TAG, "Context is null in isRunningOnTv, cannot determine device type. Assuming not TV.");
            return false;
        }
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            Log.i(TAG, "Device detected as TV mode.");
            return true;
        }
        Log.i(TAG, "Device not detected as TV mode.");
        return false;
    }

    public static void testConnection() {

        while (true) {
            // {{ args.port }} will be replaced by P4A with the actual port number
            if (WebViewLoader.pingHost("localhost", {{ args.port }}, 100)) {
                Log.v(TAG, "Successfully pinged localhost:{{ args.port }}");
                
                // Get PythonActivity.mActivity for context
                final PythonActivity activity = PythonActivity.mActivity;
                if (activity == null) {
                    Log.e(TAG, "PythonActivity.mActivity is null! Cannot determine device type or load URL.");
                    // Optionally, retry or handle this error state
                    try {
                        Thread.sleep(500); // Wait a bit and retry, mActivity might not be set immediately
                    } catch(InterruptedException e) { /* ignore */ }
                    continue; // Retry the while loop
                }

                // *** OUR MODIFICATION STARTS HERE ***
                String baseUrl = "http://127.0.0.1:{{ args.port }}/"; // Base URL from template
                
                String deviceTypeParamName = "isAndroidTv";
                boolean onTv = isRunningOnTv(activity.getApplicationContext());
                String deviceTypeValue = onTv ? "true" : "false";

                // Construct the final URL
                // Uri.encode is good practice for parameter values, though "true"/"false" are safe
                final String finalUrl = baseUrl + "?" + 
                                        deviceTypeParamName + "=" + Uri.encode(deviceTypeValue);
                Log.i(TAG, "Final URL with deviceType: " + finalUrl);
                // *** OUR MODIFICATION ENDS HERE ***

                Handler mainHandler = new Handler(activity.getMainLooper());
                Runnable myRunnable = new Runnable() {
                        @Override
                        public void run() {
                            // Load the MODIFIED URL
                            activity.loadUrl(finalUrl);
                            Log.v(TAG, "Loaded webserver in webview with URL: " + finalUrl);

                            // Remove loading screen (if this logic was in PythonActivity)
                            // PythonActivity.mActivity.removeLoadingScreen();
                            // It seems the original P4A webview bootstrap might not have this line here,
                            // but in PythonActivity itself after Python init.
                            // If your PythonActivity had `removeLoadingScreen` call after `nativeInit`,
                            // that's fine.
                        }
                    };
                mainHandler.post(myRunnable);
                break;

            } else {
                Log.v(TAG, "Could not ping localhost:{{ args.port }}");
                try {
                    Thread.sleep(100);
                } catch(InterruptedException e) {
                    Log.v(TAG, "InterruptedException occurred when sleeping");
                }
            }
        }
    }

    public static boolean pingHost(String host, int port, int timeout) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.close();
            return true;
        } catch (IOException e) {
            try {
                if (socket != null) socket.close(); // Ensure socket is closed on error
            } catch (IOException f) {
                // Log nested exception if needed, but primary error is 'e'
            }
            return false; 
        }
    }
}
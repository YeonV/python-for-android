// This file should be placed in your FORKED python-for-android repository at:
// python-for-android/pythonforandroid/bootstraps/webview/build/src/main/java/org/kivy/android/PythonActivity.java

package org.kivy.android;

import android.os.SystemClock;

import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

import android.view.ViewGroup;
import android.view.KeyEvent;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.webkit.WebSettings;

import android.widget.AbsoluteLayout;
import android.view.ViewGroup.LayoutParams;

import android.webkit.WebBackForwardList;
import android.webkit.WebViewClient;
import android.webkit.WebView;
import android.webkit.CookieManager;
import android.net.Uri;

// *** ADDED IMPORTS ***
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import android.os.Environment;
import android.app.DownloadManager;
import android.os.Build;
import java.util.Arrays;
import android.webkit.ValueCallback;
import android.content.ClipData;
import org.renpy.android.ResourceManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.content.ContentValues;
import java.io.OutputStream;

public class PythonActivity extends Activity {
    // This activity is modified from a mixture of the SDLActivity and
    // PythonActivity in the SDL2 bootstrap, but removing all the SDL2
    // specifics.

    private static final String TAG = "PythonActivityLedFxMod"; // Changed TAG slightly for clarity

    public static PythonActivity mActivity = null;
    public static boolean mOpenExternalLinksInBrowser = false;

    /** If shared libraries (e.g. SDL or the native application) could not be loaded. */
    public static boolean mBrokenLibraries;

    protected static ViewGroup mLayout;
    protected static WebView mWebView;

    protected static Thread mPythonThread;

    private ResourceManager resourceManager = null;
    private Bundle mMetaData = null;
    private PowerManager.WakeLock mWakeLock = null;
    private int mPresplashColor = Color.BLACK;
    private ValueCallback<Uri[]> mFileUploadCallbackInstance; // Renamed to avoid conflict with method param
    private static final int FILE_CHOOSER_RESULT_CODE_INSTANCE = 101; // Renamed for clarity
    private boolean customRemoteNavigation = false; // Default: use native navigation

    // Self-update needs "Install unknown apps" granted per-app from Android 8
    // onwards. Declaring REQUEST_INSTALL_PACKAGES in the manifest is not enough,
    // so we send the user to the settings screen and resume once they return.
    private static final int INSTALL_PERMISSION_RESULT_CODE = 102;

    // AudioPlaybackCapture needs a MediaProjection, and only an Activity can
    // ask for one. The capture itself runs in the service process, so the
    // approved result is broadcast across to PythonService rather than used
    // here. See PythonService.ACTION_SET_MEDIA_PROJECTION.
    private static final int MEDIA_PROJECTION_RESULT_CODE = 103;
    private String pendingApkUrl = null;
    // downloadAndInstallApk lives on RemoteControlInterface; keep the instance
    // we hand to the WebView so the update can be resumed from onActivityResult.
    private RemoteControlInterface mRemoteBridge = null;

    /**
     * Whether this app may install APKs. Below Android 8 the manifest
     * permission is sufficient; from Android 8 the user must also allow it
     * per-app under Settings -> "Install unknown apps".
     */
    public static boolean canInstallPackages() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        if (PythonActivity.mActivity == null) {
            return false;
        }
        return PythonActivity.mActivity.getPackageManager().canRequestPackageInstalls();
    }

    // The listener declared in the manifest. Android permits
    // MediaSessionManager.getActiveSessions() only for a component the user has
    // granted notification access to, which is how the core reads the currently
    // playing track.
    private static final String NOTIFICATION_LISTENER_CLASS =
        "org.kivy.android.PythonNotificationListenerService";

    /**
     * Whether the user has granted notification access to this app.
     *
     * Readable without any permission of its own, so the UI can ask before
     * offering the feature instead of letting the core fail silently.
     */
    public static boolean hasNotificationAccess() {
        if (PythonActivity.mActivity == null) {
            return false;
        }
        String enabled = Settings.Secure.getString(
            PythonActivity.mActivity.getContentResolver(),
            "enabled_notification_listeners");
        if (enabled == null) {
            return false;
        }
        return enabled.contains(
            PythonActivity.mActivity.getPackageName() + "/" + NOTIFICATION_LISTENER_CLASS);
    }

    /**
     * Send the user to the notification-access screen.
     *
     * This is a special access, not a runtime permission - there is no dialog
     * to request it, only a settings screen. From Android 11 the system can
     * open this app's own entry; before that only the full list exists, where
     * the user has to find LedFx themselves.
     *
     * The API 30 action and extra are written as literals so this compiles
     * against any SDK buildozer happens to pick.
     */
    /**
     * Ask the user to approve a MediaProjection, so audio played by other apps
     * can be captured.
     *
     * This is the only Android route to the output mix as a real, contiguous
     * stream - the Visualizer API refreshes about twenty times a second and
     * gives 8-bit samples, and AudioRecord only hears the microphone. The
     * consent dialog is mandatory and cannot be suppressed.
     */
    public void requestMediaProjection() {
        if (Build.VERSION.SDK_INT < 29) {
            Log.w(TAG, "AudioPlaybackCapture needs API 29+, have "
                  + Build.VERSION.SDK_INT);
            return;
        }
        try {
            android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(),
                                   MEDIA_PROJECTION_RESULT_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Could not start media projection request: "
                  + e.getMessage(), e);
        }
    }

    /**
     * Hand the approved projection to the service process.
     *
     * The result Intent carries a token that only becomes a MediaProjection
     * when paired with its result code, so both cross together.
     */
    private void deliverMediaProjection(int resultCode, Intent data) {
        try {
            Intent handoff = new Intent(PythonService.ACTION_SET_MEDIA_PROJECTION);
            handoff.setPackage(getPackageName());
            handoff.putExtra("resultCode", resultCode);
            handoff.putExtra("resultData", data);
            sendBroadcast(handoff);
            Log.i(TAG, "Media projection delivered to service, code=" + resultCode);
        } catch (Exception e) {
            Log.e(TAG, "Could not deliver media projection: " + e.getMessage(), e);
        }
    }

    public void openNotificationAccessSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent detail = new Intent(
                    "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS");
                detail.putExtra(
                    "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                    new ComponentName(this, NOTIFICATION_LISTENER_CLASS).flattenToString());
                startActivity(detail);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Per-app notification access screen unavailable: " + e.getMessage());
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            Log.e(TAG, "Could not open notification access settings: " + e.getMessage(), e);
            Toast.makeText(
                this,
                "Could not open notification access settings",
                Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Drop the WebView's HTTP cache after the app has been upgraded.
     *
     * The frontend ships inside the APK and is served from localhost, but
     * index.html carries no cache headers, so the WebView caches it together
     * with the hashed bundle it points at. That cache lives in the app's
     * webview data and survives an APK update - so after updating, the old
     * frontend keeps being served from disk across restarts. It reports the
     * previous version number and, with the in-app updater, keeps offering an
     * update that is already installed.
     *
     * Keyed on versionCode so this costs nothing on an ordinary launch and
     * only runs the first time a new build starts.
     */
    private void clearWebViewCacheOnUpgrade() {
        try {
            SharedPreferences prefs = getSharedPreferences("ledfx_webview", Context.MODE_PRIVATE);
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            int installed = info.versionCode;
            if (prefs.getInt("cachedForVersion", -1) != installed) {
                mWebView.clearCache(true);
                prefs.edit().putInt("cachedForVersion", installed).apply();
                Log.i(TAG, "App version changed to " + installed + " - cleared WebView cache");
            }
        } catch (Exception e) {
            // Never block startup over a cache clear.
            Log.w(TAG, "Could not clear WebView cache on upgrade", e);
        }
    }

    /**
     * Open the per-app "Install unknown apps" screen. Uses
     * startActivityForResult so onActivityResult can resume a pending update
     * as soon as the user comes back, rather than leaving them to retrigger it.
     */
    public void openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, INSTALL_PERMISSION_RESULT_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Could not open install-permission settings: " + e.getMessage(), e);
            try {
                // Some OEM builds do not honour the per-app screen
                startActivityForResult(
                    new Intent(Settings.ACTION_SECURITY_SETTINGS),
                    INSTALL_PERMISSION_RESULT_CODE);
            } catch (Exception inner) {
                Log.e(TAG, "Could not open security settings either: " + inner.getMessage(), inner);
                Toast.makeText(
                    PythonActivity.mActivity,
                    "Please allow installing unknown apps for LedFx in Android settings",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    public String getAppRoot() {
        String app_root =  getFilesDir().getAbsolutePath() + "/app";
        return app_root;
    }

    public String getEntryPoint(String search_dir) {
        List<String> entryPoints = new ArrayList<String>();
        entryPoints.add("main.pyc");  // python 3 compiled files
        for (String value : entryPoints) {
            File mainFile = new File(search_dir + "/" + value);
            if (mainFile.exists()) {
                return value;
            }
        }
        return "main.py";
    }

    public static void initialize() {
        mWebView = null;
        mLayout = null;
        mBrokenLibraries = false;
    }
    public class LedFxJavascriptInterface {
        Context mContext;

        LedFxJavascriptInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface // This annotation is crucial
        public void exportConfigFile(String fileName, String fileContentJson) {
            Log.d(TAG, "JavascriptInterface: exportConfigFile (MediaStore) called. Filename: " + fileName);
            // ... (null/empty checks for fileName, fileContentJson as before) ...

            OutputStream outputStream = null;
            Uri itemUri = null;

            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json"); // Or "application/octet-stream"

                // For Android Q (API 29) and above, save to Downloads collection
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1); // Mark as pending until write is complete
                    itemUri = mContext.getContentResolver().insert(MediaStore.Downloads.getContentUri("external"), values);
                } else {
                    // For older versions (pre-API 29), use direct path with legacy storage permission
                    // This part assumes WRITE_EXTERNAL_STORAGE is granted and requestLegacyExternalStorage might be needed for API 29 if this branch is hit
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs();
                    }
                    File file = new File(downloadsDir, fileName);
                    itemUri = Uri.fromFile(file); // This won't work directly with getContentResolver().openOutputStream
                                                // For pre-Q, direct FileOutputStream is better as in your original working code for app-specific dir
                    // Let's stick to the direct FileOutputStream for pre-Q if legacy flag is used,
                    // or ensure it works. For simplicity with MediaStore, focusing on Q+.

                    // If strictly using MediaStore for pre-Q for downloads, it's more complex or might not place it in public "Downloads" as easily.
                    // The direct file path approach (with WRITE_EXTERNAL_STORAGE) was simpler for pre-Q public downloads.
                    // Given minApi=26, this else block might be hit.
                    // For simplicity here, let's show the direct file write for pre-Q which needs WRITE_EXTERNAL_STORAGE
                    Log.d(TAG, "JSInterface: Pre-Q, attempting direct file write to Downloads (needs WRITE_EXTERNAL_STORAGE).");
                    File legacyDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!legacyDownloadsDir.exists()) legacyDownloadsDir.mkdirs();
                    File legacyFile = new File(legacyDownloadsDir, fileName);

                    try (FileOutputStream fos = new FileOutputStream(legacyFile);
                        OutputStreamWriter writer = new OutputStreamWriter(fos)) {
                        writer.write(fileContentJson);
                        writer.flush();
                        Log.i(TAG, "JSInterface: File (pre-Q) exported successfully to " + legacyFile.getAbsolutePath());
                        PythonActivity.mActivity.runOnUiThread(() ->
                            Toast.makeText(mContext, fileName + " saved to Downloads", Toast.LENGTH_LONG).show()
                        );
                        // Trigger media scanner for pre-Q direct writes
                        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScanIntent.setData(Uri.fromFile(legacyFile));
                        mContext.sendBroadcast(mediaScanIntent);
                        return; // Successfully saved using legacy method
                    }
                    // If direct write fails, the generic catch below will handle it.
                }

                if (itemUri == null) {
                    throw new IOException("Failed to create new MediaStore entry.");
                }

                outputStream = mContext.getContentResolver().openOutputStream(itemUri);
                if (outputStream == null) {
                    throw new IOException("Failed to get output stream.");
                }

                try (OutputStreamWriter writer = new OutputStreamWriter(outputStream)) {
                    writer.write(fileContentJson);
                    writer.flush(); // Ensure all data is written
                } // try-with-resources will close outputStream

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0); // Mark write as complete
                    mContext.getContentResolver().update(itemUri, values, null, null);
                }

                Log.i(TAG, "JSInterface: File exported successfully using MediaStore to Downloads. URI: " + itemUri.toString());
                PythonActivity.mActivity.runOnUiThread(() ->
                    Toast.makeText(mContext, fileName + " saved to Downloads", Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) { // Catch generic Exception for broader issues
                Log.e(TAG, "JSInterface: Error writing file via MediaStore or legacy path", e);
                PythonActivity.mActivity.runOnUiThread(() ->
                    Toast.makeText(mContext, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
                // If IS_PENDING was set, try to delete the pending entry on error
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && itemUri != null) {
                    try {
                        mContext.getContentResolver().delete(itemUri, null, null);
                    } catch (Exception deleteEx) {
                        Log.e(TAG, "JSInterface: Error deleting pending MediaStore entry", deleteEx);
                    }
                }
            } finally {
                // outputStream is closed by try-with-resources if initialized
            }
        }
    }

    public class RemoteControlInterface {
        @JavascriptInterface
        public void setCustomNavigation(boolean enabled) {
            Log.d(TAG, "Remote navigation mode set to: " + (enabled ? "CUSTOM" : "NATIVE"));
            customRemoteNavigation = enabled;
        }

        @JavascriptInterface
        public String getCpuAbi() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return Build.SUPPORTED_ABIS[0]; // Returns "arm64-v8a" or "armeabi-v7a"
            } else {
                return Build.CPU_ABI; // Fallback for older Android versions
            }
        }

        @JavascriptInterface
        public String getAllSupportedAbis() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return android.text.TextUtils.join(",", Build.SUPPORTED_ABIS);
            } else {
                return Build.CPU_ABI + "," + Build.CPU_ABI2;
            }
        }

        @JavascriptInterface
        public void downloadAndInstallApk(String apkUrl) {
            Log.i(TAG, "downloadAndInstallApk called with URL: " + apkUrl);
            PythonActivity.mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Check before downloading, not after: without this grant the
                    // install intent is refused and the download is wasted.
                    if (!PythonActivity.canInstallPackages()) {
                        Log.i(TAG, "Install permission missing, sending user to settings");
                        PythonActivity.mActivity.pendingApkUrl = apkUrl;
                        Toast.makeText(
                            PythonActivity.mActivity,
                            "Allow installing unknown apps, then return to LedFx",
                            Toast.LENGTH_LONG).show();
                        PythonActivity.mActivity.openInstallPermissionSettings();
                        return;
                    }
                    try {
                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
                        request.setTitle("App Update");
                        request.setDescription("Downloading new version...");
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "app-update.apk");
                        
                        final DownloadManager downloadManager = (DownloadManager) PythonActivity.mActivity.getSystemService(Context.DOWNLOAD_SERVICE);
                        final long downloadId = downloadManager.enqueue(request);
                        
                        // Monitor download completion
                        android.content.BroadcastReceiver onComplete = new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                                if (id == downloadId) {
                                    Log.i(TAG, "APK download completed, initiating installation");
                                    installApk(downloadManager, downloadId);
                                    context.unregisterReceiver(this);
                                }
                            }
                        };
                        
                        PythonActivity.mActivity.registerReceiver(onComplete, 
                            new android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
                        
                        Toast.makeText(PythonActivity.mActivity, "Downloading update...", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Error downloading APK: " + e.getMessage(), e);
                        Toast.makeText(PythonActivity.mActivity, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        /** Lets the UI show the right prompt instead of guessing. */
        @JavascriptInterface
        public boolean canInstallPackages() {
            return PythonActivity.canInstallPackages();
        }

        /** Opens the per-app "Install unknown apps" screen. */
        @JavascriptInterface
        public void requestInstallPermission() {
            PythonActivity.mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    PythonActivity.mActivity.openInstallPermissionSettings();
                }
            });
        }

        /** False until the user allows LedFx to read the media session. */
        @JavascriptInterface
        public boolean hasNotificationAccess() {
            return PythonActivity.hasNotificationAccess();
        }

        /** Opens the notification-access screen, where that grant is given. */
        @JavascriptInterface
        public void requestNotificationAccess() {
            PythonActivity.mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    PythonActivity.mActivity.openNotificationAccessSettings();
                }
            });
        }

        /** True when this device can capture other apps' audio output at all. */
        @JavascriptInterface
        public boolean supportsPlaybackCapture() {
            return Build.VERSION.SDK_INT >= 29;
        }

        /**
         * The audio HAL's buffer quantum in frames, or 0 if unknown.
         *
         * Reads that are a whole multiple of this can take the fast capture
         * path; a size straddling it spans two HAL buffers and carries the
         * extra latency for nothing. LedFx derives its block size from
         * sample_rate, so this is what that setting has to divide into.
         */
        @JavascriptInterface
        public int getAudioFramesPerBuffer() {
            return audioProperty(android.media.AudioManager
                                 .PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        }

        /** The audio HAL's native output sample rate in Hz, or 0 if unknown. */
        @JavascriptInterface
        public int getAudioSampleRate() {
            return audioProperty(android.media.AudioManager
                                 .PROPERTY_OUTPUT_SAMPLE_RATE);
        }

        private int audioProperty(String key) {
            try {
                android.media.AudioManager am = (android.media.AudioManager)
                    getSystemService(Context.AUDIO_SERVICE);
                String value = am.getProperty(key);
                return value == null ? 0 : Integer.parseInt(value);
            } catch (Exception e) {
                Log.w(TAG, "Could not read audio property " + key + ": "
                      + e.getMessage());
                return 0;
            }
        }

        /** True once a projection has been approved and not yet revoked. */
        @JavascriptInterface
        public boolean hasPlaybackCapture() {
            return PythonService.hasMediaProjection();
        }

        /**
         * Shows the system capture-consent dialog. Returns immediately - the
         * result arrives asynchronously and lands in PythonService, so poll
         * hasPlaybackCapture() afterwards.
         */
        @JavascriptInterface
        public void requestPlaybackCapture() {
            PythonActivity.mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    PythonActivity.mActivity.requestMediaProjection();
                }
            });
        }

        private void installApk(DownloadManager downloadManager, long downloadId) {
            Uri apkUri = downloadManager.getUriForDownloadedFile(downloadId);
            if (apkUri == null) {
                Log.e(TAG, "Downloaded APK URI is null");
                Toast.makeText(PythonActivity.mActivity, "Installation failed: file not found", Toast.LENGTH_LONG).show();
                return;
            }

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ requires FileProvider
                // Note: You'll need to add a FileProvider to your AndroidManifest.xml
                installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            }
            
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            try {
                PythonActivity.mActivity.startActivity(installIntent);
            } catch (Exception e) {
                Log.e(TAG, "Error installing APK: " + e.getMessage(), e);
                Toast.makeText(PythonActivity.mActivity, "Installation failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        @JavascriptInterface
        public String getAppVersion() {
            try {
                PackageManager pm = PythonActivity.mActivity.getPackageManager();
                String packageName = PythonActivity.mActivity.getPackageName();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName;
                } else {
                    return pm.getPackageInfo(packageName, 0).versionName;
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Error getting app version: " + e.getMessage());
                return "unknown";
            }
        }

        @JavascriptInterface
        public void exitApp() {
            Log.i(TAG, "exitApp called from JavaScript - terminating app");
            PythonActivity.mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Stop any running service
                    try {
                        PythonActivity.stop_service();
                        Log.d(TAG, "Service stopped");
                    } catch (Exception e) {
                        Log.w(TAG, "Error stopping service (may not be running): " + e.getMessage());
                    }
                    
                    // Finish the activity
                    PythonActivity.mActivity.finish();
                    
                    // Optional: Force process termination after a short delay
                    // to ensure everything is cleaned up
                    new android.os.Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            android.os.Process.killProcess(android.os.Process.myPid());
                            System.exit(0);
                        }
                    }, 500);
                }
            });
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "Custom PythonActivity onCreate running");
        resourceManager = new ResourceManager(this);
        super.onCreate(savedInstanceState);

        /*
         * Try to parse background color for use in layout, webview, and presplash image
         * https://developer.android.com/reference/android/graphics/Color.html
         * Parse the color string, and return the corresponding color-int.
         * If the string cannot be parsed, throws an IllegalArgumentException exception.
         * Supported formats are: #RRGGBB #AARRGGBB or one of the following names:
         * 'red', 'blue', 'green', 'black', 'white', 'gray', 'cyan', 'magenta', 'yellow',
         * 'lightgray', 'darkgray', 'grey', 'lightgrey', 'darkgrey', 'aqua', 'fuchsia',
         * 'lime', 'maroon', 'navy', 'olive', 'purple', 'silver', 'teal'.
         */
        String backgroundColor = resourceManager.getString("presplash_color");
        if (backgroundColor != null) {
          try {
            this.mPresplashColor = Color.parseColor(backgroundColor);
          } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid color string for presplash_color: " + backgroundColor);
          }
        }

        this.mActivity = this;
        PythonActivity.mActivity = this; // Set static mActivity to this instance
        this.showLoadingScreen();
        new UnpackFilesTask().execute(getAppRoot());
    }

    private class UnpackFilesTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            File app_root_file = new File(params[0]);
            Log.v(TAG, "UnpackFilesTask: Ready to unpack");
            PythonUtil.unpackAsset(PythonActivity.mActivity, "private", app_root_file, true);
            PythonUtil.unpackPyBundle(PythonActivity.mActivity, PythonActivity.mActivity.getApplicationInfo().nativeLibraryDir + "/" + "libpybundle", app_root_file, false);
            return null;
        }

        @Override
        protected void onPostExecute(String result) {
            Log.v(TAG, "UnpackFilesTask: onPostExecute. Device: " + android.os.Build.DEVICE + ", Model: " + android.os.Build.MODEL);

            PythonActivity.initialize(); // Static initialize

            String errorMsgBrokenLib = "";
            try {
                PythonActivity.this.loadLibraries(); // Call instance method
            } catch(UnsatisfiedLinkError e) {
                System.err.println(e.getMessage());
                mBrokenLibraries = true;
                errorMsgBrokenLib = e.getMessage();
            } catch(Exception e) {
                System.err.println(e.getMessage());
                mBrokenLibraries = true;
                errorMsgBrokenLib = e.getMessage();
            }

            if (mBrokenLibraries) {
                AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(PythonActivity.mActivity);
                dlgAlert.setMessage("An error occurred while trying to load the application libraries. Please try again and/or reinstall."
                      + System.getProperty("line.separator")
                      + System.getProperty("line.separator")
                      + "Error: " + errorMsgBrokenLib);
                dlgAlert.setTitle("Python Error");
                dlgAlert.setPositiveButton("Exit",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,int id) {
                            PythonActivity.mActivity.finish();
                        }
                    });
               dlgAlert.setCancelable(false);
               dlgAlert.create().show();
               return;
            }

            Log.d(TAG, "Setting up WebView...");
            mWebView = new WebView(PythonActivity.mActivity);
            mWebView.setBackgroundColor(mPresplashColor);
            clearWebViewCacheOnUpgrade();
            WebSettings webSettings = mWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setMediaPlaybackRequiresUserGesture(false); // Crucial for camera/mic
            webSettings.setAllowFileAccess(true); // For file:///android_asset/ and other file access
            webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
            webSettings.setAllowContentAccess(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                 // Allow mixed content (HTTP in HTTPS) - use with caution if loading external sites
                 // For localhost, this is usually fine.
                webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }            

            mWebView.setLayoutParams(new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
            mWebView.addJavascriptInterface(new LedFxJavascriptInterface(PythonActivity.mActivity), "LedFxAndroidBridge");
            Log.i(TAG, "LedFxAndroidBridge JavascriptInterface added to WebView.");
            mRemoteBridge = new RemoteControlInterface();
            mWebView.addJavascriptInterface(mRemoteBridge, "AndroidRemoteControl");
            Log.i(TAG, "AndroidRemoteControl JavascriptInterface added to WebView.");
            mWebView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    Uri u = Uri.parse(url);
                    Log.d(TAG, "WebViewClient: shouldOverrideUrlLoading: " + url + " | mOpenExternalLinksInBrowser: " + PythonActivity.mOpenExternalLinksInBrowser);

                    String scheme = u.getScheme();
                    String host = u.getHost();

                    if ( (scheme != null && scheme.equals("file")) ||
                         (host != null && (host.equals("127.0.0.1") || host.equals("localhost"))) ) {
                        Log.d(TAG, "WebViewClient: Letting WebView handle local/internal URL: " + url);
                        return false; 
                    }

                    if (PythonActivity.mOpenExternalLinksInBrowser && (scheme != null && (scheme.equals("http") || scheme.equals("https"))) ) {
                        Log.d(TAG, "WebViewClient: Opening external link in browser: " + url);
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, u);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); 
                            PythonActivity.mActivity.startActivity(intent);
                            return true; 
                        } catch (Exception e) {
                            Log.e(TAG, "WebViewClient: Could not open external link: " + url, e);
                            Toast.makeText(PythonActivity.mActivity, "Could not open link", Toast.LENGTH_SHORT).show();
                            return true; 
                        }
                    }
                    
                    try {
                        Log.d(TAG, "WebViewClient: Attempting to start activity for general URL: " + url);
                        Intent intent = new Intent(Intent.ACTION_VIEW, u);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (intent.resolveActivity(PythonActivity.mActivity.getPackageManager()) != null) {
                            PythonActivity.mActivity.startActivity(intent);
                            return true; 
                        } else {
                            Log.w(TAG, "WebViewClient: No app found to handle URL: " + url);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "WebViewClient: Error trying to handle URL with Intent: " + url, e);
                    }

                    Log.d(TAG, "WebViewClient: Fallback - Letting WebView attempt to handle URL: " + url);
                    return false;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    Log.d(TAG, "WebViewClient: onPageFinished: " + url);
                    CookieManager.getInstance().flush();
                }
            });

            mWebView.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                    Log.d(TAG, "DownloadListener: onDownloadStart. URL: " + url + " Mimetype: " + mimetype);
                    try {
                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                        request.setMimeType(mimetype);
                        String cookies = CookieManager.getInstance().getCookie(url);
                        request.addRequestHeader("cookie", cookies);
                        request.addRequestHeader("User-Agent", userAgent);
                        request.setDescription("Downloading file...");
                        
                        String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
                        request.setTitle(fileName);
                        
                        request.allowScanningByMediaScanner();
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                        
                        DownloadManager dm = (DownloadManager) PythonActivity.mActivity.getSystemService(Context.DOWNLOAD_SERVICE);
                        if (dm != null) {
                            dm.enqueue(request);
                            Toast.makeText(PythonActivity.mActivity.getApplicationContext(), "Downloading " + fileName, Toast.LENGTH_LONG).show();
                        } else {
                            Log.e(TAG, "DownloadListener: DownloadManager is null");
                            Toast.makeText(PythonActivity.mActivity.getApplicationContext(), "Download failed: DM unavailable.", Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "DownloadListener: Error starting download: " + e.getMessage(), e);
                        Toast.makeText(PythonActivity.mActivity.getApplicationContext(), "Download error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            PythonActivity.mActivity.startActivity(i);
                        } catch (Exception ex) {
                            Log.e(TAG, "DownloadListener: Fallback download attempt failed: " + ex.getMessage(), ex);
                        }
                    }
                }
            });

            mWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(final PermissionRequest request) {
                    final String[] requestedResources = request.getResources();
                    Log.d(TAG, "WebChromeClient: onPermissionRequest for origin: " + request.getOrigin().toString());
                    Log.d(TAG, "WebChromeClient: Requesting WebView resources: " + Arrays.toString(requestedResources));

                    PythonActivity.mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ArrayList<String> permissionsToGrantInWebView = new ArrayList<>();
                            boolean allAppPermissionsSufficient = true; // Assume true initially

                            for (String resource : requestedResources) {
                                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                                    if (PythonActivity.mActivity.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                        permissionsToGrantInWebView.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
                                    } else {
                                        Log.w(TAG, "WebChromeClient: App lacks CAMERA permission for WebView VIDEO_CAPTURE request.");
                                        allAppPermissionsSufficient = false;
                                    }
                                } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                                    if (PythonActivity.mActivity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        permissionsToGrantInWebView.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
                                    } else {
                                        Log.w(TAG, "WebChromeClient: App lacks RECORD_AUDIO permission for WebView AUDIO_CAPTURE request.");
                                        allAppPermissionsSufficient = false;
                                    }
                                } else {
                                    Log.d(TAG, "WebChromeClient: Unhandled WebView resource request: " + resource);
                                    // For unhandled resources, we might deny them or require specific app permissions
                                    allAppPermissionsSufficient = false; 
                                }
                            }

                            if (!permissionsToGrantInWebView.isEmpty() && allAppPermissionsSufficient) {
                                Log.i(TAG, "WebChromeClient: Granting WebView permissions for: " + permissionsToGrantInWebView.toString());
                                request.grant(permissionsToGrantInWebView.toArray(new String[0]));
                            } else {
                                Log.w(TAG, "WebChromeClient: Denying WebView request. Requested: " + Arrays.toString(requestedResources) +
                                           ", AppPermissionsSufficient: " + allAppPermissionsSufficient +
                                           ", ToGrantList: " + permissionsToGrantInWebView.toString());
                                request.deny();
                            }
                        }
                    });
                }
                // *** ADD onShowFileChooser for <input type="file"> ***
                private ValueCallback<Uri[]> mUploadMessageArray;
                private static final int FILECHOOSER_RESULTCODE = 1;

                @Override
                public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                    Log.d(TAG, "WebChromeClient: onShowFileChooser called");
                    // Use the class member variable here:
                    if (PythonActivity.this.mFileUploadCallbackInstance != null) {
                        PythonActivity.this.mFileUploadCallbackInstance.onReceiveValue(null);
                    }
                    PythonActivity.this.mFileUploadCallbackInstance = filePathCallback; // Store callback in Activity field

                    Intent intent;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        intent = fileChooserParams.createIntent();
                    } else {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*"); // Or "application/json"
                    }
                    try {
                        // Use the class member variable here:
                        PythonActivity.this.startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE_INSTANCE);
                    } catch (android.content.ActivityNotFoundException e) {
                        Log.e(TAG, "Cannot open file chooser", e);
                        Toast.makeText(getApplicationContext(), "Cannot open file chooser", Toast.LENGTH_LONG).show();
                        // Use the class member variable here:
                        if (PythonActivity.this.mFileUploadCallbackInstance != null) {
                            PythonActivity.this.mFileUploadCallbackInstance.onReceiveValue(null);
                            PythonActivity.this.mFileUploadCallbackInstance = null;
                        }
                        return false;
                    }
                    return true;
                }

                // You can override other WebChromeClient methods like onConsoleMessage for debugging JS
                // @Override
                // public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                //     Log.d("WebViewConsole", consoleMessage.message() + " -- From line "
                //             + consoleMessage.lineNumber() + " of "
                //             + consoleMessage.sourceId());
                //     return super.onConsoleMessage(consoleMessage);
                // }
            });


            mLayout = new AbsoluteLayout(PythonActivity.mActivity);
            mLayout.setBackgroundColor(mPresplashColor);
            mLayout.addView(mWebView);
            setContentView(mLayout);

            mWebView.loadUrl("file:///android_asset/_load.html"); // Initial P4A loading page

            String mFilesDirectory = PythonActivity.mActivity.getFilesDir().getAbsolutePath();
            String entry_point = PythonActivity.this.getEntryPoint(PythonActivity.this.getAppRoot()); // Use instance methods

            Log.v(TAG, "Setting env vars for start.c and Python to use");
            PythonActivity.nativeSetenv("ANDROID_ENTRYPOINT", entry_point);
            PythonActivity.nativeSetenv("ANDROID_ARGUMENT", PythonActivity.this.getAppRoot());
            PythonActivity.nativeSetenv("ANDROID_APP_PATH", PythonActivity.this.getAppRoot());
            PythonActivity.nativeSetenv("ANDROID_PRIVATE", mFilesDirectory);
            PythonActivity.nativeSetenv("ANDROID_UNPACK", PythonActivity.this.getAppRoot());
            PythonActivity.nativeSetenv("PYTHONHOME", PythonActivity.this.getAppRoot());
            PythonActivity.nativeSetenv("PYTHONPATH", PythonActivity.this.getAppRoot() + ":" + PythonActivity.this.getAppRoot() + "/lib");
            PythonActivity.nativeSetenv("PYTHONOPTIMIZE", "2");

            try {
                Log.v(TAG, "Access to our meta-data...");
                // Use instance mActivity for getPackageManager()
                mMetaData = PythonActivity.mActivity.getPackageManager().getApplicationInfo(
                        PythonActivity.mActivity.getPackageName(), PackageManager.GET_META_DATA).metaData;

                PowerManager pm = (PowerManager) PythonActivity.mActivity.getSystemService(Context.POWER_SERVICE);
                if (mMetaData != null && mMetaData.containsKey("wakelock") && mMetaData.getInt("wakelock") == 1 ) {
                    // Use instance mWakeLock
                    PythonActivity.this.mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, TAG + ":ScreenOn"); // Use specific tag
                    PythonActivity.this.mWakeLock.acquire();
                    Log.d(TAG, "Wakelock acquired.");
                }
            } catch (PackageManager.NameNotFoundException e) {
                 Log.e(TAG, "PackageManager.NameNotFoundException for meta-data", e);
            } catch (NullPointerException e) {
                 Log.e(TAG, "NullPointerException for meta-data (mMetaData might be null)", e);
            }

            Log.d(TAG, "Starting Python thread...");
            final Thread pythonThread = new Thread(new PythonMain(), "PythonThread");
            PythonActivity.mPythonThread = pythonThread;
            pythonThread.start();

            Log.d(TAG, "Starting WebViewLoader thread...");
            final Thread wvThread = new Thread(new WebViewLoaderMain(), "WvThread");
            wvThread.start();
            Log.d(TAG, "onPostExecute finished.");
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy called");
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null; // Good practice to nullify
            Log.d(TAG, "Wakelock released in onDestroy");
        }
        super.onDestroy();
        // Commenting out killProcess as it's generally not recommended.
        // Let Android manage process lifecycle if possible.
        // android.os.Process.killProcess(android.os.Process.myPid());
    }

    public void loadLibraries() {
        // Use instance method getAppRoot()
        String app_root = getAppRoot();
        File app_root_file = new File(app_root);
        PythonUtil.loadLibraries(app_root_file,
            new File(getApplicationInfo().nativeLibraryDir));
    }

    public static void loadUrl(String url) {
        if (PythonActivity.mActivity == null || PythonActivity.mWebView == null) {
            Log.e(TAG, "loadUrl called but mActivity or mWebView is null. URL: " + url);
            return;
        }
        class LoadUrl implements Runnable {
            private String mUrl;
            public LoadUrl(String url) { this.mUrl = url; }
            public void run() {
                if (PythonActivity.mWebView != null) {
                    PythonActivity.mWebView.loadUrl(this.mUrl);
                } else {
                    Log.e(TAG, "mWebView became null inside LoadUrl Runnable. URL: " + this.mUrl);
                }
            }
        }
        Log.i(TAG, "PythonActivity.loadUrl requesting to load: " + url);
        PythonActivity.mActivity.runOnUiThread(new LoadUrl(url));
    }

    public static void enableZoom() {
        if (PythonActivity.mActivity == null || PythonActivity.mWebView == null) {
            Log.e(TAG, "enableZoom called but mActivity or mWebView is null.");
            return;
        }
        PythonActivity.mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (PythonActivity.mWebView != null) {
                    PythonActivity.mWebView.getSettings().setBuiltInZoomControls(true);
                    PythonActivity.mWebView.getSettings().setDisplayZoomControls(false);
                }
            }
        });
    }

    public static ViewGroup getLayout() {
        return PythonActivity.mLayout;
    }

    long lastBackClick = 0;
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Handle BACK button with existing logic
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (PythonActivity.mWebView != null && PythonActivity.mWebView.canGoBack()) {
                WebBackForwardList webViewBackForwardList = PythonActivity.mWebView.copyBackForwardList();
                if (webViewBackForwardList.getCurrentIndex() > 0) { // Allow going back from first app page to _load.html for this test
                                                                    // but ideally > 1 if _load.html is not user-visible
                    Log.d(TAG, "WebView going back.");
                    PythonActivity.mWebView.goBack();
                    return true;
                }
            }
            if (SystemClock.elapsedRealtime() - lastBackClick > 2000){
                lastBackClick = SystemClock.elapsedRealtime();
                Toast.makeText(this, "Tap again to close the app", Toast.LENGTH_LONG).show();
                return true; 
            }
        }
        
        // Handle D-pad and remote control buttons
        if (isDpadOrRemoteKey(keyCode) && PythonActivity.mWebView != null) {
            // Always inject the event so React can see it
            String eventData = String.format(
                "window.dispatchEvent(new CustomEvent('androidremote', {" +
                "detail: {key: '%s', code: '%s', keyCode: %d}" +
                "}));",
                getKeyName(keyCode),
                android.view.KeyEvent.keyCodeToString(keyCode),
                keyCode
            );
            PythonActivity.mWebView.evaluateJavascript(eventData, null);
            Log.d(TAG, "Injected remote event: " + getKeyName(keyCode) + " (custom=" + customRemoteNavigation + ")");
            
            // Only consume if React app wants custom navigation
            return customRemoteNavigation;
        }
        
        return super.onKeyDown(keyCode, event);
    }
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isDpadOrRemoteKey(keyCode) && PythonActivity.mWebView != null) {
            String eventData = String.format(
                "window.dispatchEvent(new CustomEvent('androidremoteup', {" +
                "detail: {key: '%s', code: '%s', keyCode: %d}" +
                "}));",
                getKeyName(keyCode),
                android.view.KeyEvent.keyCodeToString(keyCode),
                keyCode
            );
            PythonActivity.mWebView.evaluateJavascript(eventData, null);
            return customRemoteNavigation;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean isDpadOrRemoteKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP ||
               keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
               keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
               keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
               keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
               keyCode == KeyEvent.KEYCODE_MENU ||
               keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
               keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
               keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE;
    }

    private String getKeyName(int keyCode) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return "ArrowUp";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "ArrowDown";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "ArrowLeft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "ArrowRight";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "Enter";
            case KeyEvent.KEYCODE_MENU: return "Menu";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "MediaPlayPause";
            case KeyEvent.KEYCODE_MEDIA_PLAY: return "MediaPlay";
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return "MediaPause";
            default: return android.view.KeyEvent.keyCodeToString(keyCode);
        }
    }

    public static ImageView mImageView = null;
    public void removeLoadingScreen() {
      if (PythonActivity.mActivity == null) return;
      PythonActivity.mActivity.runOnUiThread(new Runnable() {
        public void run() {
          if (PythonActivity.mImageView != null &&
                  PythonActivity.mImageView.getParent() != null) {
            ((ViewGroup)PythonActivity.mImageView.getParent()).removeView(
            PythonActivity.mImageView);
            PythonActivity.mImageView = null;
          }
        }
      });
    }

    protected void showLoadingScreen() {
      if (mImageView == null) {
        int presplashId = this.resourceManager.getIdentifier("presplash", "drawable");
        InputStream is = this.getResources().openRawResource(presplashId);
        Bitmap bitmap = null;
        try {
          bitmap = BitmapFactory.decodeStream(is);
        } finally {
          try {
            if (is != null) is.close();
          } catch (IOException e) {
              Log.e(TAG, "IOException closing presplash InputStream", e);
          };
        }

        if (bitmap == null) {
            Log.e(TAG, "Failed to decode presplash bitmap.");
            // Consider not setting content view if bitmap is null or using a fallback
            return; 
        }

        mImageView = new ImageView(this);
        mImageView.setImageBitmap(bitmap);

        String backgroundColor = resourceManager.getString("presplash_color");
        if (backgroundColor != null) {
          try {
            mImageView.setBackgroundColor(Color.parseColor(backgroundColor));
          } catch (IllegalArgumentException e) {
              Log.w(TAG, "Invalid presplash_color: " + backgroundColor, e);
          }
        }
        mImageView.setBackgroundColor(mPresplashColor);
        mImageView.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.FILL_PARENT,
        ViewGroup.LayoutParams.FILL_PARENT));
        mImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
      }

      if (mLayout == null) {
        setContentView(mImageView);
      } else if (PythonActivity.mImageView != null && PythonActivity.mImageView.getParent() == null){
        mLayout.addView(mImageView);
      }
    }

    public interface NewIntentListener { void onNewIntent(Intent intent); }
    private List<NewIntentListener> newIntentListeners = null;
    public void registerNewIntentListener(NewIntentListener listener) {
        if ( this.newIntentListeners == null )
            this.newIntentListeners = Collections.synchronizedList(new ArrayList<NewIntentListener>());
        this.newIntentListeners.add(listener);
    }
    public void unregisterNewIntentListener(NewIntentListener listener) {
        if ( this.newIntentListeners == null ) return;
        this.newIntentListeners.remove(listener);
    }
    @Override
    protected void onNewIntent(Intent intent) {
        if ( this.newIntentListeners == null ) return;
        this.onResume();
        synchronized ( this.newIntentListeners ) {
            Iterator<NewIntentListener> iterator = this.newIntentListeners.iterator();
            while ( iterator.hasNext() ) {
                (iterator.next()).onNewIntent(intent);
            }
        }
    }

    public interface ActivityResultListener { void onActivityResult(int requestCode, int resultCode, Intent data); }
    private List<ActivityResultListener> activityResultListeners = null;
    public void registerActivityResultListener(ActivityResultListener listener) {
        if ( this.activityResultListeners == null )
            this.activityResultListeners = Collections.synchronizedList(new ArrayList<ActivityResultListener>());
        this.activityResultListeners.add(listener);
    }
    public void unregisterActivityResultListener(ActivityResultListener listener) {
        if ( this.activityResultListeners == null ) return;
        this.activityResultListeners.remove(listener);
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        Log.d(TAG, "onActivityResult requestCode: " + requestCode + ", resultCode: " + resultCode);

        // Media projection consent. RESULT_OK plus a non-null Intent is the
        // only combination that yields a usable projection; anything else is a
        // decline and is left for the UI to re-offer.
        if (requestCode == MEDIA_PROJECTION_RESULT_CODE) {
            if (resultCode == RESULT_OK && intent != null) {
                deliverMediaProjection(resultCode, intent);
            } else {
                Log.i(TAG, "Media projection declined by user");
            }
            return;
        }

        // Returning from the "Install unknown apps" screen. The settings screen
        // always reports RESULT_CANCELED, so re-check the permission itself
        // rather than trusting resultCode.
        if (requestCode == INSTALL_PERMISSION_RESULT_CODE) {
            String apkUrl = pendingApkUrl;
            pendingApkUrl = null;
            if (canInstallPackages()) {
                if (apkUrl != null && mRemoteBridge != null) {
                    Log.i(TAG, "Install permission granted, resuming update");
                    Toast.makeText(this, "Permission granted - resuming update", Toast.LENGTH_SHORT).show();
                    mRemoteBridge.downloadAndInstallApk(apkUrl);
                }
            } else {
                Log.w(TAG, "Install permission still not granted, update cancelled");
                Toast.makeText(
                    this,
                    "Update cancelled - installing unknown apps is not allowed",
                    Toast.LENGTH_LONG).show();
            }
            return;
        }

        // Use the class member variable here:
        if (requestCode == FILE_CHOOSER_RESULT_CODE_INSTANCE) {
            // Use the class member variable here:
            if (mFileUploadCallbackInstance == null) {
                Log.w(TAG, "mFileUploadCallbackInstance is null in onActivityResult");
                super.onActivityResult(requestCode, resultCode, intent);
                return;
            }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                if (intent != null) {
                    String dataString = intent.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{ Uri.parse(dataString) };
                    } else { 
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                            android.content.ClipData clipData = intent.getClipData(); // Explicitly qualify ClipData
                            if (clipData != null) {
                                results = new Uri[clipData.getItemCount()];
                                for (int i = 0; i < clipData.getItemCount(); i++) {
                                    results[i] = clipData.getItemAt(i).getUri();
                                }
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "Received file chooser result: " + (results != null ? Arrays.toString(results) : "null results"));
            // Use the class member variable here:
            mFileUploadCallbackInstance.onReceiveValue(results);
            mFileUploadCallbackInstance = null; // Reset for next use
        } else {
            Log.d(TAG, "Passing onActivityResult to super for requestCode: " + requestCode);
            super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    public static void start_service(String serviceTitle, String serviceDescription, String pythonServiceArgument) {
        _do_start_service(serviceTitle, serviceDescription, pythonServiceArgument, true);
    }
    public static void start_service_not_as_foreground(String serviceTitle, String serviceDescription, String pythonServiceArgument) {
        _do_start_service(serviceTitle, serviceDescription, pythonServiceArgument, false);
    }
    public static void _do_start_service(String serviceTitle, String serviceDescription, String pythonServiceArgument, boolean showForegroundNotification) {
        if (PythonActivity.mActivity == null) {
            Log.e(TAG, "_do_start_service called but mActivity is null.");
            return;
        }
        Intent serviceIntent = new Intent(PythonActivity.mActivity, PythonService.class);
        String argument = PythonActivity.mActivity.getFilesDir().getAbsolutePath();
        String app_root_dir = PythonActivity.mActivity.getAppRoot(); // Assuming getAppRoot can be called on static mActivity context if needed, or use instance this.getAppRoot()
        String entry_point = PythonActivity.mActivity.getEntryPoint(app_root_dir + "/service"); // Same as above for getEntryPoint
        
        serviceIntent.putExtra("androidPrivate", argument);
        serviceIntent.putExtra("androidArgument", app_root_dir);
        serviceIntent.putExtra("serviceEntrypoint", "service/" + entry_point);
        serviceIntent.putExtra("pythonName", "python");
        serviceIntent.putExtra("pythonHome", app_root_dir);
        serviceIntent.putExtra("pythonPath", app_root_dir + ":" + app_root_dir + "/lib");
        serviceIntent.putExtra("serviceStartAsForeground", (showForegroundNotification ? "true" : "false"));
        serviceIntent.putExtra("serviceTitle", serviceTitle);
        serviceIntent.putExtra("serviceDescription", serviceDescription);
        serviceIntent.putExtra("pythonServiceArgument", pythonServiceArgument);
        PythonActivity.mActivity.startService(serviceIntent);
    }

    public static void stop_service() {
        if (PythonActivity.mActivity == null) {
            Log.e(TAG, "stop_service called but mActivity is null.");
            return;
        }
        // p4a generates service classes as <app_package>.Service<Name> (e.g. ServiceLedfx).
        // These extend PythonService, but Android's stopService() matches by EXACT component
        // class name. Using PythonService.class here targets org.kivy.android.PythonService
        // which does NOT match the running service → the call silently does nothing.
        // We must build the Intent with the correct generated class name.
        String packageName = PythonActivity.mActivity.getPackageName();
        String serviceClassName = packageName + ".ServiceLedfx";
        Intent serviceIntent = new Intent();
        serviceIntent.setClassName(packageName, serviceClassName);
        boolean result = PythonActivity.mActivity.stopService(serviceIntent);
        Log.d(TAG, "stop_service: stopService returned " + result + " for " + serviceClassName);
    }

    public static native void nativeSetenv(String name, String value);
    public static native int nativeInit(Object arguments);

    public interface PermissionsCallback { void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults); }
    private PermissionsCallback permissionCallback;
    private boolean havePermissionsCallback = false;
    public void addPermissionsCallback(PermissionsCallback callback) {
        permissionCallback = callback;
        havePermissionsCallback = true;
        Log.v(TAG, "addPermissionsCallback(): Added callback for onRequestPermissionsResult");
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        Log.v(TAG, "onRequestPermissionsResult()");
        if (havePermissionsCallback) {
            Log.v(TAG, "onRequestPermissionsResult passed to callback");
            permissionCallback.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // Using instance method checkSelfPermission since minApi >= 23
    public boolean checkCurrentPermission(String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true; // Should not hit if minApi = 26
        // No reflection needed, direct call.
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    
    // Using instance method requestPermissions since minApi >= 23
    public void requestPermissionsWithRequestCode(String[] permissions, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return; // Should not hit
        // No reflection needed, direct call.
        requestPermissions(permissions, requestCode);
    }

    // This is an instance method, matches the one used by android.permissions from P4A
    public void requestPermissions(String[] permissions) {
        requestPermissionsWithRequestCode(permissions, 1); // Default request code 1
    }

    public interface DarkModeListener {
        void onDarkModeChanged(boolean isDarkMode);
    }

    private DarkModeListener darkModeListener = null;

    public void setDarkModeListener(DarkModeListener listener) {
        darkModeListener = listener;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        int currentNightMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES;

        if (darkModeListener != null) {
            darkModeListener.onDarkModeChanged(isDarkMode);
        }

        super.onConfigurationChanged(newConfig);
    }
}

// These classes are typically in separate files in P4A's webview bootstrap
// (e.g., PythonMain.java, WebViewLoader.java).
// If you are modifying PythonActivity.java directly in your *fork* of P4A,
// these would remain as they are if they are indeed separate files in the original P4A structure.
// If they were helper classes *inside* the original PythonActivity.java you copied,
// then keeping them here is fine.

// Assuming they are separate for now, and you'd modify WebViewLoader.java separately if needed.
// If they were defined at the bottom of P4A's PythonActivity.java, then you'd keep them here.
// The original file you posted had them at the bottom, so I'll keep them here.

class PythonMain implements Runnable {
    @Override
    public void run() {
        if (PythonActivity.mActivity != null) {
            PythonActivity.nativeInit(new String[0]);
        } else {
            Log.e("PythonMain", "mActivity is null, cannot call nativeInit!");
        }
    }
}

class WebViewLoaderMain implements Runnable {
    @Override
    public void run() {
        if (PythonActivity.mActivity != null) {
            // If you modify WebViewLoader.java for query params,
            // ensure that class is also part of your forked P4A source.
            WebViewLoader.testConnection();
        } else {
            Log.e("WebViewLoaderMain", "mActivity is null, cannot call WebViewLoader.testConnection!");
        }
    }
}
package org.kivy.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class PythonService extends Service implements Runnable {

    // Thread for Python code
    private Thread pythonThread = null;

    // Python environment variables
    private String androidPrivate;
    private String androidArgument;
    private String pythonName;
    private String pythonHome;
    private String pythonPath;
    private String serviceEntrypoint;
    // Argument to pass to Python code,
    private String pythonServiceArgument;

    public static PythonService mService = null;
    private Intent startIntent = null;

    /**
     * MediaProjection hand-off, activity process -> service process.
     *
     * A MediaProjection can only be consented to by an Activity, but anything
     * that wants to use it here (AudioPlaybackCapture, screen capture) runs in
     * the service process, where there is no Activity at all. The activity
     * broadcasts the approved result and this receiver parks it in a static,
     * so Python can pick it up through pyjnius on the service side.
     *
     * A broadcast rather than startService extras on purpose: onStartCommand
     * returns early once the Python thread exists, so extras delivered that way
     * would be dropped, and a broadcast needs no knowledge of the generated
     * Service<Name> class.
     */
    public static final String ACTION_SET_MEDIA_PROJECTION =
        "org.kivy.android.action.SET_MEDIA_PROJECTION";

    private static int sProjectionResultCode = 0;
    private static Intent sProjectionResultData = null;
    private BroadcastReceiver projectionReceiver = null;

    /** Activity.RESULT_OK when consent was granted, 0 when never asked. */
    public static int getMediaProjectionResultCode() {
        return sProjectionResultCode;
    }

    /** The approved Intent, or null. Feed straight to getMediaProjection(). */
    public static Intent getMediaProjectionResultData() {
        return sProjectionResultData;
    }

    /** True once the user has approved a capture that is still usable. */
    public static boolean hasMediaProjection() {
        return sProjectionResultData != null && sProjectionResultCode != 0;
    }

    /** Dropped when the projection stops, so the UI can re-prompt. */
    public static void clearMediaProjection() {
        sProjectionResultCode = 0;
        sProjectionResultData = null;
    }

    private boolean autoRestartService = false;

    public void setAutoRestartService(boolean restart) {
        autoRestartService = restart;
    }

    public int startType() {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        projectionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                sProjectionResultCode = intent.getIntExtra("resultCode", 0);
                sProjectionResultData = intent.getParcelableExtra("resultData");
                Log.i("python service", "media projection received, code="
                      + sProjectionResultCode);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_SET_MEDIA_PROJECTION);
        // API 34 makes the exported flag mandatory. The literal is
        // Context.RECEIVER_NOT_EXPORTED, written out so this still compiles
        // against older SDKs.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(projectionReceiver, filter, 4);
        } else {
            registerReceiver(projectionReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (pythonThread != null) {
            Log.v("python service", "service exists, do not start again");
            return startType();
        }
        // intent is null if OS restarts a STICKY service
        if (intent == null) {
            Context context = getApplicationContext();
            intent = getThisDefaultIntent(context, "");
        }

        startIntent = intent;
        Bundle extras = intent.getExtras();
        androidPrivate = extras.getString("androidPrivate");
        androidArgument = extras.getString("androidArgument");
        serviceEntrypoint = extras.getString("serviceEntrypoint");
        pythonName = extras.getString("pythonName");
        pythonHome = extras.getString("pythonHome");
        pythonPath = extras.getString("pythonPath");
        boolean serviceStartAsForeground =
                (extras.getString("serviceStartAsForeground").equals("true"));
        pythonServiceArgument = extras.getString("pythonServiceArgument");
        pythonThread = new Thread(this);
        pythonThread.start();

        if (serviceStartAsForeground) {
            doStartForeground(extras);
        }

        return startType();
    }

    protected int getServiceId() {
        return 1;
    }

    protected Intent getThisDefaultIntent(Context ctx, String pythonServiceArgument) {
        return null;
    }

    protected void doStartForeground(Bundle extras) {
        String serviceTitle = extras.getString("serviceTitle");
        String smallIconName = extras.getString("smallIconName");
        String contentTitle = extras.getString("contentTitle");
        String contentText = extras.getString("contentText");
        Notification notification;
        Context context = getApplicationContext();
        Intent contextIntent = new Intent(context, PythonActivity.class);
        PendingIntent pIntent =
                PendingIntent.getActivity(
                        context,
                        0,
                        contextIntent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Unspecified icon uses default.
        int smallIconId = context.getApplicationInfo().icon;
        if (smallIconName != null) {
            if (!smallIconName.equals("")) {
                int resId = getResources().getIdentifier(smallIconName, "mipmap", getPackageName());
                if (resId == 0) {
                    resId =
                            getResources()
                                    .getIdentifier(smallIconName, "drawable", getPackageName());
                }
                if (resId != 0) {
                    smallIconId = resId;
                }
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // This constructor is deprecated
            notification = new Notification(smallIconId, serviceTitle, System.currentTimeMillis());
            try {
                // prevent using NotificationCompat, this saves 100kb on apk
                Method func =
                        notification
                                .getClass()
                                .getMethod(
                                        "setLatestEventInfo",
                                        Context.class,
                                        CharSequence.class,
                                        CharSequence.class,
                                        PendingIntent.class);
                func.invoke(notification, context, contentTitle, contentText, pIntent);
            } catch (NoSuchMethodException
                    | IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException e) {
            }
        } else {
            // for android 8+ we need to create our own channel
            // https://stackoverflow.com/questions/47531742/startforeground-fail-after-upgrade-to-android-8-1
            String NOTIFICATION_CHANNEL_ID = "org.kivy.p4a" + getServiceId();
            String channelName = "Background Service" + getServiceId();
            NotificationChannel chan =
                    new NotificationChannel(
                            NOTIFICATION_CHANNEL_ID,
                            channelName,
                            NotificationManager.IMPORTANCE_NONE);

            chan.setLightColor(Color.BLUE);
            chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(chan);

            Notification.Builder builder =
                    new Notification.Builder(context, NOTIFICATION_CHANNEL_ID);
            builder.setContentTitle(contentTitle);
            builder.setContentText(contentText);
            builder.setContentIntent(pIntent);
            builder.setSmallIcon(smallIconId);
            notification = builder.build();
        }
        startForeground(getServiceId(), notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (projectionReceiver != null) {
            try {
                unregisterReceiver(projectionReceiver);
            } catch (Exception e) {
                Log.w("python service", "projection receiver already gone");
            }
            projectionReceiver = null;
        }
        pythonThread = null;
        if (autoRestartService && startIntent != null) {
            Log.v("python service", "service restart requested");
            startService(startIntent);
        }
        Process.killProcess(Process.myPid());
    }

    /**
     * Stops the task gracefully when killed. Calling stopSelf() will trigger a onDestroy() call
     * from the system.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // sticky service runtime/restart is managed by the OS. leave it running when app is closed
        if (startType() != START_STICKY) {
            stopSelf();
        }
    }

    @Override
    public void run() {
        String app_root = getFilesDir().getAbsolutePath() + "/app";
        File app_root_file = new File(app_root);
        PythonUtil.loadLibraries(app_root_file, new File(getApplicationInfo().nativeLibraryDir));
        this.mService = this;
        nativeStart(
                androidPrivate,
                androidArgument,
                serviceEntrypoint,
                pythonName,
                pythonHome,
                pythonPath,
                pythonServiceArgument);
        stopSelf();
    }

    // Native part
    public static native void nativeStart(
            String androidPrivate,
            String androidArgument,
            String serviceEntrypoint,
            String pythonName,
            String pythonHome,
            String pythonPath,
            String pythonServiceArgument);
}

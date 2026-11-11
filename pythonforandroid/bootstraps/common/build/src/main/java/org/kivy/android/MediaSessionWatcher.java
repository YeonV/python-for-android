package org.kivy.android;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports changes to the device's media sessions to Python, so Now Playing
 * reacts to events instead of polling getActiveSessions().
 *
 * Exists in Java because MediaController.Callback is an abstract CLASS, and
 * pyjnius can only implement interfaces - the same reason as
 * PythonService.watchProjectionStop(). Everything here funnels into one
 * MediaSessionWatcherListener.onMediaChanged() call; Python then reads the
 * session state itself.
 *
 * Permissions, checked against developer.android.com: no new one.
 * addOnActiveSessionsChangedListener() requires MEDIA_CONTENT_CONTROL or an
 * enabled notification listener - the same gate getActiveSessions() already
 * passes through PythonNotificationListenerService. registerCallback() and
 * unregisterCallback() require none.
 */
public class MediaSessionWatcher {

    private static final String TAG = "MediaSessionWatcher";

    private final MediaSessionManager manager;
    private final ComponentName component;
    private final MediaSessionWatcherListener listener;
    // Main looper: it exists in the service process, and every update is
    // posted to it in order, so the controller list is only touched here.
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private boolean started = false;

    // One instance serves every watched controller; each keeps its own
    // registration list.
    private final MediaController.Callback controllerCallback =
        new MediaController.Callback() {
            // VERIFIED-SAFE(onMetadataChanged): MediaController.Callback
            // declares `public void onMetadataChanged(MediaMetadata)` - public,
            // non-final, empty default, API 21 (developer.android.com).
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                notifyChanged();
            }

            // VERIFIED-SAFE(onPlaybackStateChanged): MediaController.Callback
            // declares `public void onPlaybackStateChanged(PlaybackState)` -
            // public, non-final, API 21 (developer.android.com).
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                notifyChanged();
            }

            // VERIFIED-SAFE(onSessionDestroyed): MediaController.Callback
            // declares `public void onSessionDestroyed()` - public, non-final,
            // API 21 (developer.android.com).
            @Override
            public void onSessionDestroyed() {
                notifyChanged();
            }
        };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
        new MediaSessionManager.OnActiveSessionsChangedListener() {
            // VERIFIED-SAFE(onActiveSessionsChanged): the interface's only
            // method, `void onActiveSessionsChanged(List<MediaController>)`,
            // API 21; controllers may be null (developer.android.com).
            @Override
            public void onActiveSessionsChanged(List<MediaController> controllers) {
                watch(controllers);
                notifyChanged();
            }
        };

    public MediaSessionWatcher(
            Context context, String listenerClass, MediaSessionWatcherListener listener) {
        this.manager = (MediaSessionManager)
            context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.component = new ComponentName(context.getPackageName(), listenerClass);
        this.listener = listener;
    }

    // VERIFIED-SAFE(start): MediaSessionWatcher extends only Object, which has
    // no start(); nothing to collide with.
    /**
     * Start watching. Throws SecurityException while the user has not
     * granted notification access, exactly like getActiveSessions().
     */
    public void start() {
        if (started) {
            return;
        }
        manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler);
        started = true;
        watch(manager.getActiveSessions(component));
    }

    // VERIFIED-SAFE(stop): MediaSessionWatcher extends only Object, which has
    // no stop(); nothing to collide with.
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        try {
            manager.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Exception e) {
            Log.w(TAG, "Could not remove the active sessions listener", e);
        }
        unwatchAll();
    }

    // VERIFIED-SAFE(watch): private helper on a class extending only Object.
    private void watch(List<MediaController> controllers) {
        unwatchAll();
        if (controllers == null) {
            return;
        }
        for (MediaController controller : controllers) {
            try {
                controller.registerCallback(controllerCallback, handler);
                watched.add(controller);
            } catch (Exception e) {
                Log.w(TAG, "Could not watch " + controller.getPackageName(), e);
            }
        }
    }

    // VERIFIED-SAFE(unwatchAll): private helper on a class extending only Object.
    private void unwatchAll() {
        for (MediaController controller : watched) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (Exception e) {
                Log.w(TAG, "Could not unwatch " + controller.getPackageName(), e);
            }
        }
        watched.clear();
    }

    // VERIFIED-SAFE(notifyChanged): private helper on a class extending only
    // Object.
    private void notifyChanged() {
        if (!started) {
            return;
        }
        try {
            listener.onMediaChanged();
        } catch (Exception e) {
            // A failing Python handler must never take the service down.
            Log.w(TAG, "Media change listener failed", e);
        }
    }
}

package org.kivy.android;

import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * Declared so the app can be granted notification access.
 *
 * Nothing is read from the notifications themselves. The class exists because
 * Android gates MediaSessionManager.getActiveSessions() behind an *enabled*
 * notification listener: the call is only permitted when a component like this
 * one is declared in the manifest and switched on by the user in Settings.
 * With that granted, Python can read the active media session through pyjnius
 * and no Java side is needed at all - which is why this class stays empty.
 *
 * Until the user grants access the system never binds it, so an app that does
 * not use the feature pays nothing beyond an entry in the settings list.
 */
public class PythonNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "PythonNotifListener";

    /**
     * Disconnect from the notification stream as soon as the system connects
     * it.
     *
     * The grant the user gives is broad - Android's own dialog warns that it
     * allows reading notifications, including the text of messages. Only the
     * *enabled* state matters for MediaSessionManager.getActiveSessions(), not
     * whether this service is bound, so unbinding keeps media metadata working
     * while making sure notifications are never delivered to the app at all.
     *
     * That turns "this app does not read your notifications" from a promise
     * about our code into a property of the process. It also stops waking us
     * for every notification on the device.
     *
     * The system may connect again later (after a reboot, or when the listener
     * list changes); each time, this runs again.
     */
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            requestUnbind();
            Log.i(TAG, "Unbound from the notification stream; media session access is unaffected");
        } catch (Exception e) {
            // Staying bound is not harmful - nothing here reads notifications -
            // so this must never take the service down.
            Log.w(TAG, "Could not unbind from the notification stream", e);
        }
    }
}

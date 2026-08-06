package org.kivy.android;

import android.service.notification.NotificationListenerService;

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
}

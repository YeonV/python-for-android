package org.kivy.android;

/**
 * Implemented from Python (pyjnius) to hear about media session changes.
 * See MediaSessionWatcher.
 */
public interface MediaSessionWatcherListener {
    void onMediaChanged();
}

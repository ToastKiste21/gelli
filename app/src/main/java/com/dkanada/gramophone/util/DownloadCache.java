package com.dkanada.gramophone.util;

import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tells interested adapters that the set of downloaded songs changed.
 *
 * <p>The "downloaded" indicator is driven by a snapshot of the cache table that used to be taken
 * once when an adapter was built, so finishing or removing a download left the icon lying until
 * the screen was rebuilt.
 *
 * <p>Listeners are held weakly: an adapter that is garbage collected without detaching cleanly
 * cannot keep its activity alive through this registry.
 */
public final class DownloadCache {
    private static final Set<Runnable> listeners =
            Collections.newSetFromMap(new WeakHashMap<Runnable, Boolean>());

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private DownloadCache() {
    }

    public static void addListener(Runnable listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    /** Safe to call from any thread; listeners always run on the main thread. */
    public static void notifyChanged() {
        Runnable[] snapshot;
        synchronized (listeners) {
            snapshot = listeners.toArray(new Runnable[0]);
        }

        for (Runnable listener : snapshot) {
            mainHandler.post(listener);
        }
    }
}

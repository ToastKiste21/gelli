package com.dkanada.gramophone.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;
import android.widget.Toast;

import com.dkanada.gramophone.App;
import com.dkanada.gramophone.BuildConfig;
import com.dkanada.gramophone.R;
import com.dkanada.gramophone.database.Cache;
import com.dkanada.gramophone.model.Song;
import com.dkanada.gramophone.service.notifications.DownloadNotification;
import com.dkanada.gramophone.network.JellyfinAuth;
import com.dkanada.gramophone.util.MusicUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class DownloadService extends Service {
    public static final String TAG = DownloadService.class.getSimpleName();

    public static final String PACKAGE_NAME = BuildConfig.APPLICATION_ID;
    public static final String ACTION_START = PACKAGE_NAME + ".action.start";
    public static final String ACTION_CANCEL = PACKAGE_NAME + ".action.cancel";
    public static final String ACTION_DOWNLOAD_COMPLETE = PACKAGE_NAME + ".action.download.complete";
    public static final String EXTRA_SONGS = PACKAGE_NAME + ".extra.songs";

    private ExecutorService executor;
    private DownloadNotification notification;
    private Handler mainHandler;
    private final AtomicInteger activeDownloads = new AtomicInteger(0);

    @Override
    public void onCreate() {
        super.onCreate();

        executor = Executors.newFixedThreadPool(4);
        notification = new DownloadNotification(this);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return super.onStartCommand(null, flags, startId);
        }

        switch (intent.getAction()) {
            case DownloadService.ACTION_CANCEL:
                executor.shutdownNow();
                notification.stop(null, false);
                stopSelf();
                break;
            case DownloadService.ACTION_START:
                notification.ensureForeground();
                List<Song> songs = intent.getParcelableArrayListExtra(EXTRA_SONGS);
                if (songs != null) {
                    for (Song song : songs) {
                        if (App.getDatabase().cacheDao().isCached(song.id)) {
                            File existing = new File(MusicUtil.getFileUri(song));
                            if (existing.exists() && existing.length() > 0) {
                                showToast(getString(R.string.download_already_exists));
                                continue;
                            }
                            App.getDatabase().cacheDao().deleteCache(song.id);
                        }

                        notification.start(song);
                        activeDownloads.incrementAndGet();
                        download(song);
                    }
                }

                if (activeDownloads.get() <= 0) {
                    notification.stop(null, false);
                    stopSelf();
                }
                break;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void download(Song song) {
        executor.execute(() -> {
            File audio = new File(MusicUtil.getFileUri(song));
            File tempFile = new File(audio.getParent(), "." + song.id + ".tmp");

            notification.startSong(song);

            try {
                audio.getParentFile().mkdirs();

                if (!hasEnoughSpace(audio, song.size)) {
                    showToast(getString(R.string.download_not_enough_space));
                    onDownloadFinished(song, false);
                    return;
                }

                URL url = new URL(MusicUtil.getDownloadUri(song));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                String authorization = JellyfinAuth.header();
                if (authorization != null) connection.setRequestProperty(JellyfinAuth.AUTH_HEADER, authorization);
                connection.connect();

                int contentLength = connection.getContentLength();
                notification.update(song, 0, contentLength);

                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(tempFile)) {

                    byte[] data = new byte[8192];
                    int count;

                    while ((count = input.read(data)) != -1) {
                        output.write(data, 0, count);
                        notification.update(song, count, 0);
                    }
                }

                connection.disconnect();

                if (tempFile.length() == 0) {
                    tempFile.delete();
                    onDownloadFinished(song, false);
                    return;
                }

                if (!tempFile.renameTo(audio)) {
                    copyAndDelete(tempFile, audio);
                }

                App.getDatabase().cacheDao().insertCache(new Cache(song));
                onDownloadFinished(song, true);

            } catch (Exception e) {
                Log.e(TAG, "Download failed for: " + song.title, e);
                tempFile.delete();
                audio.delete();
                onDownloadFinished(song, false);
            }
        });
    }

    private void onDownloadFinished(Song song, boolean success) {
        notification.stop(song, success);
        if (success) {
            Intent downloadIntent = new Intent(ACTION_DOWNLOAD_COMPLETE);
            downloadIntent.setPackage(getPackageName());
            sendBroadcast(downloadIntent);
        }

        if (activeDownloads.decrementAndGet() <= 0) {
            stopSelf();
        }
    }

    private boolean hasEnoughSpace(File target, long fileSize) {
        if (fileSize <= 0) return true;

        try {
            StatFs stat = new StatFs(target.getParentFile().getAbsolutePath());
            long available = stat.getAvailableBytes();
            return available > fileSize * 2;
        } catch (Exception e) {
            return true;
        }
    }

    private void copyAndDelete(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int count;
            while ((count = in.read(buf)) != -1) {
                out.write(buf, 0, count);
            }
        }
        src.delete();
    }

    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}

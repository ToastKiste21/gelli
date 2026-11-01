package com.dkanada.gramophone.service.notifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.dkanada.gramophone.R;
import com.dkanada.gramophone.activities.MainActivity;
import com.dkanada.gramophone.model.Song;
import com.dkanada.gramophone.service.DownloadService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static android.content.Context.NOTIFICATION_SERVICE;

public class DownloadNotification {
    private static final String CHANNEL_ID = DownloadNotification.class.getSimpleName();
    private static final int NOTIFICATION_ID = 2;
    private static final int SUMMARY_NOTIFICATION_ID = 3;

    private final Service service;
    private final NotificationManager notificationManager;

    /**
     * Android drops everything above roughly five notifications per second, and each rebuild is a
     * synchronous binder call. Posting one per 8 KiB chunk made the progress bar invisible and
     * slowed the transfer, so updates are throttled to this interval.
     */
    private static final long NOTIFY_INTERVAL_MS = 500;

    private final List<Song> pendingSongs;

    /** song id to bytes already read, for every download currently in flight */
    private final Map<String, Long> activeBytes;

    private long batchTotalBytes;
    private long finishedBytes;
    private boolean foregroundStarted;

    private long lastNotifyAt;

    private String currentSongTitle;
    private int totalQueued;
    private int completedCount;
    private int failedCount;

    public DownloadNotification(Service service) {
        this.notificationManager = (NotificationManager) service.getSystemService(NOTIFICATION_SERVICE);
        this.service = service;

        this.pendingSongs = new ArrayList<>();
        this.activeBytes = new LinkedHashMap<>();
    }

    public synchronized void ensureForeground() {
        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel();
            }

            service.startForeground(NOTIFICATION_ID, buildProgressNotification());
            foregroundStarted = true;
        }
    }

    public synchronized void start(Song song) {
        this.pendingSongs.add(song);
        this.totalQueued++;
        if (song.size > 0) this.batchTotalBytes += song.size;

        ensureForeground();
    }

    public void startSong(Song song) {
        Notification notification;
        synchronized (this) {
            activeBytes.put(song.id, 0L);
            currentSongTitle = song.title;
            lastNotifyAt = SystemClock.elapsedRealtime();

            notification = buildProgressNotification();
        }

        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * Called once per read, so for a single track this runs thousands of times. Bytes are always
     * accumulated; the notification itself is only rebuilt and posted every
     * {@link #NOTIFY_INTERVAL_MS}.
     */
    public void update(Song song, int bytesRead, int contentLength) {
        Notification notification;
        synchronized (this) {
            Long previous = activeBytes.get(song.id);
            activeBytes.put(song.id, (previous == null ? 0L : previous) + bytesRead);

            // the server may disagree with the size the library reported
            if (contentLength > 0 && song.size > 0 && contentLength != song.size) {
                batchTotalBytes += contentLength - song.size;
                song.size = contentLength;
            } else if (contentLength > 0 && song.size <= 0) {
                batchTotalBytes += contentLength;
                song.size = contentLength;
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) return;
            lastNotifyAt = now;

            notification = buildProgressNotification();
        }

        // outside the lock: notify() is a binder round trip and would serialise every download thread
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    public synchronized void stop(Song song, boolean success) {
        if (song != null) {
            pendingSongs.remove(song);
            activeBytes.remove(song.id);
            if (success) {
                completedCount++;
                if (song.size > 0) finishedBytes += song.size;
            } else {
                failedCount++;
                if (song.size > 0) batchTotalBytes -= song.size;
            }
        } else {
            pendingSongs.clear();
            activeBytes.clear();
        }

        if (pendingSongs.size() != 0) {
            lastNotifyAt = SystemClock.elapsedRealtime();
            notificationManager.notify(NOTIFICATION_ID, buildProgressNotification());
            return;
        }

        foregroundStarted = false;
        service.stopForeground(true);
        notificationManager.cancel(NOTIFICATION_ID);

        showSummaryNotification();

        activeBytes.clear();
        batchTotalBytes = 0;
        finishedBytes = 0;
        lastNotifyAt = 0;
        currentSongTitle = null;
        totalQueued = 0;
        completedCount = 0;
        failedCount = 0;
    }

    private Notification buildProgressNotification() {
        Intent action = new Intent(service, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent clickIntent = PendingIntent.getActivity(service, 0, action, PendingIntent.FLAG_IMMUTABLE);

        Intent cancel = new Intent(service, DownloadService.class).setAction(DownloadService.ACTION_CANCEL);
        PendingIntent pendingCancel = PendingIntent.getService(service, 0, cancel, PendingIntent.FLAG_IMMUTABLE);

        String title;
        if (currentSongTitle != null) {
            title = currentSongTitle;
        } else if (pendingSongs.size() > 0) {
            title = pendingSongs.get(0).title;
        } else {
            title = service.getString(R.string.action_download);
        }

        int done = completedCount + failedCount;
        String contentText = String.format(service.getString(R.string.download_progress), done + 1, totalQueued);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(clickIntent)
            .setContentTitle(title)
            .setContentText(contentText)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_close_white_24dp, service.getString(R.string.action_cancel), pendingCancel)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true);

        // aggregate over the batch, so parallel downloads cannot make the bar jump backwards
        long readBytes = finishedBytes;
        for (Long value : activeBytes.values()) readBytes += value;

        if (batchTotalBytes > 0) {
            int percent = (int) Math.min(100, (readBytes * 100) / batchTotalBytes);
            builder.setProgress(100, percent, false);
            builder.setSubText(percent + "%");
        } else {
            builder.setProgress(0, 0, true);
        }

        if (totalQueued > 1) {
            NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
            style.setBigContentTitle(contentText);
            for (Song item : pendingSongs.stream().limit(5).collect(Collectors.toList())) {
                String prefix = item.title.equals(currentSongTitle) ? "\u25B6 " : "   ";
                style.addLine(prefix + item.title);
            }
            if (pendingSongs.size() > 5) {
                style.setSummaryText("+" + (pendingSongs.size() - 5) + " more");
            }
            builder.setStyle(style);
        }

        return builder.build();
    }

    private void showSummaryNotification() {
        if (completedCount == 0 && failedCount == 0) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        }

        Intent action = new Intent(service, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent clickIntent = PendingIntent.getActivity(service, 0, action, PendingIntent.FLAG_IMMUTABLE);

        String title;
        if (failedCount == 0) {
            title = String.format(service.getString(R.string.download_complete_x_songs), completedCount);
        } else {
            title = String.format(service.getString(R.string.download_complete_with_errors), completedCount, failedCount);
        }

        Notification notification = new NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(clickIntent)
            .setContentTitle(title)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build();

        notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createNotificationChannel() {
        NotificationChannel notificationChannel = notificationManager.getNotificationChannel(CHANNEL_ID);

        if (notificationChannel == null) {
            notificationChannel = new NotificationChannel(CHANNEL_ID, service.getString(R.string.action_download), NotificationManager.IMPORTANCE_LOW);

            notificationChannel.setDescription(service.getString(R.string.playing_notification_description));
            notificationChannel.enableLights(false);
            notificationChannel.enableVibration(false);

            notificationManager.createNotificationChannel(notificationChannel);
        }
    }
}

package com.dkanada.gramophone.service.notifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.dkanada.gramophone.R;
import com.dkanada.gramophone.activities.MainActivity;
import com.dkanada.gramophone.model.Song;
import com.dkanada.gramophone.service.DownloadService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static android.content.Context.NOTIFICATION_SERVICE;

public class DownloadNotification {
    private static final String CHANNEL_ID = DownloadNotification.class.getSimpleName();
    private static final int NOTIFICATION_ID = 2;
    private static final int SUMMARY_NOTIFICATION_ID = 3;

    private final Service service;
    private final NotificationManager notificationManager;

    private final List<Song> pendingSongs;

    private int currentBytes;
    private int totalBytes;
    private boolean foregroundStarted;

    private String currentSongTitle;
    private int totalQueued;
    private int completedCount;
    private int failedCount;

    public DownloadNotification(Service service) {
        this.notificationManager = (NotificationManager) service.getSystemService(NOTIFICATION_SERVICE);
        this.service = service;

        this.pendingSongs = new ArrayList<>();
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

        ensureForeground();
    }

    public synchronized void startSong(Song song) {
        this.currentSongTitle = song.title;
        this.currentBytes = 0;
        this.totalBytes = 0;

        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification());
    }

    public synchronized void update(int bytesRead, int contentLength) {
        this.currentBytes += bytesRead;
        if (contentLength > 0) {
            this.totalBytes = contentLength;
        }

        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification());
    }

    public synchronized void stop(Song song, boolean success) {
        if (song != null) {
            pendingSongs.remove(song);
            if (success) {
                completedCount++;
            } else {
                failedCount++;
            }
        } else {
            pendingSongs.clear();
        }

        if (pendingSongs.size() != 0) {
            notificationManager.notify(NOTIFICATION_ID, buildProgressNotification());
            return;
        }

        foregroundStarted = false;
        service.stopForeground(true);
        notificationManager.cancel(NOTIFICATION_ID);

        showSummaryNotification();

        currentBytes = 0;
        totalBytes = 0;
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

        if (totalBytes > 0) {
            int clampedBytes = Math.min(currentBytes, totalBytes);
            builder.setProgress(totalBytes, clampedBytes, false);

            int percent = (int) ((clampedBytes / (float) totalBytes) * 100);
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

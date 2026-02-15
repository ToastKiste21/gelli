package com.dkanada.gramophone.service.playback;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.dkanada.gramophone.R;
import com.dkanada.gramophone.model.Song;
import com.dkanada.gramophone.util.MusicUtil;
import com.dkanada.gramophone.util.PreferenceUtil;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@OptIn(markerClass = UnstableApi.class)
public class LocalPlayer implements Playback {
    public static final String TAG = LocalPlayer.class.getSimpleName();

    private final Context context;
    private final ExoPlayer exoPlayer;
    private final SimpleCache simpleCache;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ReplayGainAudioProcessor replayGainProcessor;

    private PlaybackListener listener;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // Cached values for thread-safe access from non-main threads
    private volatile int cachedProgress = 0;
    private volatile int cachedDuration = 0;
    private volatile boolean cachedPlayWhenReady = false;
    private volatile int cachedSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE;
    private volatile int cachedVolume = 0;

    @SuppressWarnings("FieldCanBeLocal")
    private final Player.Listener eventListener = new Player.Listener() {
        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            Log.i(TAG, String.format("onPlayWhenReadyChanged: %b %d", playWhenReady, reason));
            cachedPlayWhenReady = playWhenReady;
            updateCachedState();
            if (listener != null) listener.onReadyChanged(playWhenReady, reason);
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            Log.i(TAG, String.format("onPlaybackStateChanged: %d", state));
            updateCachedState();
            if (listener != null) listener.onStateChanged(state);
        }

        @Override
        public void onPlaybackSuppressionReasonChanged(@Player.PlaybackSuppressionReason int playbackSuppressionReason) {
            Log.i(TAG, String.format("onPlaybackSuppressionReasonChanged: %d", playbackSuppressionReason));
            cachedSuppressionReason = playbackSuppressionReason;
            if (listener != null) listener.onStateChanged(Player.STATE_READY);
        }

        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            Log.i(TAG, String.format("onMediaItemTransition: %s %d", mediaItem, reason));
            updateCachedState();
            if (listener != null) listener.onTrackChanged(reason);
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
            Log.i(TAG, String.format("onPositionDiscontinuity: %d", reason));
            updateCachedState();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.i(TAG, String.format("onPlayerError: %s", error.getMessage()));

            exoPlayer.clearMediaItems();
            exoPlayer.prepare();

            Toast.makeText(context, context.getResources().getString(R.string.unplayable_file), Toast.LENGTH_SHORT).show();
        }
    };

    private void updateCachedState() {
        cachedProgress = (int) exoPlayer.getCurrentPosition();
        cachedDuration = (int) exoPlayer.getDuration();
        cachedPlayWhenReady = exoPlayer.getPlayWhenReady();
        cachedSuppressionReason = exoPlayer.getPlaybackSuppressionReason();
        cachedVolume = (int) (exoPlayer.getVolume() * 100);
    }

    public LocalPlayer(Context context) {
        this.context = context;

        replayGainProcessor = new ReplayGainAudioProcessor();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
            @Override
            protected AudioSink buildAudioSink(Context context,
                    boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                    .setAudioProcessors(new AudioProcessor[]{replayGainProcessor})
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build();
            }
        };

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build();

        exoPlayer = new ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(buildDataSourceFactory()))
            .setAudioAttributes(audioAttributes, true)
            .build();

        exoPlayer.addListener(eventListener);
        exoPlayer.prepare();

        long cacheSize = PreferenceUtil.getInstance(context).getMediaCacheSize();
        LeastRecentlyUsedCacheEvictor recentlyUsedCache = new LeastRecentlyUsedCacheEvictor(cacheSize);
        StandaloneDatabaseProvider databaseProvider = new StandaloneDatabaseProvider(context);

        File cacheDirectory = new File(PreferenceUtil.getInstance(context).getLocationCache(), "exoplayer");
        simpleCache = new SimpleCache(cacheDirectory, recentlyUsedCache, databaseProvider);
    }

    public ReplayGainAudioProcessor getReplayGainProcessor() {
        return replayGainProcessor;
    }

    @Override
    public void setQueue(List<Song> queue, int position, int progress, boolean resetCurrentSong) {
        executorService.submit(() -> {
            List<MediaItem> mediaItems = createMediaItems(queue);

            mainHandler.post(() -> {
                if (resetCurrentSong) {
                    exoPlayer.setMediaItems(mediaItems, position, progress);
                    return;
                }

                int currentPosition = exoPlayer.getCurrentMediaItemIndex();
                exoPlayer.removeMediaItems(0, currentPosition);

                if (exoPlayer.getMediaItemCount() > 1) {
                    exoPlayer.removeMediaItems(1, exoPlayer.getMediaItemCount());
                }

                if (position + 1 < mediaItems.size()) {
                    exoPlayer.addMediaItems(1, mediaItems.subList(position + 1, mediaItems.size()));
                }

                exoPlayer.addMediaItems(0, mediaItems.subList(0, position));
            });
        });
    }

    private List<MediaItem> createMediaItems(List<Song> queue) {
        return queue.stream().map(song -> {
            File audio = new File(MusicUtil.getFileUri(song));
            Uri uri = Uri.fromFile(audio);

            if (!audio.exists()) {
                uri = Uri.parse(MusicUtil.getTranscodeUri(song));
            }

            List<String> containers = PreferenceUtil.getInstance(context).getDirectPlayCodecs().stream()
                    .map(codec -> codec.container.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toList());
            List<String> codecs = PreferenceUtil.getInstance(context).getDirectPlayCodecs().stream()
                    .map(codec -> codec.codec.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toList());
            String maxBitrate = PreferenceUtil.getInstance(context).getMaximumBitrate();

            MediaItem mediaItem;

            if (uri.toString().contains("file://") || (containers.contains(song.container.toLowerCase(Locale.ROOT)) && codecs.contains(song.codec.toLowerCase(Locale.ROOT)) && song.bitRate <= Integer.parseInt(maxBitrate))) {
                mediaItem = new MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(song.id)
                        .build();
            } else {
                mediaItem = new MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(song.id)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build();
            }

            return mediaItem;
        }).collect(Collectors.toList());
    }

    @Override
    public void playSongAt(int position) {
        mainHandler.post(() -> {
            if (exoPlayer.getMediaItemCount() > 0) {
                exoPlayer.seekTo(Math.max(0, position) % exoPlayer.getMediaItemCount(), 0);
            }
        });
    }

    private DataSource.Factory buildDataSourceFactory() {
        return () -> new CacheDataSource(
                simpleCache,
                new DefaultDataSource.Factory(context).createDataSource(),
                new FileDataSource(),
                new CacheDataSink(simpleCache, 10 * 1024 * 1024),
                CacheDataSource.FLAG_BLOCK_ON_CACHE,
                null
        );
    }

    @Override
    public void setListener(PlaybackListener listener) {
        this.listener = listener;
    }

    private boolean isOnMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    @Override
    public boolean isReady() {
        if (isOnMainThread()) {
            cachedPlayWhenReady = exoPlayer.getPlayWhenReady();
        }
        return cachedPlayWhenReady;
    }

    @Override
    public boolean isPlaying() {
        if (isOnMainThread()) {
            cachedPlayWhenReady = exoPlayer.getPlayWhenReady();
            cachedSuppressionReason = exoPlayer.getPlaybackSuppressionReason();
        }
        return cachedPlayWhenReady && cachedSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE;
    }

    @Override
    public boolean isLoading() {
        if (!isOnMainThread()) {
            return false;
        }

        MediaItem current = exoPlayer.getCurrentMediaItem();
        if (current != null && current.localConfiguration != null
                && current.localConfiguration.uri.toString().contains("file://")) {
            return false;
        }

        return exoPlayer.getPlaybackState() == Player.STATE_BUFFERING;
    }

    @Override
    public void start() {
        mainHandler.post(() -> exoPlayer.setPlayWhenReady(true));
    }

    @Override
    public void pause() {
        mainHandler.post(() -> exoPlayer.setPlayWhenReady(false));
    }

    @Override
    public void stop() {
        executorService.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        simpleCache.release();
        exoPlayer.release();
    }

    @Override
    public void previous() {
        mainHandler.post(() -> exoPlayer.seekToPreviousMediaItem());
    }

    @Override
    public void next() {
        mainHandler.post(() -> exoPlayer.seekToNextMediaItem());
    }

    @Override
    public void setRepeatMode(@Player.RepeatMode int repeatMode) {
        mainHandler.post(() -> exoPlayer.setRepeatMode(repeatMode));
    }

    @Override
    public int getProgress() {
        if (isOnMainThread()) {
            cachedProgress = (int) exoPlayer.getCurrentPosition();
        }
        return cachedProgress;
    }

    @Override
    public int getDuration() {
        if (isOnMainThread()) {
            cachedDuration = (int) exoPlayer.getDuration();
        }
        return cachedDuration;
    }

    @Override
    public void setProgress(int progress) {
        mainHandler.post(() -> exoPlayer.seekTo(progress));
    }

    @Override
    public void setVolume(int volume) {
        mainHandler.post(() -> {
            exoPlayer.setVolume(volume / 100f);
            cachedVolume = volume;
        });
    }

    @Override
    public int getVolume() {
        if (isOnMainThread()) {
            cachedVolume = (int) (exoPlayer.getVolume() * 100);
        }
        return cachedVolume;
    }
}

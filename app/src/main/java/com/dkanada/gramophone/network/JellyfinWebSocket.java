package com.dkanada.gramophone.network;

import org.jellyfin.apiclient.interaction.ApiClient;
import org.jellyfin.apiclient.interaction.ApiEventListener;
import org.jellyfin.apiclient.interaction.websocket.ApiWebSocket;
import org.jellyfin.apiclient.interaction.websocket.ISocketListener;
import org.jellyfin.apiclient.interaction.websocket.JavaWebSocketClient;
import org.jellyfin.apiclient.logging.ILogger;
import org.jellyfin.apiclient.serialization.GsonJsonSerializer;

import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the notification socket connected, which is what lets other Jellyfin clients drive
 * playback remotely (play, pause, next, seek).
 *
 * <p>The api client builds its socket url as {@code /socket?api_key=...}. Jellyfin 12 renamed
 * that query parameter to {@code ApiKey} and rejects the old spelling with HTTP 403, so the url
 * is built here instead. Everything else, including parsing messages and dispatching them to the
 * {@link ApiEventListener}, is delegated to the api client's own {@link ApiWebSocket}.
 */
public class JellyfinWebSocket implements ISocketListener {
    private final ApiWebSocket delegate;
    private final ApiClient apiClient;
    private final ILogger logger;

    private JavaWebSocketClient socketClient;
    private ScheduledExecutorService keepAliveExecutor;
    private ScheduledFuture<?> keepAliveTask;

    public JellyfinWebSocket(ILogger logger, ApiEventListener eventListener, ApiClient apiClient) {
        this.logger = logger;
        this.apiClient = apiClient;
        this.delegate = new ApiWebSocket(new GsonJsonSerializer(), logger, eventListener, apiClient);
    }

    /**
     * Connects if there is an access token and no live connection. Safe to call repeatedly.
     */
    public synchronized void ensureConnected() {
        if (socketClient != null && socketClient.IsWebSocketOpenOrConnecting()) return;

        String token = apiClient.getAccessToken();
        String server = apiClient.getApiUrl();
        if (token == null || token.isEmpty() || server == null || server.isEmpty()) return;

        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        String address = server.replaceFirst("^http", "ws")
                + "/socket?ApiKey=" + token
                + "&deviceId=" + apiClient.getDeviceId();

        try {
            socketClient = new JavaWebSocketClient(logger, URI.create(address), this);
            socketClient.connect();
        } catch (Exception exception) {
            logger.error("could not open web socket", exception);
            socketClient = null;
        }
    }

    public synchronized void close() {
        cancelKeepAlive();
        if (socketClient == null) return;

        try {
            socketClient.close();
        } catch (Exception exception) {
            logger.error("could not close web socket", exception);
        }

        socketClient = null;
    }

    @Override
    public void onOpen() {
        // the api client's own handler is empty, nothing to forward
    }

    @Override
    public void onClose() {
        // a client cannot be reconnected, ensureConnected() builds a new one
        cancelKeepAlive();
    }

    @Override
    public void onMessage(String message) {
        handleKeepAlive(message);
        delegate.onMessage(message);
    }

    /**
     * The server announces a keep alive timeout and closes the connection when nothing is sent
     * within it. The api client never answers, so the socket used to die after a minute.
     */
    private void handleKeepAlive(String message) {
        try {
            JSONObject json = new JSONObject(message);
            if (!"ForceKeepAlive".equalsIgnoreCase(json.optString("MessageType"))) return;

            int timeout = json.optInt("Data", 60);
            scheduleKeepAlive(Math.max(5, timeout / 2));
        } catch (Exception exception) {
            logger.error("could not read web socket message", exception);
        }
    }

    private synchronized void scheduleKeepAlive(int seconds) {
        cancelKeepAlive();

        if (keepAliveExecutor == null) keepAliveExecutor = Executors.newSingleThreadScheduledExecutor();
        keepAliveTask = keepAliveExecutor.scheduleWithFixedDelay(
                this::sendKeepAlive, seconds, seconds, TimeUnit.SECONDS);
    }

    private synchronized void cancelKeepAlive() {
        if (keepAliveTask == null) return;

        keepAliveTask.cancel(false);
        keepAliveTask = null;
    }

    private synchronized void sendKeepAlive() {
        if (socketClient == null || !socketClient.IsWebSocketOpen()) {
            cancelKeepAlive();
            return;
        }

        try {
            socketClient.send("{\"MessageType\":\"KeepAlive\"}");
        } catch (Exception exception) {
            logger.error("could not send keep alive", exception);
        }
    }
}

package com.dkanada.gramophone.network;

import androidx.annotation.NonNull;

import com.android.volley.AuthFailureError;
import com.android.volley.Response;

import org.jellyfin.apiclient.interaction.VolleyStringRequest;
import org.jellyfin.apiclient.interaction.http.HttpRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Sends authentication the way modern Jellyfin servers expect it.
 *
 * <p>jellyfin-apiclient-java 0.7.3 emits the legacy Emby headers: the client/device
 * identification goes into {@code X-Emby-Authorization} and the access token into
 * {@code X-MediaBrowser-Token}. Jellyfin 12 dropped both of them and only reads the standard
 * {@code Authorization} header, with the access token as a {@code Token="..."} parameter.
 *
 * <p>Without that header the server sees an unauthenticated request: {@code
 * POST /Users/AuthenticateByName} then fails with HTTP 400 because it cannot derive a device
 * for the session, and every authenticated call fails with HTTP 401.
 *
 * <p>The legacy headers are still sent as well so that older servers keep working.
 */
public class JellyfinStringRequest extends VolleyStringRequest {
    private static final String LEGACY_AUTH_HEADER = "X-Emby-Authorization";
    private static final String LEGACY_TOKEN_HEADER = "X-MediaBrowser-Token";
    private static final String AUTH_HEADER = "Authorization";

    public JellyfinStringRequest(int method, String url, Response.Listener<String> listener, Response.ErrorListener errorListener, HttpRequest request) {
        super(method, url, listener, errorListener, request);
    }

    @NonNull
    @Override
    public Map<String, String> getHeaders() throws AuthFailureError {
        Map<String, String> headers = new HashMap<>(super.getHeaders());

        String authorization = headers.get(LEGACY_AUTH_HEADER);
        if (authorization == null || authorization.isEmpty()) return headers;

        String token = headers.get(LEGACY_TOKEN_HEADER);
        if (token != null && !token.isEmpty() && !authorization.contains("Token=")) {
            authorization = authorization + ", Token=\"" + token + "\"";
        }

        headers.put(AUTH_HEADER, authorization);
        return headers;
    }
}

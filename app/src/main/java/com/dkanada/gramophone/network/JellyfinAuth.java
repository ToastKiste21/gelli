package com.dkanada.gramophone.network;

import com.dkanada.gramophone.App;

import org.jellyfin.apiclient.interaction.ApiClient;

import java.util.Collections;
import java.util.Map;

/**
 * Builds the {@code Authorization} header for requests that do not go through the api client.
 *
 * <p>Jellyfin 12 dropped the {@code api_key} query parameter that this app used to append to
 * streaming, download and ReplayGain URLs, so those requests have to carry the access token in
 * the standard {@code Authorization} header instead.
 */
public final class JellyfinAuth {
    public static final String AUTH_HEADER = "Authorization";

    private JellyfinAuth() {
    }

    /**
     * @return the {@code Authorization} header value, or null when there is no access token yet
     */
    public static String header() {
        ApiClient apiClient = App.getApiClient();
        if (apiClient == null) return null;

        String token = apiClient.getAccessToken();
        if (token == null || token.isEmpty()) return null;

        return "MediaBrowser"
                + " Client=\"" + quote(apiClient.getClientName()) + "\""
                + ", Device=\"" + quote(apiClient.getDeviceName()) + "\""
                + ", DeviceId=\"" + quote(apiClient.getDeviceId()) + "\""
                + ", Version=\"" + quote(apiClient.getApplicationVersion()) + "\""
                + ", Token=\"" + quote(token) + "\"";
    }

    /**
     * @return a single entry header map, or an empty map when there is no access token yet
     */
    public static Map<String, String> headers() {
        String header = header();
        if (header == null) return Collections.emptyMap();

        return Collections.singletonMap(AUTH_HEADER, header);
    }

    /**
     * Header values must be printable ASCII, and a quote would end the parameter early.
     */
    private static String quote(String value) {
        if (value == null) return "";

        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= ' ' && character < 127 && character != '"' && character != '\\') {
                builder.append(character);
            }
        }

        return builder.toString();
    }
}

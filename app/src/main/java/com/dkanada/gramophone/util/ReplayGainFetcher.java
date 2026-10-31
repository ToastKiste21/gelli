package com.dkanada.gramophone.util;

import android.util.Log;

import com.dkanada.gramophone.App;
import com.dkanada.gramophone.network.JellyfinAuth;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class ReplayGainFetcher {
    private static final String TAG = "ReplayGainFetcher";

    public static float fetchNormalizationGain(String itemId) {
        HttpURLConnection connection = null;
        try {
            String serverUrl = App.getApiClient().getApiUrl();
            String userId = App.getApiClient().getCurrentUserId();

            String url = serverUrl + "/Users/" + userId + "/Items/" + itemId;
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");

            String authorization = JellyfinAuth.header();
            if (authorization != null) connection.setRequestProperty(JellyfinAuth.AUTH_HEADER, authorization);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                Log.w(TAG, "HTTP " + responseCode + " for item " + itemId);
                return 0f;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            if (json.has("NormalizationGain") && !json.isNull("NormalizationGain")) {
                float gain = (float) json.getDouble("NormalizationGain");
                Log.d(TAG, "NormalizationGain for " + itemId + ": " + gain + " dB");
                return gain;
            }

            Log.d(TAG, "No NormalizationGain for " + itemId);
            return 0f;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching NormalizationGain for " + itemId, e);
            return 0f;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}

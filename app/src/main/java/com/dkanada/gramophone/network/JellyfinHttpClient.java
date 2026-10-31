package com.dkanada.gramophone.network;

import android.content.Context;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.BasicNetwork;
import com.android.volley.toolbox.DiskBasedCache;
import com.android.volley.toolbox.HurlStack;

import org.jellyfin.apiclient.interaction.Response;
import org.jellyfin.apiclient.interaction.VolleyErrorListener;
import org.jellyfin.apiclient.interaction.VolleyResponseListener;
import org.jellyfin.apiclient.interaction.http.HttpRequest;
import org.jellyfin.apiclient.interaction.http.IAsyncHttpClient;
import org.jellyfin.apiclient.logging.ILogger;

/**
 * Drop-in replacement for {@code org.jellyfin.apiclient.interaction.VolleyHttpClient}.
 *
 * <p>It behaves identically except that it dispatches {@link JellyfinStringRequest}, which sends
 * the standard {@code Authorization} header required by Jellyfin 12.
 */
public class JellyfinHttpClient implements IAsyncHttpClient {
    private static final int DEFAULT_DISK_USAGE_BYTES = 100 * 1024 * 1024;

    private final ILogger logger;
    private final Context context;

    private RequestQueue requestQueue;

    public JellyfinHttpClient(ILogger logger, Context context) {
        this.logger = logger;
        this.context = context.getApplicationContext();
    }

    public synchronized RequestQueue getRequestQueue() {
        if (requestQueue == null) {
            DiskBasedCache cache = new DiskBasedCache(context.getCacheDir(), DEFAULT_DISK_USAGE_BYTES);
            BasicNetwork network = new BasicNetwork(new HurlStack());

            requestQueue = new RequestQueue(cache, network);
            requestQueue.start();
        }

        return requestQueue;
    }

    public void cancelPendingRequests(Object tag) {
        if (requestQueue != null) requestQueue.cancelAll(tag);
    }

    @Override
    public void Send(HttpRequest request, Response<String> response) {
        int method = Request.Method.GET;
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            method = Request.Method.POST;
        } else if ("DELETE".equalsIgnoreCase(request.getMethod())) {
            method = Request.Method.DELETE;
        }

        String url = request.getUrl();
        JellyfinStringRequest volleyRequest = new JellyfinStringRequest(method, url,
                new VolleyResponseListener<String>(response, logger, url),
                new VolleyErrorListener<String>(response, logger),
                request);

        if (method != Request.Method.GET || !request.getEnableCaching()) {
            volleyRequest.setShouldCache(false);
        }

        volleyRequest.setRetryPolicy(new DefaultRetryPolicy(request.getTimeout(), 0, 1f));

        logger.debug("Adding request to queue: %s", url);
        getRequestQueue().add(volleyRequest);
    }
}

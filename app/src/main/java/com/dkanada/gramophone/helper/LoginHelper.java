package com.dkanada.gramophone.helper;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.dkanada.gramophone.App;
import com.dkanada.gramophone.BuildConfig;
import com.dkanada.gramophone.R;
import com.dkanada.gramophone.model.User;
import com.dkanada.gramophone.util.PreferenceUtil;

import org.jellyfin.apiclient.interaction.EmptyResponse;
import org.jellyfin.apiclient.interaction.Response;
import org.jellyfin.apiclient.model.session.ClientCapabilities;
import org.jellyfin.apiclient.model.system.SystemInfo;

/**
 * Re-authenticates the stored user against the server and broadcasts the result.
 *
 * <p>This used to be a {@code Service} started from {@code Activity.onResume()}. Android 12
 * forbids starting a background service from a process the system already considers background,
 * which it does when the activity resumes while the screen is off, so the app crashed with
 * {@code BackgroundServiceStartNotAllowedException} on launch. None of the work needs a service:
 * the api client call is already asynchronous, so it runs here directly.
 */
public final class LoginHelper {
    private static final String PACKAGE_NAME = BuildConfig.APPLICATION_ID;

    public static final String STATE_POLLING = PACKAGE_NAME + ".unknown";
    public static final String STATE_ONLINE = PACKAGE_NAME + ".online";
    public static final String STATE_OFFLINE = PACKAGE_NAME + ".offline";

    private LoginHelper() {
    }

    public static void refresh(Context context) {
        Context appContext = context.getApplicationContext();
        broadcast(appContext, STATE_POLLING);

        User user = App.getDatabase().userDao().getUser(PreferenceUtil.getInstance(appContext).getUser());
        if (user == null) {
            Toast.makeText(appContext, appContext.getResources().getString(R.string.error_unexpected), Toast.LENGTH_SHORT).show();
            return;
        }

        App.getApiClient().ChangeServerLocation(user.server);
        App.getApiClient().SetAuthenticationInfo(user.token, user.id);
        App.getApiClient().GetSystemInfoAsync(new Response<SystemInfo>() {
            @Override
            public void onResponse(SystemInfo result) {
                ClientCapabilities clientCapabilities = new ClientCapabilities();
                clientCapabilities.setSupportsMediaControl(true);
                clientCapabilities.setSupportsPersistentIdentifier(true);

                App.getWebSocket().ensureConnected();
                App.getApiClient().ReportCapabilities(clientCapabilities, new EmptyResponse());

                broadcast(appContext, STATE_ONLINE);
            }

            @Override
            public void onError(Exception exception) {
                broadcast(appContext, STATE_OFFLINE);
            }
        });
    }

    private static void broadcast(Context context, String action) {
        Intent intent = new Intent(action);
        intent.setPackage(context.getPackageName());

        context.sendBroadcast(intent);
    }
}

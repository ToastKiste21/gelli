package com.dkanada.gramophone.activities.base;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.dkanada.gramophone.App;
import com.dkanada.gramophone.helper.LoginHelper;
import com.dkanada.gramophone.interfaces.StateListener;
import com.dkanada.gramophone.util.NavigationUtil;

public abstract class AbsMusicContentActivity extends AbsMusicPanelActivity implements StateListener {
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, @NonNull Intent intent) {
            if (intent.getAction() == null) return;

            switch(intent.getAction()) {
                case LoginHelper.STATE_ONLINE:
                    onStateOnline();
                    break;
                case LoginHelper.STATE_OFFLINE:
                    NavigationUtil.startLogin(context);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(LoginHelper.STATE_POLLING);
        filter.addAction(LoginHelper.STATE_ONLINE);
        filter.addAction(LoginHelper.STATE_OFFLINE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }

        if (App.getApiClient() == null) {
            LoginHelper.refresh(this);
        } else {
            onStateOnline();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (App.getApiClient() == null) {
            LoginHelper.refresh(this);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);

        super.onDestroy();
    }

    @Override
    public void onStatePolling() {
    }

    @Override
    public void onStateOffline() {
    }
}

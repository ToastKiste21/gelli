package com.dkanada.gramophone.service.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.dkanada.gramophone.helper.LoginHelper;

public class NetworkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();

        // network info will be null in airplane mode
        if (networkInfo != null && networkInfo.isConnected()) {
            Intent onlineIntent = new Intent(LoginHelper.STATE_ONLINE);
            onlineIntent.setPackage(context.getPackageName());
            context.sendBroadcast(onlineIntent);
        } else {
            Intent offlineIntent = new Intent(LoginHelper.STATE_OFFLINE);
            offlineIntent.setPackage(context.getPackageName());
            context.sendBroadcast(offlineIntent);
        }
    }
}

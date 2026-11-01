package com.dkanada.gramophone.activities.base;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import com.dkanada.gramophone.activities.MainActivity;
import com.dkanada.gramophone.util.NavigationUtil;
import com.dkanada.gramophone.R;
import com.dkanada.gramophone.util.PreferenceUtil;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

public abstract class AbsBaseActivity extends AbsThemeActivity {
    private static final int PERMISSION_REQUEST = 100;

    private List<String> permissions;
    private boolean allowed;

    private final Handler dialogHandler = new Handler();

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        permissions = getPermissionRequest();
        allowed = checkPermissions();
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        if (!(this instanceof MainActivity)) {
            return;
        }

        // Ask for the runtime permissions first, and independently of the battery prompt.
        // POST_NOTIFICATIONS gates downloads and the playing notification, and the old chain could
        // never reach requestPermissions(): shouldShowRequestPermissionRationale() returns false
        // until the system dialog has been shown once, so a fresh install fell straight through to
        // the "open settings" branch and the system never asked at all.
        if (permissions.size() != 0 && !checkPermissions()) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permissions.get(0))) {
                showDelayedDialog(getPermissionMessage(), getString(R.string.permissions_denied),
                    getString(R.string.action_grant), (dialog, id) -> requestPermissions());
            } else {
                // never asked, or permanently denied; the callback tells the two apart
                requestPermissions();
            }

            return;
        }

        showBatteryDialogIfNeeded();
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void showBatteryDialogIfNeeded() {
        if (checkBatteryOptimization()) return;

        showDelayedDialog(getString(R.string.battery_optimizations_message),
            getString(R.string.battery_optimizations_title),
            getString(R.string.disable), (dialog, id) -> requestBatteryOptimization());
    }

    /**
     * The delay exists so the dialog does not fight the activity transition. The activity can be
     * finished inside that window, so the callback is cancelled in onDestroy and guarded here --
     * otherwise show() throws BadTokenException on a dead window.
     */
    private void showDelayedDialog(String message, String title, String positive, android.content.DialogInterface.OnClickListener action) {
        dialogHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;

            new AlertDialog.Builder(this)
                .setNegativeButton(R.string.ignore, (dialog, id) -> showWarning())
                .setMessage(message)
                .setTitle(title)
                .setPositiveButton(positive, action)
                .show();
        }, 2000);
    }

    @Override
    protected void onDestroy() {
        dialogHandler.removeCallbacksAndMessages(null);

        super.onDestroy();
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    protected void onResume() {
        super.onResume();

        if (checkPermissions() != allowed) {
            super.recreate();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    protected View getPermissionWindow() {
        return getWindow().getDecorView();
    }

    protected List<String> getPermissionRequest() {
        return new ArrayList<>();
    }

    protected String getPermissionMessage() {
        return getString(R.string.permissions_denied);
    }

    private void showWarning() {
        Snackbar.make(getPermissionWindow(), getPermissionMessage(), Snackbar.LENGTH_SHORT)
            .setAction(R.string.ignore, view -> { })
            .setActionTextColor(PreferenceUtil.getInstance(this).getAccentColor())
            .show();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void requestBatteryOptimization() {
        Intent intent = new Intent();

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);

        startActivity(intent);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean checkBatteryOptimization() {
        String packageName = getPackageName();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

        return pm.isIgnoringBatteryOptimizations(packageName);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void requestPermissions() {
        requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean checkPermissions() {
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);

        if (requestCode != PERMISSION_REQUEST) {
            return;
        }

        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                if (this instanceof MainActivity
                        && this.permissions.size() != 0
                        && !ActivityCompat.shouldShowRequestPermissionRationale(this, this.permissions.get(0))) {
                    // permanently denied: the system dialog will not appear again
                    showDelayedDialog(getPermissionMessage(), getString(R.string.permissions_denied),
                        getString(R.string.action_settings), (dialog, id) -> NavigationUtil.openSettings(this));
                } else {
                    showWarning();
                }

                return;
            }
        }

        if (this instanceof MainActivity) showBatteryDialogIfNeeded();
    }
}

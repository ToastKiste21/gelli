package com.dkanada.gramophone.views.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.dkanada.gramophone.R;
import com.dkanada.gramophone.util.PreferenceUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FilePreference extends Preference implements View.OnClickListener {
    private final SharedPreferences preferences;
    private final String defaultLocation;

    public FilePreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        preferences = PreferenceUtil.getInstance(getContext()).getPreferences();
        defaultLocation = getContext().getFilesDir().toString();

        setSummary(preferences.getString(getKey(), defaultLocation));
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        holder.itemView.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            showLocationDialog();
        } else {
            showStorageChooser();
        }
    }

    private void showLocationDialog() {
        Context context = getContext();

        List<String> labels = new ArrayList<>();
        List<String> paths = new ArrayList<>();

        // Internal app storage
        labels.add(context.getString(R.string.download_location_internal));
        paths.add(context.getFilesDir().toString());

        // External app storage (if available)
        File externalDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (externalDir != null) {
            labels.add(context.getString(R.string.download_location_external));
            paths.add(externalDir.toString());
        }

        // Custom path option
        labels.add(context.getString(R.string.download_location_custom));

        String currentPath = preferences.getString(getKey(), defaultLocation);
        int checkedIndex = paths.indexOf(currentPath);
        if (checkedIndex < 0) checkedIndex = 0;

        new AlertDialog.Builder(context)
            .setTitle(R.string.pref_title_download_location)
            .setSingleChoiceItems(labels.toArray(new String[0]), checkedIndex, (dialog, which) -> {
                if (which < paths.size()) {
                    selectPath(paths.get(which));
                    dialog.dismiss();
                } else {
                    dialog.dismiss();
                    showCustomPathInput(currentPath);
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showCustomPathInput(String currentPath) {
        Context context = getContext();
        EditText input = new EditText(context);
        input.setText(currentPath);
        input.setSelectAllOnFocus(true);

        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(context);
        container.setPadding(padding, padding, padding, 0);
        container.addView(input);

        new AlertDialog.Builder(context)
            .setTitle(R.string.download_location_custom)
            .setView(container)
            .setPositiveButton(R.string.action_set, (dialog, which) -> {
                String path = input.getText().toString().trim();
                if (!path.isEmpty()) {
                    selectPath(path);
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showStorageChooser() {
        Activity activity = (Activity) getContext();
        android.app.FragmentManager fragmentManager = activity.getFragmentManager();

        com.codekidlabs.storagechooser.StorageChooser chooser =
            new com.codekidlabs.storagechooser.StorageChooser.Builder()
                .withActivity(activity)
                .withFragmentManager(fragmentManager)
                .withPredefinedPath(preferences.getString(getKey(), defaultLocation))
                .allowCustomPath(true)
                .allowAddFolder(true)
                .setType(com.codekidlabs.storagechooser.StorageChooser.DIRECTORY_CHOOSER)
                .build();

        chooser.setOnSelectListener(this::selectPath);
        chooser.show();
    }

    private void selectPath(String path) {
        preferences.edit().putString(getKey(), path).apply();
        setSummary(path);
    }
}

package com.dbzburstlimit.recompiled;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * First screen: gets the game files onto the device (extracts the Xbox 360 ISO
 * or copies an extracted folder into app storage), checks them, and starts
 * the game.
 */
public class LauncherActivity extends Activity {
    static final String PREFS = "launcher";
    static final String PREF_TOUCH_CONTROLS = "touch_controls";
    static final String PREF_TOUCH_OPACITY = "touch_opacity";
    static final String PREF_EXTRA_ARGS = "extra_args";

    private static final int REQUEST_ISO = 1;
    private static final int REQUEST_FOLDER = 2;
    private static final long SPACE_MARGIN = 64L << 20;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private SharedPreferences prefs;
    private TextView statusText;
    private TextView progressText;
    private ProgressBar progressBar;
    private Button isoButton;
    private Button folderButton;
    private Button playButton;
    private Button cancelButton;
    private boolean busy;
    private GameFiles.Status status = GameFiles.Status.MISSING;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildLayout());
        refreshStatus();
    }

    private View buildLayout() {
        int pad = dp(16);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        logo.setMaxHeight(dp(140));
        column.addView(logo);

        TextView subtitle = text(getString(R.string.subtitle), 14);
        subtitle.setGravity(Gravity.CENTER);
        column.addView(subtitle);

        statusText = text(getString(R.string.status_checking), 16);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(12), 0, dp(12));
        column.addView(statusText);

        playButton = button(getString(R.string.play), v -> play());
        playButton.setTextSize(20);
        column.addView(playButton);

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        isoButton = button(getString(R.string.pick_iso), v -> pickIso());
        folderButton = button(getString(R.string.pick_folder), v -> pickFolder());
        pickRow.addView(isoButton, new LinearLayout.LayoutParams(0, -2, 1));
        pickRow.addView(folderButton, new LinearLayout.LayoutParams(0, -2, 1));
        column.addView(pickRow);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setVisibility(View.GONE);
        column.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));
        progressText = text("", 13);
        progressText.setVisibility(View.GONE);
        column.addView(progressText);
        cancelButton = button(getString(R.string.cancel), v -> cancel.set(true));
        cancelButton.setVisibility(View.GONE);
        column.addView(cancelButton);

        CheckBox touch = new CheckBox(this);
        touch.setText(R.string.touch_controls);
        touch.setChecked(prefs.getBoolean(PREF_TOUCH_CONTROLS, true));
        touch.setOnCheckedChangeListener(
            (b, checked) -> prefs.edit().putBoolean(PREF_TOUCH_CONTROLS, checked).apply());
        column.addView(touch);

        column.addView(text(getString(R.string.touch_opacity), 14));
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(100);
        opacity.setProgress(prefs.getInt(PREF_TOUCH_OPACITY, 55));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                prefs.edit().putInt(PREF_TOUCH_OPACITY, Math.max(10, value)).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        column.addView(opacity, new LinearLayout.LayoutParams(-1, -2));

        column.addView(text(getString(R.string.extra_args), 14));
        EditText extraArgs = new EditText(this);
        extraArgs.setSingleLine(true);
        extraArgs.setText(prefs.getString(PREF_EXTRA_ARGS, ""));
        extraArgs.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                prefs.edit().putString(PREF_EXTRA_ARGS, extraArgs.getText().toString()).apply();
            }
        });
        extraArgs.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(PREF_EXTRA_ARGS, s.toString()).apply();
            }
        });
        column.addView(extraArgs, new LinearLayout.LayoutParams(-1, -2));

        TextView storage = text(getString(R.string.storage_info,
            GameFiles.gameDir(this).getAbsolutePath(),
            GameFiles.userDir(this).getAbsolutePath()), 12);
        storage.setPadding(0, dp(12), 0, 0);
        storage.setTextIsSelectable(true);
        column.addView(storage);

        if (!hasVulkan11()) {
            TextView warning = text(getString(R.string.no_vulkan), 13);
            warning.setTextColor(Color.rgb(255, 170, 60));
            column.addView(warning);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        return scroll;
    }

    private void refreshStatus() {
        statusText.setText(R.string.status_checking);
        playButton.setEnabled(false);
        File gameDir = GameFiles.gameDir(this);
        new Thread(() -> {
            GameFiles.Status result = GameFiles.check(gameDir);
            ui.post(() -> {
                status = result;
                switch (result) {
                    case OK:
                        statusText.setText(R.string.status_ok);
                        break;
                    case WRONG_VERSION:
                        statusText.setText(R.string.status_wrong_version);
                        break;
                    case INCOMPLETE:
                        statusText.setText(R.string.status_incomplete);
                        break;
                    default:
                        statusText.setText(R.string.status_missing);
                        break;
                }
                updateButtons();
            });
        }, "check-game-files").start();
    }

    private void updateButtons() {
        playButton.setEnabled(!busy && status == GameFiles.Status.OK);
        isoButton.setEnabled(!busy);
        folderButton.setEnabled(!busy);
        int busyVisibility = busy ? View.VISIBLE : View.GONE;
        progressBar.setVisibility(busyVisibility);
        cancelButton.setVisibility(busyVisibility);
        if (busy) {
            progressText.setVisibility(View.VISIBLE);
        }
    }

    private void play() {
        startActivity(new Intent(this, GameActivity.class));
    }

    private void pickIso() {
        confirmReplace(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_ISO);
        });
    }

    private void pickFolder() {
        confirmReplace(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_FOLDER);
        });
    }

    private void confirmReplace(Runnable then) {
        if (!GameFiles.gameDir(this).exists()) {
            then.run();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.replace_title)
            .setMessage(R.string.replace_message)
            .setPositiveButton(R.string.yes, (d, w) -> then.run())
            .setNegativeButton(R.string.no, null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_ISO) {
            runJob(() -> importIso(uri));
        } else if (requestCode == REQUEST_FOLDER) {
            runJob(() -> importFolder(uri));
        }
    }

    private interface Job {
        void run() throws IOException;
    }

    private void runJob(Job job) {
        busy = true;
        cancel.set(false);
        progressBar.setProgress(0);
        progressText.setText("");
        updateButtons();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String message;
            try {
                job.run();
                message = getString(R.string.done);
            } catch (XisoExtractor.CancelledException e) {
                message = getString(R.string.cancelled);
            } catch (UserFacingException e) {
                message = e.getMessage();
            } catch (IOException | RuntimeException e) {
                message = getString(R.string.error, e.getMessage());
            }
            final String finalMessage = message;
            ui.post(() -> {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                progressText.setText(finalMessage);
                updateButtons();
                refreshStatus();
            });
        }, "import-game").start();
    }

    /** An error whose message is already meant for the player. */
    private static final class UserFacingException extends IOException {
        UserFacingException(String message) {
            super(message);
        }
    }

    private void importIso(Uri uri) throws IOException {
        setProgressMessage(getString(R.string.reading_iso));
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                throw new IOException("Can't open the file");
            }
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 FileChannel channel = in.getChannel()) {
                XisoExtractor iso = new XisoExtractor(channel);
                if (!iso.open()) {
                    throw new UserFacingException(getString(R.string.not_xbox_image));
                }
                List<XisoExtractor.Entry> entries = iso.list();
                XisoExtractor.Entry xex = null;
                for (XisoExtractor.Entry entry : entries) {
                    if (!entry.directory && entry.path.equalsIgnoreCase("default.xex")) {
                        xex = entry;
                    }
                }
                if (xex == null) {
                    throw new UserFacingException(getString(R.string.no_xex_in_image));
                }

                // The version check first, from default.xex alone, before
                // writing gigabytes.
                setProgressMessage(getString(R.string.checking_xex));
                File checkDir = new File(getCacheDir(), "xex_check");
                GameFiles.deleteRecursively(checkDir);
                iso.extract(Collections.singletonList(xex), checkDir, quietProgress());
                String sha1 = GameFiles.sha1(new File(checkDir, xex.path));
                GameFiles.deleteRecursively(checkDir);
                if (!GameFiles.US_XEX_SHA1.equals(sha1)) {
                    throw new UserFacingException(getString(R.string.status_wrong_version));
                }

                File destination = prepareDestination(XisoExtractor.totalSize(entries));
                iso.extract(entries, destination, progress(R.string.extracting));
                finishDestination(destination);
            }
        }
    }

    private void importFolder(Uri treeUri) throws IOException {
        setProgressMessage(getString(R.string.reading_folder));
        FolderImporter folder = new FolderImporter(getContentResolver(), treeUri);
        if (!folder.scan()) {
            throw new UserFacingException(getString(R.string.no_xex_in_folder));
        }
        File destination = prepareDestination(folder.totalSize());
        folder.copy(destination, progress(R.string.copying));
        finishDestination(destination);
    }

    /** Clears the old files, checks the free space and returns the folder to fill. */
    private File prepareDestination(long needed) throws IOException {
        File gameDir = GameFiles.gameDir(this);
        File partial = new File(gameDir.getParentFile(), "game_data_root.partial");
        GameFiles.deleteRecursively(partial);
        GameFiles.deleteRecursively(gameDir);
        File base = GameFiles.baseDir(this);
        long free = base.getUsableSpace();
        if (free < needed + SPACE_MARGIN) {
            throw new UserFacingException(getString(R.string.not_enough_space,
                Formatter.formatShortFileSize(this, needed),
                Formatter.formatShortFileSize(this, free)));
        }
        if (!partial.mkdirs()) {
            throw new IOException("Can't create " + partial);
        }
        return partial;
    }

    private void finishDestination(File partial) throws IOException {
        if (!partial.renameTo(GameFiles.gameDir(this))) {
            throw new IOException("Can't rename " + partial);
        }
    }

    private XisoExtractor.Progress progress(int formatRes) {
        return new XisoExtractor.Progress() {
            private long lastUpdate;

            @Override
            public void onProgress(long done, long total, String path) {
                long now = SystemClock.uptimeMillis();
                if (now - lastUpdate < 100 && done < total) {
                    return;
                }
                lastUpdate = now;
                int permille = total > 0 ? (int) (done * 1000 / total) : 0;
                String message = getString(formatRes,
                    Formatter.formatShortFileSize(LauncherActivity.this, done),
                    Formatter.formatShortFileSize(LauncherActivity.this, total), path);
                ui.post(() -> {
                    progressBar.setProgress(permille);
                    progressText.setText(message);
                });
            }

            @Override
            public boolean isCancelled() {
                return cancel.get();
            }
        };
    }

    private XisoExtractor.Progress quietProgress() {
        return new XisoExtractor.Progress() {
            @Override
            public void onProgress(long done, long total, String path) {}

            @Override
            public boolean isCancelled() {
                return cancel.get();
            }
        };
    }

    private void setProgressMessage(String message) {
        ui.post(() -> progressText.setText(message));
    }

    private boolean hasVulkan11() {
        return getPackageManager().hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000);
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

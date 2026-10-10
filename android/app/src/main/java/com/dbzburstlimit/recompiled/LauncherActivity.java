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
    static final String PREF_LAST_PLAY = "last_play";

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
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(12), dp(20), dp(24));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        logo.setMaxHeight(dp(120));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(-2, -2);
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(logo, logoParams);

        TextView subtitle = text(getString(R.string.subtitle), 13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setAlpha(0.7f);
        column.addView(subtitle, margins(-1, dp(8)));

        // Game files: status, Play, and where they come from.
        LinearLayout game = card();
        statusText = text(getString(R.string.status_checking), 16);
        statusText.setGravity(Gravity.CENTER);
        game.addView(statusText);

        playButton = button(getString(R.string.play), v -> play());
        playButton.setTextSize(20);
        playButton.setMinHeight(dp(56));
        game.addView(playButton, margins(-1, dp(16)));

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        isoButton = button(getString(R.string.pick_iso), v -> pickIso());
        folderButton = button(getString(R.string.pick_folder), v -> pickFolder());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.setMarginEnd(dp(6));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.setMarginStart(dp(6));
        pickRow.addView(isoButton, left);
        pickRow.addView(folderButton, right);
        game.addView(pickRow, margins(-1, dp(10)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setVisibility(View.GONE);
        game.addView(progressBar, margins(-1, dp(16)));
        progressText = text("", 13);
        progressText.setVisibility(View.GONE);
        game.addView(progressText, margins(-1, dp(6)));
        cancelButton = button(getString(R.string.cancel), v -> cancel.set(true));
        cancelButton.setVisibility(View.GONE);
        game.addView(cancelButton, margins(-1, dp(8)));
        column.addView(game, margins(-1, dp(20)));

        // On-screen controller.
        LinearLayout controls = card();
        controls.addView(heading(getString(R.string.section_controls)));
        CheckBox touch = new CheckBox(this);
        touch.setText(R.string.touch_controls);
        touch.setChecked(prefs.getBoolean(PREF_TOUCH_CONTROLS, true));
        touch.setOnCheckedChangeListener(
            (b, checked) -> prefs.edit().putBoolean(PREF_TOUCH_CONTROLS, checked).apply());
        controls.addView(touch, margins(-1, dp(8)));

        controls.addView(text(getString(R.string.touch_opacity), 14), margins(-1, dp(14)));
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
        controls.addView(opacity, margins(-1, dp(6)));
        column.addView(controls, margins(-1, dp(16)));

        // Advanced: extra settings as command-line options.
        LinearLayout advanced = card();
        advanced.addView(heading(getString(R.string.section_advanced)));
        TextView extraLabel = text(getString(R.string.extra_args), 13);
        extraLabel.setAlpha(0.7f);
        advanced.addView(extraLabel, margins(-1, dp(8)));
        EditText extraArgs = new EditText(this);
        extraArgs.setSingleLine(true);
        extraArgs.setText(prefs.getString(PREF_EXTRA_ARGS, ""));
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
        advanced.addView(extraArgs, margins(-1, dp(4)));
        column.addView(advanced, margins(-1, dp(16)));

        // Files and the log.
        LinearLayout files = card();
        files.addView(heading(getString(R.string.section_files)));
        TextView storage = text(getString(R.string.storage_info,
            GameFiles.gameDir(this).getAbsolutePath(),
            GameFiles.userDir(this).getAbsolutePath()), 12);
        storage.setAlpha(0.7f);
        storage.setTextIsSelectable(true);
        files.addView(storage, margins(-1, dp(8)));
        files.addView(button(getString(R.string.view_log), v -> showLog()), margins(-1, dp(12)));
        if (!hasVulkan11()) {
            TextView warning = text(getString(R.string.no_vulkan), 13);
            warning.setTextColor(Color.rgb(255, 170, 60));
            files.addView(warning, margins(-1, dp(12)));
        }
        column.addView(files, margins(-1, dp(16)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        // Android 15 draws the app under the status and navigation bars:
        // keep the content clear of them.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        return scroll;
    }

    /** A digest of burstlimit.log, to read, copy or share (Android/data isn't reachable from most file managers). */
    private void showLog() {
        new Thread(() -> {
            File log = new File(GameFiles.userDir(this), "burstlimit.log");
            String content;
            try {
                content = digest(log);
            } catch (IOException e) {
                content = "";
            }
            if (content.isEmpty()) {
                content = getString(R.string.log_empty, log.getAbsolutePath()) + "\n";
            }
            // This app's own logcat (the game process shares its uid): SDL,
            // Vulkan driver and native crash messages that never reach the file.
            String logcat = readLogcat(prefs.getLong(PREF_LAST_PLAY, 0), 24 * 1024);
            String text = content + (logcat.isEmpty() ? "" : "\n--- logcat ---\n" + logcat);
            ui.post(() -> showLogDialog(text));
        }, "read-log").start();
    }

    private static String readLogcat(long since, int maxChars) {
        try {
            // Warnings and errors from everything (Vulkan driver, crashes), SDL's
            // own messages; the runtime's lines are in the file already. Only
            // from the last time "Play" was pressed on.
            java.util.List<String> command = new java.util.ArrayList<>(
                java.util.Arrays.asList("logcat", "-d", "-v", "time"));
            if (since > 0) {
                command.add("-T");
                command.add(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date(since)));
            } else {
                command.add("-t");
                command.add("20000");
            }
            command.addAll(java.util.Arrays.asList("rexglue:S", "SDL:I", "*:W"));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    data.write(buffer, 0, read);
                }
            }
            process.waitFor();
            String text = data.toString("UTF-8");
            return text.length() > maxChars ? text.substring(text.length() - maxChars) : text;
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    private void showLogDialog(String logText) {

        TextView view = text(logText, 11);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));

        new AlertDialog.Builder(this)
            .setTitle(R.string.view_log)
            .setView(scroll)
            .setPositiveButton(R.string.copy, (d, w) -> {
                android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("burstlimit.log", logText));
                android.widget.Toast.makeText(this, R.string.log_copied, android.widget.Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton(R.string.share, (d, w) -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "burstlimit.log");
                send.putExtra(Intent.EXTRA_TEXT, logText);
                startActivity(Intent.createChooser(send, getString(R.string.share)));
            })
            .setNegativeButton(R.string.close, null)
            .show();
    }

    /**
     * Startup (device, Vulkan features, warnings), the warnings and errors seen
     * since with how often each came up, and the end of the log. Per-frame
     * present lines are left out and runs of the same message are collapsed.
     */
    private static String digest(File file) throws IOException {
        if (!file.isFile()) {
            return "";
        }
        final int headLines = 400;
        final int tailLines = 300;
        StringBuilder head = new StringBuilder();
        java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        java.util.LinkedHashMap<String, Integer> repeated = new java.util.LinkedHashMap<>();
        int lineCount = 0;
        String previous = null;
        int previousRepeats = 0;
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("XELOG_GPU PRESENT")) {
                    continue;
                }
                // "[date time] [level] [category] [tid] message" -> "[level] [category] message".
                String message = line.replaceFirst("^\\[[^\\]]*\\] (\\[[^\\]]*\\] \\[[^\\]]*\\]) \\[t\\d+\\]", "$1");
                if (message.startsWith("[warning]") || message.startsWith("[error]")
                        || message.startsWith("[critical]")) {
                    Integer count = repeated.get(message);
                    if (count != null || repeated.size() < 200) {
                        repeated.put(message, count == null ? 1 : count + 1);
                    }
                }
                if (lineCount < headLines) {
                    head.append(line).append('\n');
                    lineCount++;
                    continue;
                }
                if (message.equals(previous)) {
                    previousRepeats++;
                    continue;
                }
                if (previousRepeats > 0) {
                    tail.addLast("    (same line " + previousRepeats + " more times)");
                }
                previous = message;
                previousRepeats = 0;
                tail.addLast(line);
                while (tail.size() > tailLines) {
                    tail.removeFirst();
                }
            }
        }
        if (previousRepeats > 0) {
            tail.addLast("    (same line " + previousRepeats + " more times)");
        }
        StringBuilder out = new StringBuilder(head);
        out.append("\n--- warnings/errors (count) ---\n");
        for (java.util.Map.Entry<String, Integer> entry : repeated.entrySet()) {
            out.append(entry.getValue()).append("x ").append(entry.getKey()).append('\n');
        }
        out.append("\n--- end of log ---\n");
        for (String line : tail) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.argb(20, 255, 255, 255));
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        return card;
    }

    private TextView heading(String value) {
        TextView view = text(value, 16);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private LinearLayout.LayoutParams margins(int width, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, -2);
        params.topMargin = top;
        return params;
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
        // Each run starts a fresh log, so "View log" only shows this run.
        new File(GameFiles.userDir(this), "burstlimit.log").delete();
        prefs.edit().putLong(PREF_LAST_PLAY, System.currentTimeMillis()).commit();
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

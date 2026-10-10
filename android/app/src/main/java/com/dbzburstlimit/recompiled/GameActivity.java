package com.dbzburstlimit.recompiled;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the game: SDL loads libmain.so and calls SDL_main with the arguments
 * below. Lives in its own process (":game"), since the runtime ends the
 * process when the game quits.
 */
public class GameActivity extends SDLActivity {
    private TouchControllerView touchController;

    @Override
    protected String[] getLibraries() {
        // SDL itself is inside librexruntime.so; the GPU plugin is loaded by
        // the runtime from the native library folder.
        return new String[] {"c++_shared", "rexruntime", "main"};
    }

    @Override
    protected String[] getArguments() {
        File gameDir = GameFiles.gameDir(this);
        File userDir = GameFiles.userDir(this);
        File texturesDir = GameFiles.texturesDir(this);
        //noinspection ResultOfMethodCallIgnored
        userDir.mkdirs();
        List<String> args = new ArrayList<>();
        args.add("--game_data_root=" + gameDir.getAbsolutePath());
        args.add("--user_data_root=" + userDir.getAbsolutePath());
        args.add("--texture_folder=" + texturesDir.getAbsolutePath());
        args.add("--log_file=" + new File(userDir, "burstlimit.log").getAbsolutePath());
        // Android kills the process when it is swiped away: write the log out
        // every second so the last lines aren't lost with it.
        args.add("--log_flush_interval=1");
        args.add("--fullscreen=true");
        String extra = getSharedPreferences(LauncherActivity.PREFS, MODE_PRIVATE)
            .getString(LauncherActivity.PREF_EXTRA_ARGS, "");
        for (String arg : extra.trim().split("\\s+")) {
            if (!arg.isEmpty()) {
                args.add(arg);
            }
        }
        return args.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Landscape before SDL makes its window, so the game starts with a
        // landscape surface.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        super.onCreate(savedInstanceState);
        if (mBrokenLibraries || mLayout == null) {
            return;  // SDL already shows its error dialog.
        }
        SharedPreferences prefs = getSharedPreferences(LauncherActivity.PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(LauncherActivity.PREF_TOUCH_CONTROLS, true)) {
            touchController = new TouchControllerView(this);
            touchController.setOpacity(prefs.getInt(LauncherActivity.PREF_TOUCH_OPACITY, 55) / 100f);
            mLayout.addView(touchController, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (touchController != null) {
            touchController.startWatchingControllers();
        }
    }

    @Override
    protected void onPause() {
        if (touchController != null) {
            touchController.stopWatchingControllers();
        }
        super.onPause();
    }

    // SDL asks for an orientation from the window size and its hint (portrait
    // when the window starts out taller than wide): the game is landscape only.
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }
}

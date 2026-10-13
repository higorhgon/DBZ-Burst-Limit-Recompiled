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
        // Render passes instead of dynamic rendering: the DBZBL-Android-LAN
        // port got black frames after the title with dynamic rendering (Mali).
        // An extra setting --vulkan_dynamic_rendering=true below still wins.
        args.add("--vulkan_dynamic_rendering=false");
        // Tiled-GPU settings the XenDroid port (Xenia Edge on Android, 30 FPS in
        // this game on the same phone) runs with: estimate unclipped draws on
        // the CPU so screen-space draws don't claim the whole EDRAM (spurious
        // render target ownership transfers), and keep geometry shaders out of
        // Adreno's binning pass (point sprites / rectangles expanded in the
        // vertex shader, quads converted on the CPU).
        args.add("--execute_unclipped_draw_vs_on_cpu=true");
        args.add("--vulkan_force_expand_point_sprites_in_vs=true");
        args.add("--vulkan_force_expand_rectangle_lists_in_vs=true");
        args.add("--vulkan_force_convert_quad_lists_to_triangle_lists=true");
        if (loadVulkanDriver()) {
            // Turnip reports sparse binding, but the shared memory buffer bound
            // that way read back as zeros here: a black picture while the game
            // ran. RB2-Android also always turns it off.
            args.add("--vulkan_sparse_shared_memory=false");
        }
        String extra = getSharedPreferences(LauncherActivity.PREFS, MODE_PRIVATE)
            .getString(LauncherActivity.PREF_EXTRA_ARGS, "");
        for (String arg : extra.trim().split("\\s+")) {
            if (!arg.isEmpty()) {
                args.add(arg);
            }
        }
        return args.toArray(new String[0]);
    }

    private static final String TURNIP_LIB = "vulkan.ad07xx.so";

    /** Loads Mesa Turnip in place of the phone's Vulkan driver; returns what is used. */
    private static native String nativeLoadVulkanDriver(String driverDir, String library, String hookDir);

    /**
     * Before SDL_main (getArguments runs on SDL's main thread): the bundled
     * Mesa Turnip driver in place of the phone's, when the launcher asks for
     * it. True when Turnip is in use.
     */
    private boolean loadVulkanDriver() {
        SharedPreferences prefs = getSharedPreferences(LauncherActivity.PREFS, MODE_PRIVATE);
        String choice = prefs.getString(LauncherActivity.PREF_GPU_DRIVER, "auto");
        // Automatic = the phone's driver: on the Adreno 740 it ran the fights
        // faster than the bundled Turnip (21 vs 16 FPS, both GPU-bound).
        if (!choice.equals("turnip") && !choice.equals("custom")) {
            android.util.Log.w("burstlimit-driver", "Vulkan driver: system driver (" + choice + ")");
            return false;
        }
        try {
            File dir;
            String library;
            if (choice.equals("custom")) {
                // Imported in the launcher (DriverImport).
                dir = DriverImport.dir(this);
                library = prefs.getString(DriverImport.PREF_CUSTOM_DRIVER_LIB, "");
                if (library.isEmpty() || !new File(dir, library).isFile()) {
                    android.util.Log.w("burstlimit-driver", "Vulkan driver: system driver (imported driver missing)");
                    warnDriverFallback("imported driver missing");
                    return false;
                }
            } else {
                dir = new File(getFilesDir(), "drivers/turnip");
                library = TURNIP_LIB;
                if (!copyDriverAsset("drivers/turnip/" + TURNIP_LIB, new File(dir, TURNIP_LIB))) {
                    android.util.Log.w("burstlimit-driver", "Vulkan driver: system driver (no Turnip in this APK)");
                    return false;
                }
            }
            if (prefs.getBoolean(LauncherActivity.PREF_TURNIP_SYSMEM, true)) {
                // Untiled rendering on Turnip, as XenDroid defaults to: with many
                // render pass breaks / resolves on 4x MSAA targets it beats GMEM.
                android.system.Os.setenv("TU_DEBUG", "sysmem", true);
            }
            String status = nativeLoadVulkanDriver(dir.getAbsolutePath(), library,
                getApplicationInfo().nativeLibraryDir);
            if (status.startsWith("Turnip")) {
                return true;
            }
            warnDriverFallback(status);
            return false;
        } catch (Throwable t) {
            android.util.Log.w("burstlimit-driver", "Vulkan driver: system driver (" + t + ")", t);
            warnDriverFallback(t.toString());
            return false;
        }
    }

    /** The chosen driver didn't load: say so, the game runs on the phone's. */
    private void warnDriverFallback(String why) {
        runOnUiThread(() -> android.widget.Toast.makeText(this,
            getString(R.string.driver_fallback, why), android.widget.Toast.LENGTH_LONG).show());
    }

    /** Copies a bundled driver out of the APK (a library can only be opened from a file); false if absent. */
    private boolean copyDriverAsset(String asset, File dest) throws java.io.IOException {
        long size;
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd(asset)) {
            size = fd.getLength();
        } catch (java.io.FileNotFoundException e) {
            return false;
        }
        if (dest.isFile() && dest.length() == size) {
            return true;
        }
        //noinspection ResultOfMethodCallIgnored
        dest.getParentFile().mkdirs();
        File part = new File(dest.getPath() + ".part");
        try (java.io.InputStream in = getAssets().open(asset);
             java.io.OutputStream out = new java.io.FileOutputStream(part)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
        if (!part.renameTo(dest)) {
            throw new java.io.IOException("cannot write " + dest);
        }
        return true;
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

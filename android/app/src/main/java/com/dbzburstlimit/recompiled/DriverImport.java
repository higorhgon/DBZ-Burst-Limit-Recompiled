package com.dbzburstlimit.recompiled;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs a Vulkan driver package from the player's storage: a zip in the
 * AdrenoTools / Turnip format (a driver .so plus an optional meta.json with
 * "name" and "libraryName"), as the Xenia and RB2-Android ports take them.
 * Only the .so files and meta.json are written, flattened into
 * files/drivers/custom/ (a library can only be loaded from internal storage).
 */
final class DriverImport {
    static final String PREF_CUSTOM_DRIVER_NAME = "custom_driver_name";
    static final String PREF_CUSTOM_DRIVER_LIB = "custom_driver_lib";
    private static final long MAX_ENTRY_BYTES = 128L << 20;

    private DriverImport() {}

    static File dir(Context context) {
        return new File(context.getFilesDir(), "drivers/custom");
    }

    /** Returns the driver's name; throws with a message for the player when the zip isn't one. */
    static String install(Context context, Uri uri) throws IOException {
        File dir = dir(context);
        File staging = new File(context.getFilesDir(), "drivers/custom.part");
        deleteTree(staging);
        if (!staging.mkdirs()) {
            throw new IOException("cannot create " + staging);
        }
        String meta = null;
        String firstLibrary = null;
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                boolean library = base.endsWith(".so");
                if (!library && !base.equals("meta.json")) {
                    continue;
                }
                if (base.isEmpty() || base.startsWith(".")) {
                    continue;
                }
                byte[] buffer = new byte[1 << 16];
                long total = 0;
                File out = new File(staging, base);
                StringBuilder text = library ? null : new StringBuilder();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_ENTRY_BYTES) {
                            throw new IOException(base + " is too large for a driver");
                        }
                        os.write(buffer, 0, n);
                        if (text != null) {
                            text.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                }
                if (library && firstLibrary == null) {
                    firstLibrary = base;
                } else if (!library) {
                    meta = text.toString();
                }
            }
        }
        String libraryName = jsonString(meta, "libraryName");
        if (libraryName == null || !new File(staging, libraryName).isFile()) {
            libraryName = firstLibrary;
        }
        if (libraryName == null) {
            deleteTree(staging);
            throw new IOException("no driver library (.so) in this zip");
        }
        String displayName = jsonString(meta, "name");
        if (displayName == null || displayName.isEmpty()) {
            displayName = libraryName;
        }
        deleteTree(dir);
        if (!staging.renameTo(dir)) {
            throw new IOException("cannot install the driver into " + dir);
        }
        context.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_CUSTOM_DRIVER_NAME, displayName)
            .putString(PREF_CUSTOM_DRIVER_LIB, libraryName)
            .putString(LauncherActivity.PREF_GPU_DRIVER, "custom")
            .apply();
        return displayName;
    }

    /** The string value of a top-level key in a small JSON object, or null. */
    private static String jsonString(String json, String key) {
        if (json == null) {
            return null;
        }
        try {
            return new org.json.JSONObject(json).optString(key, null);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}

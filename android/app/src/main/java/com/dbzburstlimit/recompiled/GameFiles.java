package com.dbzburstlimit.recompiled;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Where the game files live on the device, and whether they are the right ones. */
final class GameFiles {
    /** The recompiled code is built from this default.xex (US / NTSC-U). */
    static final String US_XEX_SHA1 = "aec598f88cf51181fc377b148e0b1ad30db4485c";
    static final long US_XEX_SIZE = 7_983_104L;

    /** Files the game can't start without (besides default.xex). */
    static final String[] REQUIRED_FILES = {
        "LONG2DATA/LONG2DATA_US.CPK",
        "LONG2DATA/STREAM_US.CPK",
    };

    enum Status { MISSING, INCOMPLETE, WRONG_VERSION, OK }

    private GameFiles() {}

    /** App-specific storage: no permission needed, reachable over USB (Android/data/...). */
    static File baseDir(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        return dir;
    }

    static File gameDir(Context context) {
        return new File(baseDir(context), "game_data_root");
    }

    static File userDir(Context context) {
        return new File(baseDir(context), "user");
    }

    static File texturesDir(Context context) {
        return new File(baseDir(context), "textures");
    }

    static Status check(File gameDir) {
        File xex = findCaseInsensitive(gameDir, "default.xex");
        if (xex == null) {
            return Status.MISSING;
        }
        for (String path : REQUIRED_FILES) {
            if (findCaseInsensitive(gameDir, path) == null) {
                return Status.INCOMPLETE;
            }
        }
        if (xex.length() != US_XEX_SIZE) {
            return Status.WRONG_VERSION;
        }
        try {
            return US_XEX_SHA1.equals(sha1(xex)) ? Status.OK : Status.WRONG_VERSION;
        } catch (IOException e) {
            return Status.INCOMPLETE;
        }
    }

    /** Resolves a relative path one component at a time, ignoring case. */
    static File findCaseInsensitive(File root, String relativePath) {
        File current = root;
        for (String part : relativePath.split("/")) {
            File exact = new File(current, part);
            if (exact.exists()) {
                current = exact;
                continue;
            }
            String[] names = current.list();
            if (names == null) {
                return null;
            }
            File match = null;
            for (String name : names) {
                if (name.equalsIgnoreCase(part)) {
                    match = new File(current, name);
                    break;
                }
            }
            if (match == null) {
                return null;
            }
            current = match;
        }
        return current;
    }

    static String sha1(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}

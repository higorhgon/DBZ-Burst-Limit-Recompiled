package com.dbzburstlimit.recompiled;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Copies an already extracted game folder (picked with the system file picker). */
final class FolderImporter {
    private static final class Item {
        final Uri uri;
        final String path;
        final long size;
        final boolean directory;

        Item(Uri uri, String path, long size, boolean directory) {
            this.uri = uri;
            this.path = path;
            this.size = size;
            this.directory = directory;
        }
    }

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final List<Item> items = new ArrayList<>();
    private long totalSize;

    FolderImporter(ContentResolver resolver, Uri treeUri) {
        this.resolver = resolver;
        this.treeUri = treeUri;
    }

    /** Lists the folder. False when it has no default.xex at its top. */
    boolean scan() throws IOException {
        items.clear();
        totalSize = 0;
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        scanFolder(rootId, "");
        for (Item item : items) {
            if (!item.directory && item.path.equalsIgnoreCase("default.xex")) {
                return true;
            }
        }
        return false;
    }

    long totalSize() {
        return totalSize;
    }

    void copy(File destination, XisoExtractor.Progress progress) throws IOException {
        long done = 0;
        byte[] buffer = new byte[1 << 20];
        for (Item item : items) {
            File target = new File(destination, item.path);
            if (item.directory) {
                if (!target.isDirectory() && !target.mkdirs()) {
                    throw new IOException("Can't create " + target);
                }
                continue;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Can't create " + parent);
            }
            try (InputStream in = resolver.openInputStream(item.uri);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) {
                    throw new IOException("Can't open " + item.path);
                }
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (progress.isCancelled()) {
                        throw new XisoExtractor.CancelledException();
                    }
                    out.write(buffer, 0, read);
                    done += read;
                    progress.onProgress(done, totalSize, item.path);
                }
            }
        }
    }

    private void scanFolder(String documentId, String path) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        };
        List<String[]> folders = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Can't list " + (path.isEmpty() ? "the folder" : path));
            }
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                if (name == null || name.contains("/") || name.equals("..") || name.equals(".")) {
                    continue;
                }
                String childPath = path.isEmpty() ? name : path + "/" + name;
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                items.add(new Item(uri, childPath, size, directory));
                if (directory) {
                    folders.add(new String[] {id, childPath});
                } else {
                    totalSize += size;
                }
            }
        }
        for (String[] folder : folders) {
            scanFolder(folder[0], folder[1]);
        }
    }
}

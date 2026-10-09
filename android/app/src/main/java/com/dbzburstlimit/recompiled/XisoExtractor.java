package com.dbzburstlimit.recompiled;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts the game partition of an Xbox 360 disc image (XDVDFS), keeping the
 * whole tree (default.xex, LONG2DATA/...), like extract-xiso.
 *
 * Works on full disc images (redump: the game partition starts at an offset
 * that depends on the disc type) and on images already trimmed to the game
 * partition (extract-xiso -r, "XISO").
 */
final class XisoExtractor {
    interface Progress {
        /** done / total bytes, and the file being written. */
        void onProgress(long done, long total, String path);

        boolean isCancelled();
    }

    static final class Entry {
        final String path;
        final long sector;
        final long size;
        final boolean directory;

        Entry(String path, long sector, long size, boolean directory) {
            this.path = path;
            this.sector = sector;
            this.size = size;
            this.directory = directory;
        }
    }

    static final class CancelledException extends IOException {
        CancelledException() {
            super("cancelled");
        }
    }

    private static final int SECTOR_SIZE = 2048;
    private static final long VOLUME_DESCRIPTOR_SECTOR = 32;
    private static final byte[] MAGIC =
        "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    // Game partition start: trimmed XISO, XGD2, XGD3, XGD1 (original Xbox).
    private static final long[] PARTITION_OFFSETS = {0L, 0xFD90000L, 0x2080000L, 0x18300000L};
    private static final int ATTRIBUTE_DIRECTORY = 0x10;

    private final FileChannel channel;
    private long partitionOffset = -1;
    private long rootSector;
    private long rootSize;

    XisoExtractor(FileChannel channel) {
        this.channel = channel;
    }

    /** Finds the game partition. False when this isn't an Xbox / Xbox 360 disc image. */
    boolean open() throws IOException {
        for (long offset : PARTITION_OFFSETS) {
            ByteBuffer descriptor = read(offset + VOLUME_DESCRIPTOR_SECTOR * SECTOR_SIZE, 28);
            if (descriptor == null) {
                continue;
            }
            byte[] magic = new byte[MAGIC.length];
            descriptor.get(magic);
            if (java.util.Arrays.equals(magic, MAGIC)) {
                partitionOffset = offset;
                rootSector = descriptor.getInt() & 0xFFFFFFFFL;
                rootSize = descriptor.getInt() & 0xFFFFFFFFL;
                return true;
            }
        }
        return false;
    }

    /** Every file and folder of the game partition, folders before their contents. */
    List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        ArrayDeque<Entry> folders = new ArrayDeque<>();
        folders.add(new Entry("", rootSector, rootSize, true));
        Set<Long> visitedTables = new HashSet<>();
        while (!folders.isEmpty()) {
            Entry folder = folders.poll();
            if (folder.size == 0 || !visitedTables.add(folder.sector)) {
                continue;
            }
            ByteBuffer table = read(partitionOffset + folder.sector * SECTOR_SIZE, (int) folder.size);
            if (table == null) {
                throw new IOException("Directory table past the end of the image: " + folder.path);
            }
            // The entries form a binary tree; offsets are in 4-byte units.
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            Set<Integer> seen = new HashSet<>();
            pending.add(0);
            while (!pending.isEmpty()) {
                int offset = pending.pop();
                if (offset + 14 > table.capacity() || !seen.add(offset)) {
                    continue;
                }
                int left = table.getShort(offset) & 0xFFFF;
                int right = table.getShort(offset + 2) & 0xFFFF;
                if (left == 0xFFFF && right == 0xFFFF) {
                    continue;  // Padding: an empty folder.
                }
                long sector = table.getInt(offset + 4) & 0xFFFFFFFFL;
                long size = table.getInt(offset + 8) & 0xFFFFFFFFL;
                int attributes = table.get(offset + 12) & 0xFF;
                int nameLength = table.get(offset + 13) & 0xFF;
                if (offset + 14 + nameLength > table.capacity()) {
                    continue;
                }
                byte[] nameBytes = new byte[nameLength];
                for (int i = 0; i < nameLength; i++) {
                    nameBytes[i] = table.get(offset + 14 + i);
                }
                String name = new String(nameBytes, StandardCharsets.ISO_8859_1);
                if (isSafeName(name)) {
                    String path = folder.path.isEmpty() ? name : folder.path + "/" + name;
                    boolean directory = (attributes & ATTRIBUTE_DIRECTORY) != 0;
                    Entry entry = new Entry(path, sector, size, directory);
                    entries.add(entry);
                    if (directory) {
                        folders.add(entry);
                    }
                }
                if (left != 0 && left != 0xFFFF) {
                    pending.push(left * 4);
                }
                if (right != 0 && right != 0xFFFF) {
                    pending.push(right * 4);
                }
            }
        }
        return entries;
    }

    static long totalSize(List<Entry> entries) {
        long total = 0;
        for (Entry entry : entries) {
            if (!entry.directory) {
                total += entry.size;
            }
        }
        return total;
    }

    void extract(List<Entry> entries, File destination, Progress progress) throws IOException {
        long total = totalSize(entries);
        long done = 0;
        byte[] buffer = new byte[1 << 20];
        ByteBuffer wrapped = ByteBuffer.wrap(buffer);
        for (Entry entry : entries) {
            File target = new File(destination, entry.path);
            if (entry.directory) {
                if (!target.isDirectory() && !target.mkdirs()) {
                    throw new IOException("Can't create " + target);
                }
                continue;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Can't create " + parent);
            }
            long position = partitionOffset + entry.sector * SECTOR_SIZE;
            long remaining = entry.size;
            try (FileOutputStream out = new FileOutputStream(target)) {
                while (remaining > 0) {
                    if (progress.isCancelled()) {
                        throw new CancelledException();
                    }
                    wrapped.clear();
                    wrapped.limit((int) Math.min(buffer.length, remaining));
                    int read = channel.read(wrapped, position);
                    if (read <= 0) {
                        throw new IOException("Unexpected end of the image in " + entry.path);
                    }
                    out.write(buffer, 0, read);
                    position += read;
                    remaining -= read;
                    done += read;
                    progress.onProgress(done, total, entry.path);
                }
            }
        }
    }

    private static boolean isSafeName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
            && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    private ByteBuffer read(long position, int length) throws IOException {
        if (position < 0 || position + length > channel.size()) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read <= 0) {
                return null;
            }
        }
        buffer.flip();
        return buffer;
    }
}

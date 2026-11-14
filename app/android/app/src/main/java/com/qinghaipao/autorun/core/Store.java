package com.qinghaipao.autorun.core;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Crash-safe key/value store backed by files in the app's private dir.
 *
 * <p>Two rules this exists to enforce, both learned from the JS engine losing
 * state in production:
 * <ol>
 *   <li><b>Nothing important lives only in memory.</b> The JS scheduler kept its
 *       task table in a Map; every restart silently dropped pending sign-ins.</li>
 *   <li><b>Writes are atomic.</b> A plain {@code Files.write} interrupted by the
 *       system killing the process leaves a truncated JSON file, and the old
 *       loader responded by discarding <i>all</i> state. Here the payload lands
 *       in a temp file, is fsynced, then renamed over the target — rename is
 *       atomic within a filesystem, so a reader sees either the old or the new
 *       bytes, never half of each.</li>
 * </ol>
 */
public final class Store {

    private final File dir;

    public Store(File dir) {
        this.dir = dir;
        if (!dir.exists() && !dir.mkdirs()) {
            // Degrade to "state not persisted" rather than a crash loop.
            L.w("Autorun/Store", "cannot create " + dir + ", writes will fail");
        }
    }

    public File file(String name) {
        return new File(dir, name);
    }

    /** Returns null when the file is missing or unreadable/corrupt. */
    public JSONObject read(String name) {
        File f = file(name);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            String text = new String(buf, 0, off, StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) return null;
            return new JSONObject(text);
        } catch (Exception e) {
            L.w("Autorun/Store", "unreadable " + name + ": " + e.getMessage());
            return null;
        }
    }

    public void write(String name, JSONObject obj) {
        writeRaw(name, obj == null ? "{}" : obj.toString());
    }

    public synchronized void writeRaw(String name, String json) {
        if (!dir.exists()) dir.mkdirs();
        File target = file(name);
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            L.e("Autorun/Store", "write " + name + " failed", e);
            return;
        }
        if (!tmp.renameTo(target)) {
            // Windows-style rename-over-existing can fail; on Android the target
            // is ours so deleting first is safe (worst case we lose one write).
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!tmp.renameTo(target)) {
                L.e("Autorun/Store", "rename " + name + " failed");
            }
        }
    }
}

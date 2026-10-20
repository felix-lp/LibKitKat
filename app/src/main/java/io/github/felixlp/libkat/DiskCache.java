package io.github.felixlp.libkat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

import io.github.felixlp.libkat.core.Text;

/** Простой файловый кэш: имя файла — md5 ключа, вытеснение самых старых по mtime. */
final class DiskCache {
    private final File dir;
    private final long maxBytes;

    DiskCache(File dir, long maxBytes) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    File get(String key) {
        File f = file(key);
        if (f.isFile() && f.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        return null;
    }

    File put(String key, InputStream in) throws IOException {
        File f = file(key);
        File tmp = new File(dir, f.getName() + "." + Thread.currentThread().getId() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32768];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (!f.isFile()) throw new IOException("Не удалось записать кэш");
        }
        return f;
    }

    void remove(String key) {
        //noinspection ResultOfMethodCallIgnored
        file(key).delete();
    }

    long size() {
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) total += f.length();
        }
        return total;
    }

    void clear() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= maxBytes) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = a.lastModified();
                long y = b.lastModified();
                return x < y ? -1 : (x == y ? 0 : 1);
            }
        });
        for (File f : files) {
            if (total <= maxBytes * 8 / 10) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private File file(String key) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(key.getBytes("UTF-8"));
            return new File(dir, Text.hex(d));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

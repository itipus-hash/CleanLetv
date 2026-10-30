package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The owner's order of the app library catalogue, separate from the automatic priority/label sort.
 * Empty until the first app is moved: while it is empty the catalogue keeps sorting missing shortcuts
 * first and recent installs ahead of the rest. Once an app is moved the whole visible order is written
 * here, so apps installed afterwards lead the list and stay easy to find.
 */
final class LibraryOrder {
    private static final int MAX_APPS = 512;
    private static final int MAX_BYTES = MAX_APPS * 256;

    private LibraryOrder() { }

    private static AtomicFile store(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "library_order.txt"));
    }

    /** Packages in the owner's order; empty while the automatic sort is still in charge. */
    static List<String> load(Context context) {
        try {
            return read(store(context));
        } catch (IOException | RuntimeException e) {
            Log.w("TvHome.LibraryOrder", "Could not read the library order", e);
            return new ArrayList<>();
        }
    }

    private static List<String> read(AtomicFile file) throws IOException {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists())
            return new ArrayList<>();
        try (FileInputStream input = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("Order file too large");
                bytes.write(buffer, 0, count);
            }
            return decode(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    static boolean save(Context context, List<String> order) {
        FileOutputStream output = null;
        try {
            byte[] bytes = encode(order).getBytes(StandardCharsets.UTF_8);
            AtomicFile file = store(context);
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
            return true;
        } catch (IOException | RuntimeException e) {
            if (output != null) store(context).failWrite(output);
            Log.w("TvHome.LibraryOrder", "Could not save the library order", e);
            return false;
        }
    }

    private static String encode(List<String> order) {
        if (order.size() > MAX_APPS) throw new IllegalArgumentException("Too many apps");
        StringBuilder text = new StringBuilder();
        for (String pkg : order) {
            if (!LibraryApps.validPackage(pkg)) throw new IllegalArgumentException("Invalid package");
            text.append(pkg).append('\n');
        }
        return text.toString();
    }

    private static List<String> decode(String text) {
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("Order file too large");
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i < text.length() && text.charAt(i) != '\n') continue;
            if (i == start && i == text.length()) break;
            String pkg = text.substring(start, i);
            if (!LibraryApps.validPackage(pkg) || result.contains(pkg) || result.size() >= MAX_APPS)
                throw new IllegalArgumentException("Invalid order file");
            result.add(pkg);
            start = i + 1;
        }
        return result;
    }
}

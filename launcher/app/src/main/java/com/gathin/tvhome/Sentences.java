package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Random;

/**
 * The sentence pool, bundled in {@code assets/sentences.json}: no network, the whole library ships
 * with the app. {@code dbg/build-sentences.py} builds the file from hitokoto's official
 * sentences-bundle, {@code dbg/filter-sentences.py} trims it by length.
 *
 * <p>The file holds one JSON object per line, which is what makes it cheap to use: the pool is
 * indexed by line offset (about 90 KB of arrays) and only the line that is actually shown is
 * parsed. Building the index costs one pass over 1.7 MB, once per process - the firmware restarts
 * Home often, so this is deliberately the only work done up front.
 *
 * <p>Two ways to pick, and the difference is the point: {@link #daily} is derived from the date, so
 * the desktop shows the same sentence all day and shows it again after a restart; {@link #random}
 * is a fresh draw each time, for the Le-key panel.
 */
final class Sentences {
    /** The categories the bundle ships, in the order the API lists them (dbg/build-sentences.py). */
    static final String[] CATEGORIES = {
            "动画", "漫画", "游戏", "文学", "原创", "来自网络",
            "其他", "影视", "诗词", "网易云", "哲学", "抖机灵"};
    /** Every category ticked: the default, and what an empty mask falls back to. */
    static final int ALL = (1 << 12) - 1;
    private static final String ASSET = "sentences.json";
    /** Fallback bucket for a line whose category cannot be read: "其他". */
    private static final int OTHER_BIT = 1 << 6;

    /** One sentence as the two faces show it. */
    static final class Quote {
        final String sentence, source, author, category;

        Quote(String sentence, String source, String author, String category) {
            this.sentence = sentence;
            this.source = source;
            this.author = author;
            this.category = category;
        }
    }

    private static byte[] data;      // the whole asset, kept for offset-based reads
    private static int[] starts;     // line offsets
    private static int[] lengths;
    private static int[] bits;       // category bit per line (int: the pool has 12 categories,
                                     // and a byte silently drops bits 8-11)
    private static final Random RANDOM = new Random();

    private Sentences() { }

    /** Sentences in the pool, whatever the category filter says. */
    static int size(Context context) {
        ensure(context);
        return starts == null ? 0 : starts.length;
    }

    /**
     * Today's sentence: the same one all day, and the same one after a restart, because it is
     * derived from the date rather than stored. {@code mask} is the set of ticked categories.
     */
    static Quote daily(Context context, LocalDate day, int mask) {
        ensure(context);
        int mask2 = mask & ALL;
        int count = count(mask2);
        if (count == 0) return null;
        // A mixed epoch-day: consecutive days land far apart in the pool, so the sequence does not
        // read as "the next one" - it reads as a draw.
        long mixed = day.toEpochDay() * 0x9E3779B97F4A7C15L;
        int pick = Math.floorMod((int) (mixed ^ (mixed >>> 32)), count);
        return at(nth(mask2, pick));
    }

    /** A fresh draw every call - the Le-key panel opens on a new sentence each time. */
    static Quote random(Context context, int mask) {
        ensure(context);
        int mask2 = mask & ALL;
        int count = count(mask2);
        if (count == 0) return null;
        return at(nth(mask2, RANDOM.nextInt(count)));
    }

    /** How many sentences the mask allows. */
    static int count(Context context, int mask) {
        ensure(context);
        return count(mask & ALL);
    }

    // ---- index -----------------------------------------------------------------------------------

    private static synchronized void ensure(Context context) {
        if (bits != null) return;
        byte[] all = read(context);
        int lines = 0;
        for (int i = 0, from = 0; i <= all.length; i++) {
            if (i == all.length || all[i] == '\n') {
                int start = from, end = i;
                while (start < end && all[start] <= ' ') start++;
                while (end > start && all[end - 1] <= ' ') end--;
                if (end > start && all[start] == '{') lines++;
                from = i + 1;
            }
        }
        int[] lineStarts = new int[lines];
        int[] lineLengths = new int[lines];
        int[] lineBits = new int[lines];
        int kept = 0;
        for (int i = 0, from = 0; i <= all.length; i++) {
            if (i == all.length || all[i] == '\n') {
                int start = from, end = i;
                while (start < end && all[start] <= ' ') start++;
                while (end > start && all[end - 1] <= ' ') end--;
                if (end > start && all[start] == '{') {
                    lineStarts[kept] = start;
                    lineLengths[kept] = end - start;
                    lineBits[kept] = categoryBit(all, start, end);
                    kept++;
                }
                from = i + 1;
            }
        }
        data = all;
        starts = lineStarts;
        lengths = lineLengths;
        bits = lineBits;
    }

    private static byte[] read(Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(2 << 20);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (IOException missing) {
            return new byte[0];
        }
    }

    /** The category bit of one line, read without parsing the object. */
    private static int categoryBit(byte[] all, int start, int end) {
        int at = indexOf(all, start, end, "\"category\"");
        if (at < 0) return OTHER_BIT;
        int i = at + 10;                     // past the key, still on the closing quote
        while (i < end && (all[i] == ' ' || all[i] == ':' || all[i] == '"')) i++;
        int from = i;
        while (i < end && all[i] != '"') i++;
        if (i <= from) return OTHER_BIT;
        String name = new String(all, from, i - from, StandardCharsets.UTF_8);
        for (int c = 0; c < CATEGORIES.length; c++) {
            if (CATEGORIES[c].equals(name)) return 1 << c;
        }
        return OTHER_BIT;
    }

    private static int indexOf(byte[] all, int from, int end, String needle) {
        int last = end - needle.length();
        for (int i = from; i <= last; i++) {
            int k = 0;
            while (k < needle.length() && all[i + k] == needle.charAt(k)) k++;
            if (k == needle.length()) return i;
        }
        return -1;
    }

    private static int count(int mask) {
        int total = 0;
        for (int i = 0; i < bits.length; i++) {
            if ((bits[i] & mask) != 0) total++;
        }
        return total;
    }

    /** The index of the {@code k}-th sentence the mask allows. */
    private static int nth(int mask, int k) {
        int seen = 0;
        for (int i = 0; i < bits.length; i++) {
            if ((bits[i] & mask) == 0) continue;
            if (seen == k) return i;
            seen++;
        }
        return bits.length - 1;
    }

    private static Quote at(int index) {
        if (index < 0 || index >= starts.length) return null;
        String line = new String(data, starts[index], lengths[index], StandardCharsets.UTF_8);
        try {
            JSONObject json = new JSONObject(line);
            return new Quote(json.optString("sentence", ""), json.optString("source", ""),
                    json.optString("author", ""), json.optString("category", ""));
        } catch (JSONException broken) {
            return null;
        }
    }
}

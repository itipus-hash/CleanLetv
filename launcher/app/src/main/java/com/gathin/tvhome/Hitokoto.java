package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;

/**
 * Hands one sentence to the two faces that show it: the box in Home's top right corner and the
 * Le-key panel. Both read the bundled pool in {@link Sentences} - nothing goes over the network.
 *
 * <p>The two faces want different things, so there are two entry points: the desktop asks for
 * {@link #loadDaily} (same sentence all day), the panel for {@link #loadRandom} (new one each
 * open). Both do the work off the main thread because the first call indexes 1.7 MB of asset.
 */
final class Hitokoto {
    /** The category filter, straight from Config.hitokotoCategories. */
    private static final int ALL = Sentences.ALL;

    interface Callback {
        void onSentence(String text, String from, String who);
    }

    /** The desktop's sentence: derived from the date, so it survives a restart unchanged. */
    static void loadDaily(Context context, Handler main, int mask, Callback callback) {
        load(context, main, mask, callback, true);
    }

    /** The panel's sentence: a fresh draw on every open. */
    static void loadRandom(Context context, Handler main, int mask, Callback callback) {
        load(context, main, mask, callback, false);
    }

    private static void load(Context context, Handler main, int mask, Callback callback,
                             boolean daily) {
        Context app = context.getApplicationContext();
        int categories = mask == 0 ? ALL : mask;
        new Thread(() -> {
            Sentences.Quote quote;
            try {
                quote = daily
                        ? Sentences.daily(app, java.time.LocalDate.now(), categories)
                        : Sentences.random(app, categories);
            } catch (RuntimeException brokenAsset) {
                quote = null;
            }
            Sentences.Quote result = quote;
            main.post(() -> {
                if (result == null) callback.onSentence("", "", "");
                else callback.onSentence(result.sentence, result.source, result.author);
            });
        }, "hitokoto").start();
    }

    /** "— 张颜齐《贰拾》", dropping whichever half the answer left empty. */
    static String source(String from, String who) {
        if (from.isEmpty() && who.isEmpty()) return "";
        String work = from.isEmpty() ? "" : "《" + from + "》";
        if (who.isEmpty()) return "— " + work;
        if (work.isEmpty()) return "— " + who;
        return "— " + who + work;
    }

    private Hitokoto() {
    }
}

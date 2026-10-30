package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * The weather line under the clock: {@code 17° 泉州市 · 阴 · 最高 20° 最低 14°}.
 *
 * <p>This is the only part of the launcher that opens a connection - the sentence pool ships in
 * assets and everything else is local - so {@code android.permission.INTERNET} exists for this
 * alone. Data comes from QWeather (和风天气), which needs a key; see {@link #apiKey}.
 *
 * <p>Three calls make a line: the place has to be resolved to coordinates (GeoAPI), then the
 * current sky and today's range are read for those coordinates. Locating is the expensive part,
 * so the resolved place is cached for {@link #PLACE_MS} and only re-resolved when the city setting
 * changes or that window lapses; the IP lookup that backs "where am I" runs inside the same
 * window. The line itself is re-read every {@link #FRESH_MS} and cached on disk, so Home shows the
 * last known sky immediately - even with the network down - and never blocks on the radio.
 *
 * <p>Everything here runs off the main thread; {@link #load} only touches the disk.
 */
final class Weather {
    private static final String TAG = "Weather";
    private static final String FILE_NAME = "weather.json";

    /** Re-read the sky every half hour: QWeather's free tier counts requests, and the line is decoration. */
    private static final long FRESH_MS = 30 * 60 * 1000L;
    /** Re-locate every six hours. A TV does not move, and this keeps the call count down. */
    private static final long PLACE_MS = 6 * 60 * 60 * 1000L;
    /** A line older than this is not worth showing at all. */
    private static final long STALE_MS = 24 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 8000;

    /** A resolved place: what the line names, and where the sky is read from. */
    private static final class Place {
        String name = "";
        double lat, lon;
    }

    /** What the line shows. Everything the UI needs, and nothing it does not. */
    static final class Snapshot {
        String city = "";
        int temp;
        String text = "";
        int code;
        int max, min;
        /** Ultraviolet index and relative humidity, 0..1 as the API answers them. */
        int uv, humidity;        String sunrise = "", sunset = "";
        /** When the sky was read, not when it was drawn. */
        long at;
        Place place;
        long placeAt;
        /** The string the place was resolved from - the city setting, or what the IP lookup said. */
        String query = "";
    }

    interface Callback {
        void onWeather(Snapshot snapshot);
    }

    /**
     * The cached line, or null when there is nothing worth showing. Disk only, so it is safe on the
     * main thread: Home calls it before the first frame and shows whatever the last refresh left.
     */
    static Snapshot load(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return null;
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] bytes = readAll(in);
            if (bytes == null) return null;
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Snapshot snap = new Snapshot();
            snap.city = json.optString("city", "");
            snap.temp = json.optInt("temp");
            snap.text = json.optString("text", "");
            snap.code = json.optInt("code");
            snap.max = json.optInt("max");
            snap.min = json.optInt("min");
            snap.uv = json.optInt("uv", -1);
            snap.humidity = json.optInt("humidity", -1);
            snap.sunrise = json.optString("sunrise", "");
            snap.sunset = json.optString("sunset", "");
            snap.at = json.optLong("at");
            snap.placeAt = json.optLong("placeAt");
            snap.query = json.optString("query", "");
            Place place = new Place();
            place.name = json.optString("placeName", "");
            place.lat = json.optDouble("lat");
            place.lon = json.optDouble("lon");
            snap.place = place;
            return snap;
        } catch (Exception broken) {
            Log.w(TAG, "could not read the cached sky: " + broken.getMessage());
            return null;
        }
    }

    /** True when the line is old enough to be worth re-reading. */
    static boolean stale(Snapshot snap) {
        return snap == null || System.currentTimeMillis() - snap.at > FRESH_MS;
    }

    /** A line this old says more about yesterday than about the sky. */
    static boolean expired(Snapshot snap) {
        return snap == null || System.currentTimeMillis() - snap.at > STALE_MS;
    }

    /**
     * Reads the sky in the background and hands back a snapshot - the freshly built one on success,
     * otherwise the one already on disk (which may be null). Never throws, never blocks the caller.
     *
     * @param city   the city from the settings screen; empty means "locate me by IP"
     * @param apiKey the QWeather key; empty means "do not show a line at all"
     */
    static void refresh(Context context, Handler main, String city, String apiKey, String host,
                        Callback callback) {
        if (apiKey.isEmpty()) {
            Snapshot none = null;
            main.post(() -> callback.onWeather(none));
            return;
        }
        Context app = context.getApplicationContext();
        Snapshot cached = load(app);
        new Thread(() -> {
            Snapshot result = cached;
            try {
                result = fetch(app, city.trim(), apiKey.trim(), host.trim(), cached);
            } catch (RuntimeException | IOException | JSONException broken) {
                // A dead radio must not cost the line it already has.
                Log.w(TAG, "could not read the sky: " + broken.getMessage());
            }
            Snapshot built = result;
            main.post(() -> callback.onWeather(built));
        }, "weather").start();
    }

    // ---- the three calls ---------------------------------------------------------------------

    private static Snapshot fetch(Context app, String city, String key, String host,
                                  Snapshot cached) throws IOException, JSONException {
        tlsContext(app);
        long now = System.currentTimeMillis();
        Place place = null;
        String query = null;
        // The place is only re-resolved when it has never been, when it has gone stale, or when
        // the city setting no longer matches the query it was resolved from - otherwise the IP
        // lookup and the GeoAPI call are two requests a refresh does not need to spend. The query
        // is what is compared, not the answer: "泉州市" resolves to "泉州", and comparing those
        // would re-locate on every single refresh.
        boolean placeFresh = cached != null && cached.place != null
                && now - cached.placeAt < PLACE_MS;
        boolean cityChanged = !city.isEmpty()
                && (cached == null || !city.equals(cached.query));
        if (placeFresh && !cityChanged) {
            place = cached.place;
            query = cached.query;
        }
        if (place == null) {
            query = city.isEmpty() ? ipCity() : city;
            if (query.isEmpty()) {
                Log.w(TAG, "no place to read the sky for");
                return cached;
            }
            place = lookup(query, key, host);
            if (place == null) return cached;
        }

        Snapshot snap = new Snapshot();
        snap.place = place;
        snap.placeAt = now;
        snap.at = now;
        snap.city = place.name;
        snap.query = query;
        Sky sky = current(place, key, host);
        snap.temp = sky.temp;
        snap.text = sky.text;
        snap.code = sky.code;
        snap.uv = sky.uv;
        snap.humidity = sky.humidity;
        Forecast forecast = forecast(place, key, host);
        snap.max = forecast.max;
        snap.min = forecast.min;
        snap.sunrise = forecast.sunrise;
        snap.sunset = forecast.sunset;
        save(app, snap);
        return snap;
    }

    /** The city the TV's own address says it is in, or "" when that cannot be told. */
    private static String ipCity() throws IOException, JSONException {
        String body = get("https://myip.ipip.net");
        int at = body.indexOf("来自于：");
        if (at < 0) at = body.indexOf("来自于:");
        if (at < 0) return "";
        String[] parts = body.substring(at + 4).trim().split("\\s+");
        // "中国 福建 泉州  chinatelecom.com.cn": the country comes first and the carrier last,
        // and the carrier is the only part with a dot in it.
        String city = "";
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].indexOf('.') >= 0) continue;
            city = parts[i];
        }
        return city;
    }

    private static Place lookup(String query, String key, String host) throws IOException, JSONException {
        String url = "https://" + host + "/geo/v2/city/lookup?location="
                + URLEncoder.encode(query, "UTF-8") + "&range=cn&number=1&lang=zh&key=" + key;
        String body = get(url);
        JSONObject json = new JSONObject(body);
        JSONArray list = json.optJSONArray("location");
        if (list == null || list.length() == 0) {
            Log.w(TAG, "no place called " + query);
            return null;
        }
        JSONObject first = list.optJSONObject(0);
        if (first == null) return null;
        Place place = new Place();
        // The name the lookup answers with is what the line names. For a city query that is the
        // city itself; a district only shows up when the query was a coordinate pair.
        place.name = first.optString("name", "");
        if (place.name.isEmpty()) place.name = first.optString("adm2", "");
        place.lat = first.optDouble("lat");
        place.lon = first.optDouble("lon");
        return place;
    }

    // The current sky is two fields out of one response, but a helper can only hand back one
    // thing, so the second rides along in a field. Ugly, and private to this class.
    private static String currentText = "";
    private static String currentCode = "";

    /** The sky right now: how warm it is, what it looks like, and the code that picks the glyph. */
    private static final class Sky {
        int temp;
        String text = "";
        int code;
        /** Ultraviolet index, and relative humidity rounded to whole percent. */
        int uv, humidity;
    }

    /** Today's shape: the range the line shows, plus the sun for the line under it. */
    private static final class Forecast {
        int max, min;
        String sunrise = "", sunset = "";
    }

    private static Sky current(Place place, String key, String host) throws IOException, JSONException {
        String url = "https://" + host + "/weather/v1/current/" + round2(place.lat) + "/"
                + round2(place.lon) + "?lang=zh&key=" + key;
        JSONObject json = new JSONObject(get(url));
        Sky sky = new Sky();
        JSONObject condition = json.optJSONObject("condition");
        if (condition != null) {
            sky.text = condition.optString("text", "");
            sky.code = condition.optInt("code");
        }
        JSONObject temperature = json.optJSONObject("temperature");
        if (temperature != null) sky.temp = Math.round((float) temperature.optDouble("value"));
        sky.uv = json.has("uvIndex") ? json.optInt("uvIndex") : -1;
        // Humidity answers as a 0..1 fraction; the line is a TV screen, so whole percent. A field
        // the answer did not carry is -1, which is what "say nothing" looks like - a real 0 (night,
        // or desert air) is a number worth printing.
        sky.humidity = json.has("humidity")
                ? Math.round((float) json.optDouble("humidity") * 100f) : -1;
        return sky;
    }

    /** Today's high and low with the sun, or zeroes when the forecast does not answer. */
    private static Forecast forecast(Place place, String key, String host)
            throws IOException, JSONException {
        Forecast none = new Forecast();
        String url = "https://" + host + "/weather/v1/daily/" + round2(place.lat) + "/"
                + round2(place.lon) + "?days=1&lang=zh&key=" + key;
        JSONObject json = new JSONObject(get(url));
        JSONArray days = json.optJSONArray("days");
        if (days == null || days.length() == 0) return none;
        JSONObject day = days.optJSONObject(0);
        if (day == null) return none;
        JSONObject high = day.optJSONObject("temperatureMax");
        JSONObject low = day.optJSONObject("temperatureMin");
        none.max = high == null ? 0 : Math.round((float) high.optDouble("value"));
        none.min = low == null ? 0 : Math.round((float) low.optDouble("value"));
        // The sun rides along in the day's astro block, in UTC; the TV's clock is what matters.
        JSONObject astro = day.optJSONObject("astro");
        if (astro != null) {
            none.sunrise = hhmm(astro.optString("sunrise", ""));
            none.sunset = hhmm(astro.optString("sunset", ""));
        }
        return none;
    }

    /** "2026-10-24T22:08Z" in UTC, as the TV's own clock would show it: "06:08". */
    private static String hhmm(String iso) {
        if (iso == null || iso.length() < 16 || !iso.endsWith("Z")) return "";
        try {
            java.text.SimpleDateFormat read =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US);
            read.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.text.SimpleDateFormat write =
                    new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
            write.setTimeZone(java.util.TimeZone.getDefault());
            return write.format(read.parse(iso));
        } catch (java.text.ParseException broken) {
            return "";
        }
    }

    // ---- plumbing ----------------------------------------------------------------------------

    /** The coordinates go in the path, and the API takes at most two decimals. */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * A TLS context that also trusts {@link R.raw#isrg_root_x1}.
     *
     * <p>QWeather signs its per-account hosts with Let's Encrypt, and this set's firmware predates
     * ISRG Root X1 - its CA store simply does not carry it, so the handshake dies with "Trust
     * anchor for certification path not found" against a perfectly valid certificate. The system
     * anchors are still asked first; the bundled root is only reached when they cannot build a
     * path, so a host the set already trusts (the public api.qweather.com, signed by Sectigo)
     * keeps working exactly as before.
     */
    private static SSLContext tls;

    private static synchronized void tlsContext(Context app) {
        if (tls != null) return;
        try {
            TrustManagerFactory system = TrustManagerFactory.getInstance("X509");
            system.init((KeyStore) null);
            X509TrustManager systemTm = (X509TrustManager) system.getTrustManagers()[0];

            KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
            extra.load(null, null);
            try (InputStream in = app.getResources().openRawResource(R.raw.isrg_root_x1)) {
                int index = 0;
                for (Certificate certificate :
                        CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    extra.setCertificateEntry("isrg-" + index++, certificate);
                }
            }
            TrustManagerFactory bundled = TrustManagerFactory.getInstance("X509");
            bundled.init(extra);
            X509TrustManager bundledTm = (X509TrustManager) bundled.getTrustManagers()[0];

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String auth)
                        throws CertificateException {
                    systemTm.checkClientTrusted(chain, auth);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String auth)
                        throws CertificateException {
                    try {
                        systemTm.checkServerTrusted(chain, auth);
                    } catch (CertificateException systemSaidNo) {
                        bundledTm.checkServerTrusted(chain, auth);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return systemTm.getAcceptedIssuers();
                }
            }}, null);
            tls = context;
        } catch (Exception broken) {
            Log.w(TAG, "could not build the TLS context: " + broken.getMessage());
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestMethod("GET");
        // QWeather's per-account hosts are signed by Let's Encrypt, whose root this firmware's
        // CA store predates; without the extra anchor the handshake fails before any byte moves.
        if (connection instanceof HttpsURLConnection && tls != null) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(tls.getSocketFactory());
        }
        try (InputStream raw = connection.getResponseCode() / 100 == 2
                ? connection.getInputStream() : connection.getErrorStream()) {
            if (raw == null) throw new IOException("HTTP " + connection.getResponseCode());
            // QWeather gzips every answer whether the client asked for it or not, and
            // HttpURLConnection does not unwrap it - without this the JSON arrives as binary
            // noise and parses into nothing at all.
            InputStream in = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                    ? new GZIPInputStream(raw) : raw;
            byte[] bytes = readAll(in);
            if (bytes == null) throw new IOException("empty answer");
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (connection.getResponseCode() / 100 != 2) {
                throw new IOException("HTTP " + connection.getResponseCode() + " " + body);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static void save(Context app, Snapshot snap) {
        try {
            JSONObject json = new JSONObject();
            json.put("city", snap.city);
            json.put("temp", snap.temp);
            json.put("text", snap.text);
            json.put("code", snap.code);
            json.put("max", snap.max);
            json.put("min", snap.min);
            json.put("uv", snap.uv);
            json.put("humidity", snap.humidity);
            json.put("sunrise", snap.sunrise);
            json.put("sunset", snap.sunset);
            json.put("at", snap.at);
            json.put("placeName", snap.place.name);
            json.put("lat", snap.place.lat);
            json.put("lon", snap.place.lon);
            json.put("placeAt", snap.placeAt);
            json.put("query", snap.query);
            File file = new File(app.getFilesDir(), FILE_NAME);
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception broken) {
            Log.w(TAG, "could not keep the sky: " + broken.getMessage());
        }
    }

    /** Which of the bundled glyphs fits a QWeather condition code (see weather-conditions). */
    static int iconFor(int code) {
        int bucket = code / 100;
        switch (bucket) {
            case 3:
                return R.drawable.weather_rain;
            case 4:
                return R.drawable.weather_snow;
            case 5:
                return R.drawable.weather_haze;
            case 2:
                return R.drawable.weather_wind;
            case 9:
                return R.drawable.weather_sun;
            default:
                // 1xx: clear, mostly clear, cloudy. 150-199 are the clear night codes, which read
                // the same on a 20dp glyph.
                return code >= 101 && code <= 104 ? R.drawable.weather_cloud : R.drawable.weather_sun;
        }
    }

    private Weather() {
    }
}

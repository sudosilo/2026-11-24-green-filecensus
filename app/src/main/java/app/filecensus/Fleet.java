// GREEN PHONE EDIT
package app.filecensus;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.zip.GZIPOutputStream;

/** Cross-phone index: settings, pushing this phone's census to the fleet server, and plain requests for the page. */
public final class Fleet {

    private static Fleet instance;

    public static synchronized Fleet get(Context c) {
        if (instance == null) instance = new Fleet(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private volatile boolean pushing;
    private volatile String phase = "";
    private volatile long sent, total;
    private volatile String error = "";

    private Fleet(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("fleet", Context.MODE_PRIVATE);
        if (!prefs.contains("id")) {
            byte[] b = new byte[10];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder("ph-");
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            prefs.edit().putString("id", sb.toString()).putString("name", "Green phone").putString("color", "green")
                    .putBoolean("auto", true).apply();
        }
    }

    public boolean enabled() {
        return !prefs.getString("url", "").isEmpty() && !prefs.getString("key", "").isEmpty();
    }

    public boolean autoPush() { return enabled() && prefs.getBoolean("auto", true); }

    public String config() throws JSONException {
        String key = prefs.getString("key", "");
        return new JSONObject().put("url", prefs.getString("url", "")).put("hasKey", !key.isEmpty())
                .put("keyHint", key.length() > 6 ? key.substring(0, 3) + "..." + key.substring(key.length() - 4) : "")
                .put("id", prefs.getString("id", "")).put("name", prefs.getString("name", "")).put("color", prefs.getString("color", "green"))
                .put("auto", prefs.getBoolean("auto", true)).put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("lastPush", prefs.getLong("lastPush", 0)).put("enabled", enabled()).toString();
    }

    public void save(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        SharedPreferences.Editor e = prefs.edit();
        if (o.has("url")) {
            String u = o.getString("url").trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            if (!u.isEmpty() && !u.startsWith("http")) u = "https://" + u;
            e.putString("url", u);
        }
        if (o.has("key") && !o.getString("key").trim().isEmpty()) e.putString("key", o.getString("key").trim());
        if (o.has("name")) e.putString("name", o.getString("name").trim().isEmpty() ? "Phone" : o.getString("name").trim());
        if (o.has("color")) e.putString("color", o.getString("color"));
        if (o.has("auto")) e.putBoolean("auto", o.getBoolean("auto"));
        e.apply();
    }

    public String status() {
        try {
            return new JSONObject().put("pushing", pushing).put("phase", phase).put("sent", sent).put("total", total)
                    .put("error", error).put("lastPush", prefs.getLong("lastPush", 0)).toString();
        } catch (JSONException ex) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------ http

    public static final class Reply { public int code; public String body = ""; }

    public Reply request(String method, String path, String body, boolean gzip) {
        Reply r = new Reply();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(prefs.getString("url", "") + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Authorization", "Bearer " + prefs.getString("key", ""));
            if (body != null) {
                byte[] data = body.getBytes("UTF-8");
                if (gzip) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(data); }
                    data = bo.toByteArray();
                    c.setRequestProperty("Content-Encoding", "gzip");
                }
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) { os.write(data); }
            }
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                r.body = bo.toString("UTF-8");
            }
        } catch (Exception e) {
            r.code = 0;
            r.body = "{\"error\":" + JSONObject.quote("Could not reach the fleet server: " + e.getMessage()) + "}";
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    // ------------------------------------------------------------------ push

    public synchronized void pushAsync() {
        if (pushing || !enabled()) return;
        pushing = true;
        error = "";
        new Thread(this::push, "filecensus-fleet").start();
    }

    private void push() {
        try {
            phase = "starting";
            JSONObject dev = new JSONObject().put("id", prefs.getString("id", "")).put("name", prefs.getString("name", "Phone"))
                    .put("color", prefs.getString("color", "green")).put("model", Build.MANUFACTURER + " " + Build.MODEL);
            Reply b = request("POST", "/v1/census/begin", new JSONObject().put("device", dev).toString(), false);
            if (b.code != 200) throw new Exception(errorOf(b));
            String upload = new JSONObject(b.body).getString("upload");
            android.database.sqlite.SQLiteDatabase db = Db.get(ctx).getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM files", null)) { total = c.moveToFirst() ? c.getLong(0) : 0; }
            sent = 0;
            phase = "sending";
            JSONObject kinds = new JSONObject();
            String last = "";
            while (true) {
                JSONArray rows = new JSONArray();
                try (Cursor c = db.rawQuery("SELECT path, size, mtime, dt, kind, fp, hash, ai, shot, lat IS NOT NULL, " +
                        "(SELECT label FROM looks l WHERE l.path=files.path) FROM files WHERE path > ? ORDER BY path LIMIT 3000", new String[]{last})) {
                    while (c.moveToNext()) {
                        last = c.getString(0);
                        String kind = c.getString(4);
                        kinds.put(kind, kinds.optLong(kind) + 1);
                        rows.put(new JSONArray().put(last).put(c.getLong(1)).put(c.getLong(2)).put(c.getLong(3)).put(kind)
                                .put(c.isNull(5) ? "" : c.getString(5)).put(c.isNull(6) ? "" : c.getString(6))
                                .put(c.getInt(7)).put(c.getInt(8)).put(c.getInt(9)).put(c.isNull(10) ? "" : c.getString(10)));
                    }
                }
                if (rows.length() == 0) break;
                Reply ch = request("POST", "/v1/census/chunk", new JSONObject().put("upload", upload).put("rows", rows).toString(), true);
                if (ch.code != 200) throw new Exception(errorOf(ch));
                sent += rows.length();
            }
            phase = "finishing";
            String version = "";
            try { version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) { }
            JSONObject totals = new JSONObject().put("kinds", kinds).put("census", prefs.getLong("lastCensus", 0)).put("app", version);
            Reply cm = request("POST", "/v1/census/commit", new JSONObject().put("upload", upload).put("totals", totals).toString(), false);
            if (cm.code != 200) throw new Exception(errorOf(cm));
            prefs.edit().putLong("lastPush", System.currentTimeMillis()).apply();
            phase = "done";
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            phase = "stopped";
        } finally {
            pushing = false;
        }
    }

    public void noteCensus() { prefs.edit().putLong("lastCensus", System.currentTimeMillis()).apply(); }

    private static String errorOf(Reply r) {
        try { return new JSONObject(r.body).optString("error", "server said " + r.code); } catch (Exception e) { return "server said " + r.code; }
    }
}

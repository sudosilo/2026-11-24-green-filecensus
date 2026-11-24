// GREEN PHONE EDIT
package app.filecensus;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    static final String ORIGIN = "https://filecensus.local/";
    private WebView web;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Lets file:// links open in other apps when no MediaStore entry exists.
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());

        web = new WebView(this);
        web.setBackgroundColor(0xFFC0C0C0);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "FC");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Map<String, String> h = req.getRequestHeaders();
                String r = null;
                if (h != null) for (Map.Entry<String, String> e : h.entrySet()) if ("range".equalsIgnoreCase(e.getKey())) r = e.getValue();
                rangeHeader.set(r);
                return serve(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !req.getUrl().toString().startsWith(ORIGIN);
            }
        });
        setContentView(web);
        if (saved != null) web.restoreState(saved); else web.loadUrl(ORIGIN + "ui.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.fcResume && fcResume()", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.fcBack ? fcBack() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    private final ThreadLocal<String> rangeHeader = new ThreadLocal<>();

    private String range(Uri u) {
        String r = rangeHeader.get();
        rangeHeader.remove();
        return r;
    }

    /** Streams a file to the page with byte-range support so audio and video can seek. */
    private WebResourceResponse serveFile(String path, String range, Map<String, String> headers) throws Exception {
        if (path == null) return null;
        File f = new File(path);
        if (!f.isFile()) return new WebResourceResponse("text/plain", "utf-8", 404, "Missing", headers, new ByteArrayInputStream(new byte[0]));
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(Kinds.ext(f.getName()));
        if (mime == null) mime = "application/octet-stream";
        long len = f.length();
        long start = 0, end = len - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String[] se = range.substring(6).split(",")[0].split("-", -1);
            try {
                if (!se[0].isEmpty()) start = Long.parseLong(se[0].trim());
                if (se.length > 1 && !se[1].trim().isEmpty()) end = Math.min(len - 1, Long.parseLong(se[1].trim()));
                else if (se[0].isEmpty() && se.length > 1) { start = Math.max(0, len - Long.parseLong(se[1].trim())); end = len - 1; }
                partial = true;
            } catch (NumberFormatException ignored) { }
        }
        if (start > end || start >= len) { start = 0; end = len - 1; partial = false; }
        final long count = end - start + 1;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        raf.seek(start);
        InputStream in = new InputStream() {
            long left = count;
            @Override public int read() throws java.io.IOException {
                if (left <= 0) return -1;
                int b = raf.read();
                if (b >= 0) left--;
                return b;
            }
            @Override public int read(byte[] b, int off, int n) throws java.io.IOException {
                if (left <= 0) return -1;
                int r = raf.read(b, off, (int) Math.min(n, left));
                if (r > 0) left -= r;
                return r;
            }
            @Override public void close() throws java.io.IOException { raf.close(); }
        };
        headers.put("Accept-Ranges", "bytes");
        headers.put("Content-Length", String.valueOf(count));
        if (partial) headers.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
        return new WebResourceResponse(mime, null, partial ? 206 : 200, partial ? "Partial Content" : "OK", headers, in);
    }

    private WebResourceResponse serve(Uri u) {
        String url = u.toString();
        if (!url.startsWith(ORIGIN)) return null;
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        try {
            String p = u.getPath() == null ? "/" : u.getPath();
            if (p.equals("/thumb")) {
                String file = u.getQueryParameter("p");
                byte[] b = file == null ? null : Thumbs.get(this, file);
                headers.put("Cache-Control", "max-age=86400");
                if (b == null) return new WebResourceResponse("text/plain", "utf-8", 404, "None", headers, new ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse("image/jpeg", null, 200, "OK", headers, new ByteArrayInputStream(b));
            }
            if (p.equals("/preview")) {
                String file = u.getQueryParameter("p");
                byte[] b = file == null ? null : Thumbs.preview(file);
                if (b == null) return new WebResourceResponse("text/plain", "utf-8", 404, "None", headers, new ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse("image/jpeg", null, 200, "OK", headers, new ByteArrayInputStream(b));
            }
            if (p.equals("/file")) return serveFile(u.getQueryParameter("p"), range(u), headers);
            String asset = p.startsWith("/") ? p.substring(1) : p;
            if (asset.isEmpty()) asset = "ui.html";
            InputStream in = getAssets().open(asset);
            String mime = asset.endsWith(".html") ? "text/html" : asset.endsWith(".js") ? "application/javascript"
                    : asset.endsWith(".css") ? "text/css" : asset.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
            return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Missing", headers, new ByteArrayInputStream(new byte[0]));
        }
    }

    boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasLocation() {
        if (Build.VERSION.SDK_INT < 29) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void askAccess() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29 && !hasLocation()) perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        if (Build.VERSION.SDK_INT < 30 && !hasAccess()) perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 30 && !hasAccess()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 7);
            return;
        }
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 7);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        web.evaluateJavascript("window.fcResume && fcResume()", null);
    }

    void openFile(String path) {
        File f = new File(path);
        String ext = Kinds.ext(f.getName());
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = "*/*";
        Uri uri = null;
        try (Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.Files.FileColumns._ID}, MediaStore.Files.FileColumns.DATA + "=?",
                new String[]{path}, null)) {
            if (c != null && c.moveToFirst()) uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), c.getLong(0));
        } catch (Throwable ignored) { }
        if (uri == null) uri = Uri.fromFile(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(i, f.getName()));
        } catch (Throwable t) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Android's own dialog is the last gate before anything is moved or removed. The page cannot press it,
     * and the work only runs from the dialog's own button.
     */
    void confirm(String token, boolean empty) {
        String sum = Trash.summary(token);
        if (sum == null || Trash.isEmptyPlan(token) != empty) {
            web.evaluateJavascript("window.fcDeleteDone && fcDeleteDone(" + JSONObject.quote("{\"error\":\"This review expired. Nothing was changed.\"}") + ")", null);
            return;
        }
        String title = empty ? "Delete permanently?" : "Move to the FileCensus trash?";
        String msg = empty
                ? sum + " will be erased from the phone. This cannot be undone."
                : sum + " will be moved out of their folders into the FileCensus trash. You can restore them from the Trash tab until you empty it.";
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setCancelable(true)
                .setNegativeButton("Cancel", (d, w) -> web.evaluateJavascript("window.fcDeleteDone && fcDeleteDone(" + JSONObject.quote("{\"cancelled\":true}") + ")", null))
                .setPositiveButton(empty ? "Delete forever" : "Move to trash", (d, w) -> new Thread(() -> {
                    String res;
                    try { res = empty ? Trash.commitEmpty(MainActivity.this, token) : Trash.commitDelete(MainActivity.this, token); }
                    catch (Exception e) { res = "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
                    final String r = res;
                    runOnUiThread(() -> web.evaluateJavascript("window.fcDeleteDone && fcDeleteDone(" + JSONObject.quote(r) + ")", null));
                }).start())
                .show();
    }

    /** Everything the page can ask the phone for. */
    final class Bridge {
        private Db db() { return Db.get(MainActivity.this); }

        @JavascriptInterface public String access() {
            try {
                JSONObject o = new JSONObject().put("files", hasAccess()).put("location", hasLocation()).put("sdk", Build.VERSION.SDK_INT);
                StringBuilder roots = new StringBuilder();
                for (File r : Scanner.get(MainActivity.this).roots()) roots.append(roots.length() > 0 ? ", " : "").append(r.getAbsolutePath());
                o.put("roots", roots.toString());
                return o.toString();
            } catch (JSONException e) { return "{}"; }
        }
        @JavascriptInterface public void askAccess() { runOnUiThread(MainActivity.this::askAccess); }
        @JavascriptInterface public void scan() { if (hasAccess()) Scanner.get(MainActivity.this).start(); }
        @JavascriptInterface public String status() { return Scanner.get(MainActivity.this).status(); }
        @JavascriptInterface public String query(String f) { try { return db().query(f); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public String detail(String p) { try { return db().detail(p); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public String groups(String by, String f) { try { return db().groups(by, f); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String stats() { try { return db().stats(); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public String tags() { try { return db().tags(); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public long saveTag(long id, String name, String color) { return db().saveTag(id, name, color); }
        @JavascriptInterface public void deleteTag(long id) { db().deleteTag(id); }
        @JavascriptInterface public void setTag(String path, long id, boolean on) { db().setTag(path, id, on); }
        @JavascriptInterface public long tagMatching(String f, long id, boolean on) { try { return db().tagMatching(f, id, on); } catch (Exception e) { return -1; } }
        @JavascriptInterface public String dupes(int offset, int limit, String kind) { try { return db().dupes(offset, limit, kind); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public void dupeScan() { if (hasAccess()) Scanner.get(MainActivity.this).startDupes(); }
        @JavascriptInterface public String tree(String root) { try { return db().tree(root); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public String sizeHist() { try { return db().sizeHist(); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String gps() { try { return db().gps(); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String home() { return Environment.getExternalStorageDirectory().getAbsolutePath(); }
        @JavascriptInterface public String peek(String path) {
            File f = new File(path);
            byte[] b = new byte[(int) Math.min(65536, Math.max(0, f.length()))];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                return new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public String board(String root) { try { return db().board(root); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public void setLook(String path, String look) { try { db().setLook(path, look); } catch (Exception ignored) { } }
        @JavascriptInterface public void clearLook(String path) { db().clearLook(path); }
        @JavascriptInterface public long lookMatching(String f, String look) { try { return db().lookMatching(f, look); } catch (Exception e) { return -1; } }
        @JavascriptInterface public String looks() { try { return db().looks(); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String queryPaths(String f, int limit) { try { return db().queryPaths(f, limit); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String prepareDelete(String paths) { try { return Trash.prepareDelete(MainActivity.this, paths); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public boolean narrowDelete(String token, String keep) { try { return Trash.narrow(token, keep); } catch (Exception e) { return false; } }
        @JavascriptInterface public void confirmDelete(String token) { runOnUiThread(() -> confirm(token, false)); }
        @JavascriptInterface public String trashList() { try { return Trash.list(MainActivity.this); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public String restore(String ids) { try { return Trash.restore(MainActivity.this, ids); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public String prepareEmpty(String ids) { try { return Trash.prepareEmpty(MainActivity.this, ids); } catch (Exception e) { return err(e); } }
        @JavascriptInterface public void confirmEmpty(String token) { runOnUiThread(() -> confirm(token, true)); }
        @JavascriptInterface public String fleetConfig() { try { return Fleet.get(MainActivity.this).config(); } catch (Exception e) { return "{}"; } }
        @JavascriptInterface public void fleetSave(String json) { try { Fleet.get(MainActivity.this).save(json); } catch (Exception ignored) { } }
        @JavascriptInterface public String fleetStatus() { return Fleet.get(MainActivity.this).status(); }
        @JavascriptInterface public void fleetPush() { Fleet.get(MainActivity.this).pushAsync(); }
        @JavascriptInterface public String localByFp(String fps) { try { return db().localByFp(fps); } catch (Exception e) { return "{}"; } }
        /** Runs a fleet request off the main thread and hands the answer back to fcNet(id, code, body). */
        @JavascriptInterface public void fleetRequest(String method, String path, int id) {
            new Thread(() -> {
                Fleet.Reply r = Fleet.get(MainActivity.this).request(method, path, null, false);
                runOnUiThread(() -> web.evaluateJavascript("window.fcNet && fcNet(" + id + "," + r.code + "," + JSONObject.quote(r.body) + ")", null));
            }).start();
        }
        @JavascriptInterface public void open(String path) { runOnUiThread(() -> openFile(path)); }
        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
        }

        private String err(Exception e) {
            try { return new JSONObject().put("error", String.valueOf(e.getMessage())).toString(); } catch (JSONException j) { return "{}"; }
        }
    }
}

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
        @JavascriptInterface public void open(String path) { runOnUiThread(() -> openFile(path)); }
        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
        }

        private String err(Exception e) {
            try { return new JSONObject().put("error", String.valueOf(e.getMessage())).toString(); } catch (JSONException j) { return "{}"; }
        }
    }
}

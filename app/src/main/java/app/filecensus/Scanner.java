// GREEN PHONE EDIT
package app.filecensus;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Environment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two-pass scanner. Pass one walks every reachable folder and records name, type, size and dates
 * so the catalog fills in fast. Pass two opens each new or changed file for metadata, provenance
 * and the content hint, newest first.
 */
public final class Scanner {

    private static Scanner instance;

    public static synchronized Scanner get(Context c) {
        if (instance == null) instance = new Scanner(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private volatile boolean running;
    private volatile String phase = "idle";
    private volatile String current = "";
    private volatile long walked, changed, enriched, toEnrich, removed;
    private volatile long started, finished;
    private volatile String error = "";

    private Scanner(Context c) { ctx = c; }

    public synchronized void start() {
        if (running) return;
        running = true;
        walked = changed = enriched = toEnrich = removed = 0;
        error = "";
        started = System.currentTimeMillis();
        Thread t = new Thread(this::run, "filecensus-scan");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    public String status() {
        try {
            return new JSONObject()
                    .put("running", running).put("phase", phase).put("current", current)
                    .put("walked", walked).put("changed", changed).put("enriched", enriched)
                    .put("toEnrich", toEnrich).put("removed", removed)
                    .put("started", started).put("finished", finished).put("error", error).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Every storage root this app can read: internal shared storage plus SD cards and USB drives. */
    public List<File> roots() {
        Set<String> seen = new LinkedHashSet<>();
        List<File> out = new ArrayList<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null && primary.canRead()) { out.add(primary); seen.add(primary.getAbsolutePath()); }
        File[] dirs = ctx.getExternalFilesDirs(null);
        if (dirs != null) {
            for (File d : dirs) {
                if (d == null) continue;
                String p = d.getAbsolutePath();
                int i = p.indexOf("/Android/data/");
                if (i <= 0) continue;
                File root = new File(p.substring(0, i));
                if (root.canRead() && seen.add(root.getAbsolutePath())) out.add(root);
            }
        }
        return out;
    }

    private void run() {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        try {
            long gen = System.currentTimeMillis();
            phase = "loading catalog";
            Map<String, long[]> known = new HashMap<>();
            try (Cursor c = db.rawQuery("SELECT path, size, mtime FROM files", null)) {
                while (c.moveToNext()) known.put(c.getString(0), new long[]{c.getLong(1), c.getLong(2)});
            }

            phase = "walking folders";
            SQLiteStatement touch = db.compileStatement("UPDATE files SET seen=? WHERE path=?");
            SQLiteStatement upsert = db.compileStatement("INSERT OR REPLACE INTO files " +
                    "(path,name,parent,ext,kind,size,mtime,ctime,dt,hint,done,seen) VALUES (?,?,?,?,?,?,?,?,?,?,0,?)");
            ArrayDeque<File> stack = new ArrayDeque<>();
            for (File r : roots()) stack.push(r);
            int batch = 0;
            db.beginTransaction();
            try {
                while (!stack.isEmpty()) {
                    File dir = stack.pop();
                    String dp = dir.getAbsolutePath();
                    if (dp.endsWith("/Android/data") || dp.endsWith("/Android/obb") || dp.contains("/.Trash")) continue;
                    File[] list = dir.listFiles();
                    if (list == null) continue;
                    current = dp;
                    for (File f : list) {
                        try {
                            if (Files.isSymbolicLink(f.toPath())) continue;
                            if (f.isDirectory()) { stack.push(f); continue; }
                            walked++;
                            String path = f.getAbsolutePath();
                            long size = f.length();
                            long mtime = f.lastModified();
                            long[] k = known.remove(path);
                            if (k != null && k[0] == size && k[1] == mtime) {
                                touch.bindLong(1, gen);
                                touch.bindString(2, path);
                                touch.executeUpdateDelete();
                            } else {
                                changed++;
                                String name = f.getName();
                                String ext = Kinds.ext(name);
                                long ctime = 0;
                                try {
                                    BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                                    long ct = a.creationTime().toMillis();
                                    if (ct > 0 && ct != mtime) ctime = ct;
                                } catch (Throwable ignored) { }
                                upsert.clearBindings();
                                upsert.bindString(1, path);
                                upsert.bindString(2, name);
                                upsert.bindString(3, f.getParent() == null ? "" : f.getParent());
                                upsert.bindString(4, ext);
                                upsert.bindString(5, Kinds.kind(ext));
                                upsert.bindLong(6, size);
                                upsert.bindLong(7, mtime);
                                if (ctime > 0) upsert.bindLong(8, ctime); else upsert.bindNull(8);
                                upsert.bindLong(9, mtime);
                                upsert.bindString(10, Kinds.label(ext) + ", reading...");
                                upsert.bindLong(11, gen);
                                upsert.executeInsert();
                            }
                            if (++batch >= 400) {
                                db.setTransactionSuccessful();
                                db.endTransaction();
                                db.beginTransaction();
                                batch = 0;
                            }
                        } catch (Throwable ignored) { }
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            phase = "removing deleted files";
            if (!known.isEmpty()) {
                db.beginTransaction();
                try {
                    for (String gone : known.keySet()) {
                        db.delete("files", "path=?", new String[]{gone});
                        db.delete("file_tags", "path=?", new String[]{gone});
                        removed++;
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }

            phase = "reading metadata";
            List<String[]> todo = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT path, kind, ext FROM files WHERE done=0 ORDER BY mtime DESC", null)) {
                while (c.moveToNext()) todo.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
            }
            toEnrich = todo.size();
            batch = 0;
            db.beginTransaction();
            try {
                for (String[] row : todo) {
                    File f = new File(row[0]);
                    current = row[0];
                    if (!f.exists()) { enriched++; continue; }
                    Meta m = Meta.extract(ctx, f, row[1], row[2]);
                    ContentValues v = new ContentValues();
                    v.put("hint", m.hint);
                    if (m.taken > 0) { v.put("taken", m.taken); v.put("dt", m.taken); }
                    if (m.lat != null) { v.put("lat", m.lat); v.put("lon", m.lon); }
                    if (m.camera != null) v.put("camera", m.camera);
                    if (m.width > 0) { v.put("width", m.width); v.put("height", m.height); }
                    if (m.duration > 0) v.put("duration", m.duration);
                    v.put("ai", m.ai ? 1 : 0);
                    if (m.aiInfo != null) v.put("ai_info", m.aiInfo);
                    v.put("shot", m.shot);
                    if (m.shotInfo != null) v.put("shot_info", m.shotInfo);
                    v.put("meta", m.extra.toString());
                    v.put("done", 1);
                    db.update("files", v, "path=?", new String[]{row[0]});
                    enriched++;
                    if (++batch >= 50) {
                        db.setTransactionSuccessful();
                        db.endTransaction();
                        db.beginTransaction();
                        batch = 0;
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            phase = "done";
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            phase = "stopped";
        } finally {
            current = "";
            finished = System.currentTimeMillis();
            running = false;
        }
    }
}

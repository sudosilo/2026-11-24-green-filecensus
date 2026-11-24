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
    private volatile long dupSizes, dupDone, hashedBytes;

    private Scanner(Context c) { ctx = c; }

    public synchronized void start() { begin(false); }

    /** Re-runs only the duplicate finder. */
    public synchronized void startDupes() { begin(true); }

    private void begin(boolean dupesOnly) {
        if (running) return;
        running = true;
        dupSizes = dupDone = hashedBytes = 0;
        walked = changed = enriched = toEnrich = removed = 0;
        error = "";
        started = System.currentTimeMillis();
        Thread t = new Thread(dupesOnly ? this::runDupes : this::run, "filecensus-scan");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    public String status() {
        try {
            return new JSONObject()
                    .put("running", running).put("phase", phase).put("current", current)
                    .put("walked", walked).put("changed", changed).put("enriched", enriched)
                    .put("toEnrich", toEnrich).put("removed", removed)
                    .put("started", started).put("finished", finished).put("error", error)
                    .put("dupSizes", dupSizes).put("dupDone", dupDone).put("hashedBytes", hashedBytes).toString();
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

    private void runDupes() {
        try {
            findDupes(Db.get(ctx).getWritableDatabase());
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

    private static final int EDGE = 65536;

    /**
     * Same size first, then a quick hash of the first and last 64 KB, then a full SHA-256 only for
     * files that still collide. Only full-hash matches count as duplicates.
     */
    private void findDupes(SQLiteDatabase db) throws Exception {
        phase = "finding duplicates";
        List<Long> sizes = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT size FROM files WHERE size>0 GROUP BY size HAVING COUNT(*)>1 " +
                "AND SUM(phash IS NULL) + SUM(hash IS NULL) > 0 ORDER BY size DESC", null)) {
            while (c.moveToNext()) sizes.add(c.getLong(0));
        }
        dupSizes = sizes.size();
        for (long size : sizes) {
            List<String[]> rows = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT path, phash, hash FROM files WHERE size=?", new String[]{String.valueOf(size)})) {
                while (c.moveToNext()) rows.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
            }
            Map<String, List<String[]>> byPartial = new HashMap<>();
            db.beginTransaction();
            try {
                for (String[] r : rows) {
                    if (r[1] == null) {
                        current = r[0];
                        String ph = hash(new File(r[0]), size, size <= 2L * EDGE);
                        if (ph == null) continue;
                        r[1] = ph;
                        ContentValues v = new ContentValues();
                        v.put("phash", ph);
                        if (size <= 2L * EDGE) { v.put("hash", ph); r[2] = ph; }
                        db.update("files", v, "path=?", new String[]{r[0]});
                    }
                    List<String[]> l = byPartial.get(r[1]);
                    if (l == null) { l = new ArrayList<>(); byPartial.put(r[1], l); }
                    l.add(r);
                }
                for (List<String[]> group : byPartial.values()) {
                    if (group.size() < 2) continue;
                    for (String[] r : group) {
                        if (r[2] != null) continue;
                        current = r[0];
                        String full = hash(new File(r[0]), size, true);
                        if (full == null) continue;
                        ContentValues v = new ContentValues();
                        v.put("hash", full);
                        db.update("files", v, "path=?", new String[]{r[0]});
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            dupDone++;
        }
    }

    private String hash(File f, long size, boolean full) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[EDGE];
            if (full) {
                int n;
                while ((n = r.read(buf)) > 0) { md.update(buf, 0, n); hashedBytes += n; }
            } else {
                r.readFully(buf, 0, EDGE);
                md.update(buf);
                r.seek(size - EDGE);
                r.readFully(buf, 0, EDGE);
                md.update(buf);
                hashedBytes += 2L * EDGE;
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(full ? "f" : "p");
            for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
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
            findDupes(db);
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

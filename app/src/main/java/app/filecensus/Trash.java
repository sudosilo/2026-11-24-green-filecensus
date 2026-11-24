// GREEN PHONE EDIT
package app.filecensus;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.MediaScannerConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Safe deletion. Nothing is removed unless the page first asks for a review (prepare), which returns a
 * single-use token bound to the exact files, sizes and dates that were shown, and the person then accepts
 * Android's own confirmation dialog. Deleting moves files into a .filecensus-trash folder on the same
 * storage volume, so it is instant and can be undone. Only emptying the trash removes files for good,
 * and that takes its own review, token and dialog.
 */
public final class Trash {

    public static final String DIR = ".filecensus-trash";
    private static final long TOKEN_LIFE = 10 * 60 * 1000;

    private static final class Plan {
        final boolean empty;
        final List<String[]> items = new ArrayList<>(); // delete: path,size,mtime   empty: id
        final long made = System.currentTimeMillis();
        long bytes;
        Plan(boolean empty) { this.empty = empty; }
    }

    private static final Map<String, Plan> PLANS = new HashMap<>();
    private static final SecureRandom RNG = new SecureRandom();

    private Trash() { }

    static void createTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS trash (id INTEGER PRIMARY KEY AUTOINCREMENT, orig TEXT, trashed TEXT, " +
                "name TEXT, kind TEXT, size INTEGER, mtime INTEGER, deleted_at INTEGER, tags TEXT, look TEXT, note TEXT)");
    }

    private static String token() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static synchronized void sweep() {
        long now = System.currentTimeMillis();
        PLANS.values().removeIf(p -> now - p.made > TOKEN_LIFE);
    }

    private static File rootFor(Context ctx, String path) {
        File best = null;
        for (File r : Scanner.get(ctx).roots()) {
            String rp = r.getAbsolutePath() + "/";
            if (path.startsWith(rp) && (best == null || rp.length() > best.getAbsolutePath().length() + 1)) best = r;
        }
        return best;
    }

    static boolean inTrash(String path) {
        return path.contains("/" + DIR + "/") || path.endsWith("/" + DIR);
    }

    // ------------------------------------------------------------------ delete: review step

    /** Builds the review the page shows. Returns the files with everything worth a second look, plus a token. */
    public static String prepareDelete(Context ctx, String pathsJson) throws JSONException {
        sweep();
        JSONArray paths = new JSONArray(pathsJson);
        SQLiteDatabase db = Db.get(ctx).getReadableDatabase();
        Plan plan = new Plan(false);
        JSONArray items = new JSONArray();
        JSONArray refused = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < paths.length() && i < 20000; i++) {
            String p = paths.getString(i);
            if (!seen.add(p)) continue;
            File f = new File(p);
            String why = null;
            if (rootFor(ctx, p) == null) why = "outside shared storage";
            else if (inTrash(p)) why = "already in the trash";
            else if (!f.isFile()) why = "no longer there";
            if (why != null) { refused.put(new JSONObject().put("path", p).put("why", why)); continue; }
            JSONObject o = new JSONObject().put("path", p).put("name", f.getName()).put("parent", f.getParent())
                    .put("size", f.length()).put("mtime", f.lastModified());
            try (Cursor c = db.rawQuery("SELECT kind, dt, hash, ai, (SELECT GROUP_CONCAT(tag_id) FROM file_tags t WHERE t.path=files.path), " +
                    Db.LOOK + ", (SELECT COUNT(*) FROM files g WHERE g.hash=files.hash AND files.hash IS NOT NULL) FROM files WHERE path=?", new String[]{p})) {
                if (c.moveToFirst()) {
                    o.put("kind", c.getString(0)).put("dt", c.getLong(1)).put("ai", c.getInt(3))
                            .put("tags", c.isNull(4) ? "" : c.getString(4)).put("look", c.isNull(5) ? "" : c.getString(5))
                            .put("copies", c.getLong(6));
                } else {
                    o.put("kind", Kinds.kind(Kinds.ext(f.getName()))).put("dt", f.lastModified()).put("copies", 0);
                }
            }
            items.put(o);
            plan.items.add(new String[]{p, String.valueOf(f.length()), String.valueOf(f.lastModified())});
            plan.bytes += f.length();
        }
        String tok = token();
        synchronized (Trash.class) { PLANS.put(tok, plan); }
        return new JSONObject().put("token", tok).put("count", plan.items.size()).put("bytes", plan.bytes)
                .put("items", items).put("refused", refused).toString();
    }

    /** The page may narrow a review (files unchecked) before confirming; it can only remove, never add. */
    public static synchronized boolean narrow(String tok, String keepJson) throws JSONException {
        Plan p = PLANS.get(tok);
        if (p == null || p.empty) return false;
        JSONArray keep = new JSONArray(keepJson);
        java.util.Set<String> want = new java.util.HashSet<>();
        for (int i = 0; i < keep.length(); i++) want.add(keep.getString(i));
        p.items.removeIf(it -> !want.contains(it[0]));
        p.bytes = 0;
        for (String[] it : p.items) p.bytes += Long.parseLong(it[1]);
        return true;
    }

    public static synchronized String summary(String tok) {
        Plan p = PLANS.get(tok);
        if (p == null) return null;
        return p.items.size() + (p.items.size() == 1 ? " file, " : " files, ") + Fmt.size(p.bytes);
    }

    public static synchronized boolean isEmptyPlan(String tok) {
        Plan p = PLANS.get(tok);
        return p != null && p.empty;
    }

    private static synchronized Plan take(String tok) {
        Plan p = PLANS.remove(tok);
        if (p == null || System.currentTimeMillis() - p.made > TOKEN_LIFE) return null;
        return p;
    }

    // ------------------------------------------------------------------ delete: after the dialog

    /** Runs only after Android's dialog was accepted. Moves each reviewed file into the trash. */
    public static String commitDelete(Context ctx, String tok) throws JSONException {
        Plan plan = take(tok);
        JSONObject out = new JSONObject();
        if (plan == null || plan.empty) return out.put("error", "This review expired. Nothing was deleted.").toString();
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        long batch = System.currentTimeMillis();
        int moved = 0;
        long bytes = 0;
        JSONArray failed = new JSONArray();
        List<String> scan = new ArrayList<>();
        for (String[] it : plan.items) {
            String path = it[0];
            File f = new File(path);
            File root = rootFor(ctx, path);
            if (root == null || inTrash(path) || !f.isFile()) { failed.put(new JSONObject().put("path", path).put("why", "no longer there")); continue; }
            if (f.length() != Long.parseLong(it[1]) || f.lastModified() != Long.parseLong(it[2])) {
                failed.put(new JSONObject().put("path", path).put("why", "changed since the review, left alone"));
                continue;
            }
            File trashRoot = new File(root, DIR);
            if (!trashRoot.isDirectory()) {
                trashRoot.mkdirs();
                try { new FileOutputStream(new File(trashRoot, ".nomedia")).close(); } catch (Exception ignored) { }
            }
            String rel = path.substring(root.getAbsolutePath().length() + 1);
            File dest = new File(new File(trashRoot, String.valueOf(batch)), rel);
            if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
            String why = move(ctx, f, dest, Long.parseLong(it[1]));
            if (why != null) { failed.put(new JSONObject().put("path", path).put("why", why)); continue; }
            String tags = null, look = null, name = f.getName(), kind = Kinds.kind(Kinds.ext(name));
            try (Cursor c = db.rawQuery("SELECT (SELECT GROUP_CONCAT(tag_id) FROM file_tags t WHERE t.path=files.path), " + Db.LOOK +
                    ", kind FROM files WHERE path=?", new String[]{path})) {
                if (c.moveToFirst()) { tags = c.getString(0); look = c.getString(1); kind = c.getString(2); }
            }
            ContentValues v = new ContentValues();
            v.put("orig", path);
            v.put("trashed", dest.getAbsolutePath());
            v.put("name", name);
            v.put("kind", kind);
            v.put("size", Long.parseLong(it[1]));
            v.put("mtime", Long.parseLong(it[2]));
            v.put("deleted_at", batch);
            v.put("tags", tags);
            v.put("look", look);
            db.insert("trash", null, v);
            db.delete("files", "path=?", new String[]{path});
            db.delete("file_tags", "path=?", new String[]{path});
            db.delete("looks", "path=?", new String[]{path});
            scan.add(path);
            moved++;
            bytes += Long.parseLong(it[1]);
        }
        rescan(ctx, scan);
        return out.put("moved", moved).put("bytes", bytes).put("failed", failed).toString();
    }

    // ------------------------------------------------------------------ the trash itself

    public static String list(Context ctx) throws JSONException {
        JSONArray out = new JSONArray();
        long total = 0;
        try (Cursor c = Db.get(ctx).getReadableDatabase().rawQuery("SELECT id, orig, trashed, name, kind, size, mtime, deleted_at, look, note " +
                "FROM trash ORDER BY deleted_at DESC, orig", null)) {
            while (c.moveToNext()) {
                out.put(new JSONObject().put("id", c.getLong(0)).put("path", c.getString(1)).put("trashed", c.getString(2))
                        .put("name", c.getString(3)).put("kind", c.getString(4)).put("size", c.getLong(5))
                        .put("mtime", c.getLong(6)).put("deleted", c.getLong(7)).put("look", c.isNull(8) ? "" : c.getString(8))
                        .put("ext", Kinds.ext(c.getString(3))).put("there", new File(c.getString(2)).isFile())
                        .put("note", c.isNull(9) ? "" : c.getString(9)).put("origThere", new File(c.getString(1)).exists()));
                total += c.getLong(5);
            }
        }
        return new JSONObject().put("items", out).put("bytes", total).toString();
    }

    /** Puts files back where they were. Restoring never needs a dialog because it removes nothing. */
    public static String restore(Context ctx, String idsJson) throws JSONException {
        JSONArray ids = new JSONArray(idsJson);
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        int ok = 0;
        JSONArray failed = new JSONArray();
        List<String> scan = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            long id = ids.getLong(i);
            try (Cursor c = db.rawQuery("SELECT orig, trashed, tags, look FROM trash WHERE id=?", new String[]{String.valueOf(id)})) {
                if (!c.moveToFirst()) continue;
                String orig = c.getString(0), trashed = c.getString(1), tags = c.getString(2), look = c.getString(3);
                File src = new File(trashed), dest = new File(orig);
                if (!src.isFile()) { failed.put(new JSONObject().put("path", orig).put("why", "missing from the trash")); continue; }
                if (dest.exists()) {
                    String n = dest.getName(), base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n, ext = n.contains(".") ? n.substring(n.lastIndexOf('.')) : "";
                    int k = 1;
                    do { dest = new File(dest.getParentFile(), base + " (restored" + (k > 1 ? " " + k : "") + ")" + ext); k++; } while (dest.exists());
                }
                if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
                String why = move(ctx, src, dest, src.length());
                if (why != null) { failed.put(new JSONObject().put("path", orig).put("why", why)); continue; }
                String back = dest.getAbsolutePath();
                if (tags != null) for (String t : tags.split(",")) { try { Db.get(ctx).setTag(back, Long.parseLong(t), true); } catch (NumberFormatException ignored) { } }
                if (look != null && !look.isEmpty()) {
                    String[] p = look.split("\\|", -1);
                    if (p.length >= 5) {
                        JSONObject l = new JSONObject().put("color", p[0]).put("shape", p[1]).put("label", p[2])
                                .put("size", p[3].isEmpty() ? 1 : Integer.parseInt(p[3])).put("fx", p[4]);
                        Db.get(ctx).setLook(back, l.toString());
                    }
                }
                db.delete("trash", "id=?", new String[]{String.valueOf(id)});
                scan.add(back);
                ok++;
                pruneEmpty(src.getParentFile());
            }
        }
        rescan(ctx, scan);
        if (ok > 0) Scanner.get(ctx).start();
        return new JSONObject().put("restored", ok).put("failed", failed).toString();
    }

    /** Review for emptying: lists exactly which trashed files would be gone for good. */
    public static String prepareEmpty(Context ctx, String idsJson) throws JSONException {
        sweep();
        Plan plan = new Plan(true);
        JSONArray ids = new JSONArray(idsJson);
        SQLiteDatabase db = Db.get(ctx).getReadableDatabase();
        for (int i = 0; i < ids.length(); i++) {
            try (Cursor c = db.rawQuery("SELECT id, size FROM trash WHERE id=?", new String[]{String.valueOf(ids.getLong(i))})) {
                if (c.moveToFirst()) { plan.items.add(new String[]{String.valueOf(c.getLong(0))}); plan.bytes += c.getLong(1); }
            }
        }
        String tok = token();
        synchronized (Trash.class) { PLANS.put(tok, plan); }
        return new JSONObject().put("token", tok).put("count", plan.items.size()).put("bytes", plan.bytes).toString();
    }

    /** Runs only after the second dialog was accepted. This is the one step that cannot be undone. */
    public static String commitEmpty(Context ctx, String tok) throws JSONException {
        Plan plan = take(tok);
        JSONObject out = new JSONObject();
        if (plan == null || !plan.empty) return out.put("error", "This review expired. Nothing was removed.").toString();
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        int gone = 0;
        long bytes = 0;
        JSONArray failed = new JSONArray();
        for (String[] it : plan.items) {
            try (Cursor c = db.rawQuery("SELECT trashed, size, orig FROM trash WHERE id=?", new String[]{it[0]})) {
                if (!c.moveToFirst()) continue;
                File f = new File(c.getString(0));
                if (!inTrash(f.getAbsolutePath())) continue; // only ever removes files inside the trash folder
                String why = erase(ctx, f);
                if (why != null) { failed.put(new JSONObject().put("path", c.getString(2)).put("why", why)); continue; }
                db.delete("trash", "id=?", new String[]{it[0]});
                gone++;
                bytes += c.getLong(1);
                pruneEmpty(f.getParentFile());
            }
        }
        return out.put("removed", gone).put("bytes", bytes).put("failed", failed).toString();
    }

    /**
     * Moves a file and then checks the disk, because a rename that reports success is not proof.
     * Returns null when the file is at dest and gone from src, otherwise the reason it failed,
     * with src left exactly as it was.
     */
    static String move(Context ctx, File src, File dest, long size) {
        String err = null;
        try {
            java.nio.file.Files.move(src.toPath(), dest.toPath());
        } catch (Exception e) {
            err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (!src.renameTo(dest)) {
                if (!dest.exists()) return "Android refused the move (" + err + ")";
            }
        }
        boolean srcThere = src.exists(), destThere = dest.isFile();
        if (!destThere) return srcThere ? "Android refused the move" : "the file disappeared during the move";
        if (srcThere) {
            // the storage layer copied instead of moving; finish the move or undo the copy
            if (dest.length() == size && deleteHard(ctx, src) && !src.exists()) return null;
            deleteHard(ctx, dest);
            return "Android kept the original in place, so it was left alone";
        }
        return null;
    }

    /** Erases a trashed file and checks it is really gone. Falls back to MediaStore when a plain delete is refused. */
    static String erase(Context ctx, File f) {
        if (!f.exists()) return null;
        if (deleteHard(ctx, f) && !f.exists()) return null;
        return "Android refused to erase it";
    }

    private static boolean deleteHard(Context ctx, File f) {
        try { java.nio.file.Files.deleteIfExists(f.toPath()); } catch (Exception ignored) { }
        if (!f.exists()) return true;
        if (f.delete() && !f.exists()) return true;
        try {
            ctx.getContentResolver().delete(android.provider.MediaStore.Files.getContentUri("external"),
                    android.provider.MediaStore.MediaColumns.DATA + "=?", new String[]{f.getAbsolutePath()});
        } catch (Exception ignored) { }
        return !f.exists();
    }

    /** Called by the census: a trashed file whose original path is back on disk gets flagged so it shows in the Trash tab. */
    static void flagReturned(SQLiteDatabase db, String path) {
        ContentValues v = new ContentValues();
        v.put("note", "A file is back at its original path. Either Android copied it instead of moving it, or the app that owns it made it again.");
        db.update("trash", v, "orig=?", new String[]{path});
    }

    private static void pruneEmpty(File dir) {
        while (dir != null && inTrash(dir.getAbsolutePath() + "/") && !dir.getName().equals(DIR)) {
            String[] kids = dir.list();
            if (kids == null || kids.length > 0 || !dir.delete()) break;
            dir = dir.getParentFile();
        }
    }

    private static void rescan(Context ctx, List<String> paths) {
        if (paths.isEmpty()) return;
        try { MediaScannerConnection.scanFile(ctx, paths.toArray(new String[0]), null, null); } catch (Throwable ignored) { }
    }
}

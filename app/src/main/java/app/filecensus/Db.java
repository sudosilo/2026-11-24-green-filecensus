// GREEN PHONE EDIT
package app.filecensus;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** SQLite catalog: every file, its metadata, and the user's reusable tags. */
public class Db extends SQLiteOpenHelper {

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "filecensus.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE files (" +
                "path TEXT PRIMARY KEY, name TEXT, parent TEXT, ext TEXT, kind TEXT, " +
                "size INTEGER, mtime INTEGER, ctime INTEGER, taken INTEGER, dt INTEGER, " +
                "hint TEXT, lat REAL, lon REAL, camera TEXT, width INTEGER, height INTEGER, " +
                "duration INTEGER, ai INTEGER DEFAULT 0, ai_info TEXT, shot INTEGER DEFAULT 0, " +
                "shot_info TEXT, meta TEXT, done INTEGER DEFAULT 0, seen INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX ix_kind ON files(kind)");
        db.execSQL("CREATE INDEX ix_dt ON files(dt)");
        db.execSQL("CREATE INDEX ix_done ON files(done)");
        db.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE COLLATE NOCASE, color TEXT)");
        db.execSQL("CREATE TABLE file_tags (path TEXT, tag_id INTEGER, PRIMARY KEY(path, tag_id))");
        db.execSQL("CREATE INDEX ix_ft_tag ON file_tags(tag_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    // ---------------------------------------------------------------- filters

    /** Builds a WHERE clause from the UI filter object. */
    static String where(JSONObject f, List<String> args) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        String q = f.optString("q", "").trim();
        if (!q.isEmpty()) {
            for (String word : q.split("\\s+")) {
                w.append(" AND (name LIKE ? OR hint LIKE ? OR parent LIKE ? OR camera LIKE ? OR ai_info LIKE ?)");
                String like = "%" + word + "%";
                for (int i = 0; i < 5; i++) args.add(like);
            }
        }
        String kind = f.optString("kind", "");
        if (!kind.isEmpty()) { w.append(" AND kind=?"); args.add(kind); }
        long tag = f.optLong("tag", 0);
        if (tag > 0) { w.append(" AND path IN (SELECT path FROM file_tags WHERE tag_id=?)"); args.add(String.valueOf(tag)); }
        if (f.optBoolean("untagged", false)) w.append(" AND path NOT IN (SELECT path FROM file_tags)");
        if (f.optBoolean("ai", false)) w.append(" AND ai=1");
        if (f.optBoolean("shot", false)) w.append(" AND shot>0");
        if (f.optBoolean("gps", false)) w.append(" AND lat IS NOT NULL");
        long from = f.optLong("from", 0);
        long to = f.optLong("to", 0);
        if (from > 0) { w.append(" AND dt>=?"); args.add(String.valueOf(from)); }
        if (to > 0) { w.append(" AND dt<?"); args.add(String.valueOf(to)); }
        String folder = f.optString("folder", "");
        if (!folder.isEmpty()) { w.append(" AND parent=?"); args.add(folder); }
        return w.toString();
    }

    static String order(String sort) {
        switch (sort == null ? "" : sort) {
            case "date_asc": return " ORDER BY dt ASC";
            case "size_desc": return " ORDER BY size DESC";
            case "size_asc": return " ORDER BY size ASC";
            case "name": return " ORDER BY name COLLATE NOCASE ASC";
            case "kind": return " ORDER BY CASE kind WHEN 'image' THEN 0 WHEN 'video' THEN 1 WHEN 'audio' THEN 2 " +
                    "WHEN 'document' THEN 3 WHEN 'text' THEN 4 WHEN 'code' THEN 5 WHEN 'archive' THEN 6 WHEN 'apk' THEN 7 " +
                    "WHEN 'data' THEN 8 ELSE 9 END, dt DESC";
            default: return " ORDER BY dt DESC";
        }
    }

    // ---------------------------------------------------------------- queries

    public String query(String filterJson) throws JSONException {
        JSONObject f = new JSONObject(filterJson);
        List<String> args = new ArrayList<>();
        String w = where(f, args);
        String[] a = args.toArray(new String[0]);
        SQLiteDatabase db = getReadableDatabase();
        JSONObject out = new JSONObject();
        try (Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(size),0) FROM files" + w, a)) {
            if (c.moveToFirst()) { out.put("total", c.getLong(0)); out.put("bytes", c.getLong(1)); }
        }
        int limit = Math.max(1, Math.min(500, f.optInt("limit", 120)));
        int offset = Math.max(0, f.optInt("offset", 0));
        String sql = "SELECT path,name,parent,ext,kind,size,mtime,dt,hint,ai,shot,lat," +
                "(SELECT GROUP_CONCAT(tag_id) FROM file_tags t WHERE t.path=files.path) FROM files" +
                w + order(f.optString("sort")) + " LIMIT " + limit + " OFFSET " + offset;
        JSONArray items = new JSONArray();
        try (Cursor c = db.rawQuery(sql, a)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("path", c.getString(0));
                o.put("name", c.getString(1));
                o.put("parent", c.getString(2));
                o.put("ext", c.getString(3));
                o.put("kind", c.getString(4));
                o.put("size", c.getLong(5));
                o.put("mtime", c.getLong(6));
                o.put("dt", c.getLong(7));
                o.put("hint", c.isNull(8) ? "" : c.getString(8));
                o.put("ai", c.getInt(9));
                o.put("shot", c.getInt(10));
                o.put("gps", !c.isNull(11));
                o.put("tags", c.isNull(12) ? "" : c.getString(12));
                items.put(o);
            }
        }
        out.put("items", items);
        return out.toString();
    }

    public String detail(String path) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject o = new JSONObject();
        try (Cursor c = db.rawQuery("SELECT * FROM files WHERE path=?", new String[]{path})) {
            if (!c.moveToFirst()) return "{}";
            for (int i = 0; i < c.getColumnCount(); i++) {
                String col = c.getColumnName(i);
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_INTEGER: o.put(col, c.getLong(i)); break;
                    case Cursor.FIELD_TYPE_FLOAT: o.put(col, c.getDouble(i)); break;
                    case Cursor.FIELD_TYPE_STRING: o.put(col, c.getString(i)); break;
                    default: break;
                }
            }
        }
        JSONArray tags = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT tag_id FROM file_tags WHERE path=?", new String[]{path})) {
            while (c.moveToNext()) tags.put(c.getLong(0));
        }
        o.put("tags", tags);
        return o.toString();
    }

    /** Group counts by day, month or kind for the current filter. */
    public String groups(String by, String filterJson) throws JSONException {
        JSONObject f = new JSONObject(filterJson);
        List<String> args = new ArrayList<>();
        String w = where(f, args);
        String key;
        switch (by) {
            case "kind": key = "kind"; break;
            case "year": key = "strftime('%Y', dt/1000, 'unixepoch', 'localtime')"; break;
            case "day": key = "strftime('%Y-%m-%d', dt/1000, 'unixepoch', 'localtime')"; break;
            case "folder": key = "parent"; break;
            default: key = "strftime('%Y-%m', dt/1000, 'unixepoch', 'localtime')"; break;
        }
        String sql = "SELECT " + key + " AS k, COUNT(*), COALESCE(SUM(size),0), " +
                "SUM(kind='image'), SUM(kind='video'), SUM(ai), SUM(shot>0), MIN(dt), MAX(dt) FROM files" + w +
                " GROUP BY k ORDER BY " + ("kind".equals(by) ? "COUNT(*) DESC" : ("folder".equals(by) ? "SUM(size) DESC" : "k DESC")) +
                ("folder".equals(by) ? " LIMIT 60" : "");
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("key", c.isNull(0) ? "unknown" : c.getString(0));
                o.put("count", c.getLong(1));
                o.put("bytes", c.getLong(2));
                o.put("images", c.getLong(3));
                o.put("videos", c.getLong(4));
                o.put("ai", c.getLong(5));
                o.put("shots", c.getLong(6));
                o.put("first", c.getLong(7));
                o.put("last", c.getLong(8));
                out.put(o);
            }
        }
        return out.toString();
    }

    public String stats() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject o = new JSONObject();
        try (Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(size),0), SUM(ai), SUM(shot=1), SUM(shot=2), " +
                "SUM(lat IS NOT NULL), SUM(done=0), MIN(dt), MAX(dt) FROM files", null)) {
            if (c.moveToFirst()) {
                o.put("files", c.getLong(0));
                o.put("bytes", c.getLong(1));
                o.put("ai", c.getLong(2));
                o.put("android_shots", c.getLong(3));
                o.put("ios_shots", c.getLong(4));
                o.put("gps", c.getLong(5));
                o.put("pending", c.getLong(6));
                o.put("first", c.getLong(7));
                o.put("last", c.getLong(8));
            }
        }
        JSONArray cams = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT camera, COUNT(*) FROM files WHERE camera IS NOT NULL AND camera<>'' " +
                "GROUP BY camera ORDER BY COUNT(*) DESC LIMIT 12", null)) {
            while (c.moveToNext()) cams.put(new JSONObject().put("name", c.getString(0)).put("count", c.getLong(1)));
        }
        o.put("cameras", cams);
        JSONArray big = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT path, name, size, kind FROM files ORDER BY size DESC LIMIT 15", null)) {
            while (c.moveToNext()) big.put(new JSONObject().put("path", c.getString(0)).put("name", c.getString(1))
                    .put("size", c.getLong(2)).put("kind", c.getString(3)));
        }
        o.put("biggest", big);
        o.put("kinds", new JSONArray(groups("kind", "{}")));
        o.put("folders", new JSONArray(groups("folder", "{}")));
        return o.toString();
    }

    // ---------------------------------------------------------------- tags

    public String tags() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, name, color, " +
                "(SELECT COUNT(*) FROM file_tags f WHERE f.tag_id=tags.id) FROM tags ORDER BY name COLLATE NOCASE", null)) {
            while (c.moveToNext()) {
                out.put(new JSONObject().put("id", c.getLong(0)).put("name", c.getString(1))
                        .put("color", c.getString(2)).put("count", c.getLong(3)));
            }
        }
        return out.toString();
    }

    public long saveTag(long id, String name, String color) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) return -1;
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("color", color);
        SQLiteDatabase db = getWritableDatabase();
        if (id > 0) {
            db.update("tags", v, "id=?", new String[]{String.valueOf(id)});
            return id;
        }
        return db.insertWithOnConflict("tags", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void deleteTag(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("file_tags", "tag_id=?", new String[]{String.valueOf(id)});
            db.delete("tags", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void setTag(String path, long tagId, boolean on) {
        SQLiteDatabase db = getWritableDatabase();
        if (on) {
            ContentValues v = new ContentValues();
            v.put("path", path);
            v.put("tag_id", tagId);
            db.insertWithOnConflict("file_tags", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        } else {
            db.delete("file_tags", "path=? AND tag_id=?", new String[]{path, String.valueOf(tagId)});
        }
    }

    /** Applies or removes a tag on every file matching the filter. Returns rows affected. */
    public long tagMatching(String filterJson, long tagId, boolean on) throws JSONException {
        JSONObject f = new JSONObject(filterJson);
        List<String> args = new ArrayList<>();
        String w = where(f, args);
        SQLiteDatabase db = getWritableDatabase();
        long before = count(db, "SELECT COUNT(*) FROM file_tags WHERE tag_id=" + tagId);
        if (on) {
            List<String> a2 = new ArrayList<>();
            a2.add(String.valueOf(tagId));
            a2.addAll(args);
            db.execSQL("INSERT OR IGNORE INTO file_tags(path, tag_id) SELECT path, ? FROM files" + w, a2.toArray());
        } else {
            List<String> a2 = new ArrayList<>();
            a2.add(String.valueOf(tagId));
            a2.addAll(args);
            db.execSQL("DELETE FROM file_tags WHERE tag_id=? AND path IN (SELECT path FROM files" + w + ")", a2.toArray());
        }
        long after = count(db, "SELECT COUNT(*) FROM file_tags WHERE tag_id=" + tagId);
        return Math.abs(after - before);
    }

    private static long count(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }
}

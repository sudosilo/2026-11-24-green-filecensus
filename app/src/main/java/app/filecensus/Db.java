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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SQLite catalog: every file, its metadata, and the user's reusable tags. */
public class Db extends SQLiteOpenHelper {

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "filecensus.db", null, 5);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE files (" +
                "path TEXT PRIMARY KEY, name TEXT, parent TEXT, ext TEXT, kind TEXT, " +
                "size INTEGER, mtime INTEGER, ctime INTEGER, taken INTEGER, dt INTEGER, " +
                "hint TEXT, lat REAL, lon REAL, camera TEXT, width INTEGER, height INTEGER, " +
                "duration INTEGER, ai INTEGER DEFAULT 0, ai_info TEXT, shot INTEGER DEFAULT 0, " +
                "shot_info TEXT, meta TEXT, done INTEGER DEFAULT 0, seen INTEGER DEFAULT 0, hash TEXT, phash TEXT, fp TEXT)");
        db.execSQL("CREATE INDEX ix_fp ON files(fp)");
        db.execSQL("CREATE INDEX ix_hash ON files(hash)");
        db.execSQL("CREATE INDEX ix_size ON files(size)");
        db.execSQL("CREATE INDEX ix_kind ON files(kind)");
        db.execSQL("CREATE INDEX ix_dt ON files(dt)");
        db.execSQL("CREATE INDEX ix_done ON files(done)");
        db.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE COLLATE NOCASE, color TEXT)");
        db.execSQL("CREATE TABLE file_tags (path TEXT, tag_id INTEGER, PRIMARY KEY(path, tag_id))");
        db.execSQL("CREATE INDEX ix_ft_tag ON file_tags(tag_id)");
        createLooks(db);
        Trash.createTable(db);
    }

    private static void createLooks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS looks (path TEXT PRIMARY KEY, color TEXT, shape TEXT, label TEXT, " +
                "size INTEGER DEFAULT 1, fx TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE files ADD COLUMN hash TEXT");
            db.execSQL("ALTER TABLE files ADD COLUMN phash TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_hash ON files(hash)");
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_size ON files(size)");
        }
        if (oldV < 3) createLooks(db);
        if (oldV < 4) Trash.createTable(db);
        if (oldV < 5) {
            if (oldV >= 4) db.execSQL("ALTER TABLE trash ADD COLUMN note TEXT");
            db.execSQL("ALTER TABLE files ADD COLUMN fp TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_fp ON files(fp)");
        }
    }

    static final String LOOK = "(SELECT IFNULL(color,'')||'|'||IFNULL(shape,'')||'|'||IFNULL(label,'')||'|'||IFNULL(size,1)||'|'||IFNULL(fx,'') FROM looks l WHERE l.path=files.path)";

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
                "(SELECT GROUP_CONCAT(tag_id) FROM file_tags t WHERE t.path=files.path), " + LOOK + " FROM files" +
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
                o.put("look", c.isNull(13) ? "" : c.getString(13));
                items.put(o);
            }
        }
        out.put("items", items);
        return out.toString();
    }

    public String queryPaths(String filterJson, int limit) throws JSONException {
        JSONObject f = new JSONObject(filterJson);
        List<String> args = new ArrayList<>();
        String w = where(f, args);
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT path FROM files" + w + order(f.optString("sort")) +
                " LIMIT " + Math.max(1, Math.min(20000, limit)), args.toArray(new String[0]))) {
            while (c.moveToNext()) out.put(c.getString(0));
        }
        return out.toString();
    }

    /** Local files matching fleet fingerprints, so remote results can say "also on this phone". */
    public String localByFp(String fpsJson) throws JSONException {
        JSONArray fps = new JSONArray(fpsJson);
        JSONObject out = new JSONObject();
        SQLiteDatabase db = getReadableDatabase();
        for (int i = 0; i < fps.length() && i < 1000; i++) {
            String fp = fps.getString(i);
            if (fp.isEmpty()) continue;
            try (Cursor c = db.rawQuery("SELECT path FROM files WHERE fp=? LIMIT 5", new String[]{fp})) {
                JSONArray a = new JSONArray();
                while (c.moveToNext()) a.put(c.getString(0));
                if (a.length() > 0) out.put(fp, a);
            }
        }
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
        try (Cursor c = db.rawQuery("SELECT color, shape, label, size, fx FROM looks WHERE path=?", new String[]{path})) {
            if (c.moveToFirst()) {
                o.put("look", new JSONObject().put("color", c.getString(0)).put("shape", c.getString(1))
                        .put("label", c.isNull(2) ? "" : c.getString(2)).put("size", c.getInt(3)).put("fx", c.isNull(4) ? "" : c.getString(4)));
            }
        }
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

    // ---------------------------------------------------------------- duplicates

    private static final String DUPE_GROUPS = "SELECT hash, COUNT(*) AS n, MAX(size) AS sz FROM files " +
            "WHERE hash IS NOT NULL AND hash NOT LIKE 'p:%' GROUP BY hash HAVING n > 1";

    public String dupes(int offset, int limit, String kind) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        String base = kind == null || kind.isEmpty() ? DUPE_GROUPS
                : DUPE_GROUPS.replace("WHERE hash", "WHERE kind=" + android.database.DatabaseUtils.sqlEscapeString(kind) + " AND hash");
        JSONObject out = new JSONObject();
        try (Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(n),0), COALESCE(SUM(sz*(n-1)),0) FROM (" + base + ")", null)) {
            if (c.moveToFirst()) { out.put("groups", c.getLong(0)); out.put("files", c.getLong(1)); out.put("wasted", c.getLong(2)); }
        }
        JSONArray groups = new JSONArray();
        try (Cursor g = db.rawQuery(base + " ORDER BY sz*(n-1) DESC LIMIT " + Math.max(1, limit) + " OFFSET " + Math.max(0, offset), null)) {
            while (g.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("hash", g.getString(0));
                o.put("count", g.getLong(1));
                o.put("size", g.getLong(2));
                JSONArray files = new JSONArray();
                try (Cursor c = db.rawQuery("SELECT path,name,parent,ext,kind,size,mtime,dt,hint,ai,shot,lat," +
                        "(SELECT GROUP_CONCAT(tag_id) FROM file_tags t WHERE t.path=files.path), " + LOOK + " FROM files WHERE hash=? ORDER BY dt ASC",
                        new String[]{g.getString(0)})) {
                    while (c.moveToNext()) {
                        files.put(new JSONObject().put("path", c.getString(0)).put("name", c.getString(1))
                                .put("parent", c.getString(2)).put("ext", c.getString(3)).put("kind", c.getString(4))
                                .put("size", c.getLong(5)).put("mtime", c.getLong(6)).put("dt", c.getLong(7))
                                .put("hint", c.isNull(8) ? "" : c.getString(8)).put("ai", c.getInt(9)).put("shot", c.getInt(10))
                                .put("gps", !c.isNull(11)).put("tags", c.isNull(12) ? "" : c.getString(12))
                                .put("look", c.isNull(13) ? "" : c.getString(13)));
                    }
                }
                o.put("files", files);
                groups.put(o);
            }
        }
        out.put("items", groups);
        return out.toString();
    }

    // ---------------------------------------------------------------- visualizer data

    /** Immediate children of a folder with the total size of everything beneath each one. */
    public String tree(String root) throws JSONException {
        Map<String, long[]> sums = new HashMap<>();
        long hereBytes = 0, hereCount = 0;
        String prefix = root.endsWith("/") ? root : root + "/";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT parent, SUM(size), COUNT(*) FROM files " +
                "WHERE parent=? OR parent LIKE ? GROUP BY parent", new String[]{root, likeEscape(prefix) + "%"})) {
            while (c.moveToNext()) {
                String parent = c.getString(0);
                if (parent.equals(root)) { hereBytes += c.getLong(1); hereCount += c.getLong(2); continue; }
                if (!parent.startsWith(prefix)) continue;
                String rest = parent.substring(prefix.length());
                int slash = rest.indexOf('/');
                String child = prefix + (slash < 0 ? rest : rest.substring(0, slash));
                long[] v = sums.get(child);
                if (v == null) { v = new long[2]; sums.put(child, v); }
                v[0] += c.getLong(1);
                v[1] += c.getLong(2);
            }
        }
        List<Map.Entry<String, long[]>> list = new ArrayList<>(sums.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        JSONArray kids = new JSONArray();
        for (Map.Entry<String, long[]> e : list) {
            kids.put(new JSONObject().put("path", e.getKey()).put("bytes", e.getValue()[0]).put("count", e.getValue()[1]).put("dir", true));
        }
        if (hereCount > 0) kids.put(new JSONObject().put("path", root).put("bytes", hereBytes).put("count", hereCount).put("dir", false));
        return new JSONObject().put("root", root).put("items", kids).toString();
    }

    private static String likeEscape(String s) {
        return s.replace("%", "_");
    }

    public String sizeHist() throws JSONException {
        long[] edges = {1, 1024L, 10240L, 102400L, 1048576L, 10485760L, 104857600L, 1073741824L, Long.MAX_VALUE};
        String[] labels = {"under 1 KB", "1 to 10 KB", "10 to 100 KB", "100 KB to 1 MB", "1 to 10 MB", "10 to 100 MB", "100 MB to 1 GB", "over 1 GB"};
        JSONArray out = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        for (int i = 0; i < labels.length; i++) {
            try (Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(size),0) FROM files WHERE size>=? AND size<?",
                    new String[]{String.valueOf(i == 0 ? 0 : edges[i]), String.valueOf(edges[i + 1])})) {
                if (c.moveToFirst()) out.put(new JSONObject().put("label", labels[i]).put("count", c.getLong(0)).put("bytes", c.getLong(1)));
            }
        }
        return out.toString();
    }

    public String gps() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT path, lat, lon, kind, dt FROM files WHERE lat IS NOT NULL " +
                "ORDER BY dt DESC LIMIT 20000", null)) {
            while (c.moveToNext()) {
                out.put(new JSONArray().put(c.getString(0)).put(c.getDouble(1)).put(c.getDouble(2)).put(c.getString(3)).put(c.getLong(4)));
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- looks

    private static ContentValues lookValues(JSONObject l) {
        ContentValues v = new ContentValues();
        v.put("color", l.optString("color", ""));
        v.put("shape", l.optString("shape", ""));
        String label = l.optString("label", "").trim();
        v.put("label", label.length() > 24 ? label.substring(0, 24) : label);
        v.put("size", Math.max(0, Math.min(3, l.optInt("size", 1))));
        v.put("fx", l.optString("fx", ""));
        return v;
    }

    public void setLook(String path, String lookJson) throws JSONException {
        ContentValues v = lookValues(new JSONObject(lookJson));
        v.put("path", path);
        getWritableDatabase().insertWithOnConflict("looks", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void clearLook(String path) {
        getWritableDatabase().delete("looks", "path=?", new String[]{path});
    }

    /** Gives every file matching the filter the same look, or clears them. Nicknames are not copied in bulk. */
    public long lookMatching(String filterJson, String lookJson) throws JSONException {
        JSONObject f = new JSONObject(filterJson);
        List<String> args = new ArrayList<>();
        String w = where(f, args);
        SQLiteDatabase db = getWritableDatabase();
        if (lookJson == null || lookJson.isEmpty()) {
            return db.delete("looks", "path IN (SELECT path FROM files" + w + ")", args.toArray(new String[0]));
        }
        ContentValues v = lookValues(new JSONObject(lookJson));
        List<Object> a = new ArrayList<>();
        a.add(v.getAsString("color"));
        a.add(v.getAsString("shape"));
        a.add(v.getAsInteger("size"));
        a.add(v.getAsString("fx"));
        a.addAll(args);
        long before = count(db, "SELECT COUNT(*) FROM looks");
        db.execSQL("INSERT OR REPLACE INTO looks(path, color, shape, label, size, fx) SELECT path, ?, ?, " +
                "(SELECT label FROM looks o WHERE o.path=files.path), ?, ? FROM files" + w, a.toArray());
        long n = count(db, "SELECT COUNT(*) FROM files" + w.replace("?", "?"), args);
        return Math.max(n, count(db, "SELECT COUNT(*) FROM looks") - before);
    }

    private static long count(SQLiteDatabase db, String sql, List<String> args) {
        try (Cursor c = db.rawQuery(sql, args.toArray(new String[0]))) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    public String looks() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT l.path, l.color, l.shape, l.label, l.size, l.fx, f.name, f.kind " +
                "FROM looks l JOIN files f ON f.path=l.path ORDER BY (l.label IS NULL OR l.label=''), l.label COLLATE NOCASE, f.name LIMIT 400", null)) {
            while (c.moveToNext()) {
                out.put(new JSONObject().put("path", c.getString(0)).put("look", lookStr(c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getString(5)))
                        .put("name", c.getString(6)).put("kind", c.getString(7)));
            }
        }
        return out.toString();
    }

    private static String lookStr(String color, String shape, String label, int size, String fx) {
        return (color == null ? "" : color) + "|" + (shape == null ? "" : shape) + "|" + (label == null ? "" : label) + "|" + size + "|" + (fx == null ? "" : fx);
    }

    // ---------------------------------------------------------------- board

    /**
     * Everything the Board needs for one folder in a single pass: a chip per subfolder (bytes, file count,
     * type mix, monthly activity, tags inside, the marked files inside), the folder's own files to draw as
     * components, and duplicate links between chips and files.
     */
    public String board(String root) throws JSONException {
        String prefix = root.endsWith("/") ? root : root + "/";
        Map<String, JSONObject> kids = new HashMap<>();
        Map<String, Map<String, Long>> kidKinds = new HashMap<>();
        Map<String, Map<String, Long>> kidMonths = new HashMap<>();
        Map<String, java.util.Set<String>> kidTags = new HashMap<>();
        Map<String, JSONArray> kidMarks = new HashMap<>();
        Map<String, Long> months = new java.util.TreeMap<>();
        Map<String, List<String>> hashBuckets = new HashMap<>();
        List<JSONObject> here = new ArrayList<>();
        java.text.SimpleDateFormat ym = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US);
        long total = 0, count = 0;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT path, name, parent, kind, size, dt, hash, ai, lat, " +
                "(SELECT GROUP_CONCAT(tag_id) FROM file_tags t WHERE t.path=files.path), " + LOOK +
                " FROM files WHERE parent=? OR parent LIKE ?", new String[]{root, likeEscape(prefix) + "%"})) {
            while (c.moveToNext()) {
                String path = c.getString(0), parent = c.getString(2), kind = c.getString(3);
                if (!parent.equals(root) && !parent.startsWith(prefix)) continue;
                long size = c.getLong(4), dt = c.getLong(5);
                String hash = c.getString(6), tagStr = c.getString(9), look = c.getString(10);
                String month = ym.format(new java.util.Date(dt));
                months.put(month, (months.containsKey(month) ? months.get(month) : 0L) + 1);
                total += size;
                count++;
                String bucket;
                if (parent.equals(root)) {
                    bucket = "";
                    JSONObject f = new JSONObject().put("path", path).put("name", c.getString(1)).put("kind", kind)
                            .put("size", size).put("dt", dt).put("ai", c.getInt(7)).put("gps", !c.isNull(8))
                            .put("tags", tagStr == null ? "" : tagStr).put("look", look == null ? "" : look)
                            .put("hash", hash == null ? "" : hash);
                    here.add(f);
                } else {
                    String rest = parent.substring(prefix.length());
                    int slash = rest.indexOf('/');
                    bucket = prefix + (slash < 0 ? rest : rest.substring(0, slash));
                    JSONObject k = kids.get(bucket);
                    if (k == null) {
                        k = new JSONObject().put("path", bucket).put("bytes", 0L).put("count", 0L).put("last", 0L);
                        kids.put(bucket, k);
                        kidKinds.put(bucket, new HashMap<>());
                        kidMonths.put(bucket, new HashMap<>());
                        kidTags.put(bucket, new java.util.HashSet<>());
                        kidMarks.put(bucket, new JSONArray());
                    }
                    k.put("bytes", k.getLong("bytes") + size);
                    k.put("count", k.getLong("count") + 1);
                    if (dt > k.getLong("last")) k.put("last", dt);
                    Map<String, Long> km = kidKinds.get(bucket);
                    km.put(kind, (km.containsKey(kind) ? km.get(kind) : 0L) + size);
                    Map<String, Long> mm = kidMonths.get(bucket);
                    mm.put(month, (mm.containsKey(month) ? mm.get(month) : 0L) + 1);
                    if (tagStr != null) for (String t : tagStr.split(",")) kidTags.get(bucket).add(t);
                    JSONArray marks = kidMarks.get(bucket);
                    if ((look != null || tagStr != null) && marks.length() < 16) {
                        marks.put(new JSONObject().put("path", path).put("name", c.getString(1)).put("kind", kind)
                                .put("look", look == null ? "" : look).put("tags", tagStr == null ? "" : tagStr).put("dt", dt));
                    }
                }
                if (hash != null) {
                    List<String> l = hashBuckets.get(hash);
                    if (l == null) { l = new ArrayList<>(); hashBuckets.put(hash, l); }
                    l.add(bucket.isEmpty() ? "f:" + path : bucket);
                }
            }
        }
        // duplicate links: between chips, and between this folder's own files
        Map<String, Integer> links = new HashMap<>();
        Map<String, Integer> dupeCount = new HashMap<>();
        for (List<String> l : hashBuckets.values()) {
            if (l.size() < 2) continue;
            for (String b : l) dupeCount.put(b, (dupeCount.containsKey(b) ? dupeCount.get(b) : 0) + 1);
            for (int i = 0; i < l.size(); i++) {
                for (int j = i + 1; j < l.size(); j++) {
                    String a = l.get(i), b = l.get(j);
                    if (a.equals(b)) continue;
                    String key = a.compareTo(b) < 0 ? a + "\n" + b : b + "\n" + a;
                    links.put(key, (links.containsKey(key) ? links.get(key) : 0) + 1);
                }
            }
        }
        List<JSONObject> kidList = new ArrayList<>(kids.values());
        kidList.sort((a, b) -> Long.compare(b.optLong("bytes"), a.optLong("bytes")));
        JSONArray kidsOut = new JSONArray();
        for (JSONObject k : kidList) {
            String b = k.getString("path");
            JSONObject kinds = new JSONObject();
            for (Map.Entry<String, Long> e : kidKinds.get(b).entrySet()) kinds.put(e.getKey(), e.getValue());
            JSONObject mo = new JSONObject();
            for (Map.Entry<String, Long> e : kidMonths.get(b).entrySet()) mo.put(e.getKey(), e.getValue());
            JSONArray tg = new JSONArray();
            for (String t : kidTags.get(b)) tg.put(t);
            k.put("kinds", kinds).put("months", mo).put("tags", tg).put("marks", kidMarks.get(b))
                    .put("dupes", dupeCount.containsKey(b) ? dupeCount.get(b) : 0);
            kidsOut.put(k);
        }
        // this folder's own files: every marked file, then the biggest, capped
        here.sort((a, b) -> {
            boolean la = !a.optString("look").isEmpty() || !a.optString("tags").isEmpty();
            boolean lb = !b.optString("look").isEmpty() || !b.optString("tags").isEmpty();
            if (la != lb) return la ? -1 : 1;
            return Long.compare(b.optLong("size"), a.optLong("size"));
        });
        JSONArray files = new JSONArray();
        for (int i = 0; i < here.size() && i < 90; i++) {
            JSONObject f = here.get(i);
            String h = f.optString("hash");
            f.put("dupe", !h.isEmpty() && hashBuckets.containsKey(h) && hashBuckets.get(h).size() > 1);
            files.put(f);
        }
        JSONArray linkOut = new JSONArray();
        for (Map.Entry<String, Integer> e : links.entrySet()) {
            String[] ab = e.getKey().split("\n");
            linkOut.put(new JSONArray().put(ab[0]).put(ab[1]).put(e.getValue()));
        }
        JSONObject mo = new JSONObject();
        for (Map.Entry<String, Long> e : months.entrySet()) mo.put(e.getKey(), e.getValue());
        return new JSONObject().put("root", root).put("bytes", total).put("count", count).put("hereCount", here.size())
                .put("kids", kidsOut).put("files", files).put("links", linkOut).put("months", mo).toString();
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

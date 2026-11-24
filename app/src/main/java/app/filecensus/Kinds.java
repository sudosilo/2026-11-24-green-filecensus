// GREEN PHONE EDIT
package app.filecensus;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Maps file extensions to a broad kind and a readable type label. */
public final class Kinds {
    private Kinds() { }

    private static final Map<String, String> KIND = new HashMap<>();
    private static final Map<String, String> LABEL = new HashMap<>();

    private static void put(String kind, String label, String... exts) {
        for (String e : exts) { KIND.put(e, kind); LABEL.put(e, label); }
    }

    static {
        put("image", "JPEG image", "jpg", "jpeg", "jpe", "jfif");
        put("image", "PNG image", "png");
        put("image", "WebP image", "webp");
        put("image", "HEIC image", "heic", "heif", "hif");
        put("image", "AVIF image", "avif");
        put("image", "GIF image", "gif");
        put("image", "Bitmap image", "bmp");
        put("image", "RAW photo", "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf");
        put("image", "TIFF image", "tif", "tiff");
        put("image", "SVG drawing", "svg");
        put("image", "Icon", "ico");
        put("video", "MP4 video", "mp4", "m4v");
        put("video", "QuickTime video", "mov");
        put("video", "Matroska video", "mkv");
        put("video", "WebM video", "webm");
        put("video", "3GP video", "3gp", "3g2");
        put("video", "AVI video", "avi");
        put("video", "Transport stream", "ts", "mts", "m2ts");
        put("audio", "MP3 audio", "mp3");
        put("audio", "AAC audio", "m4a", "aac");
        put("audio", "Ogg audio", "ogg", "oga", "opus");
        put("audio", "WAV audio", "wav");
        put("audio", "FLAC audio", "flac");
        put("audio", "AMR voice", "amr", "awb");
        put("audio", "MIDI", "mid", "midi");
        put("document", "PDF document", "pdf");
        put("document", "Word document", "doc", "docx", "odt", "rtf");
        put("document", "Spreadsheet", "xls", "xlsx", "ods");
        put("document", "Presentation", "ppt", "pptx", "odp");
        put("document", "E-book", "epub", "mobi", "azw3");
        put("text", "Text", "txt", "log", "nfo");
        put("text", "Markdown", "md", "markdown");
        put("text", "CSV data", "csv", "tsv");
        put("text", "Subtitles", "srt", "vtt", "ass");
        put("code", "HTML page", "html", "htm");
        put("code", "JSON data", "json", "jsonl");
        put("code", "XML data", "xml", "plist");
        put("code", "YAML config", "yml", "yaml", "toml", "ini", "conf", "cfg", "properties", "env");
        put("code", "JavaScript", "js", "mjs", "cjs", "jsx", "ts", "tsx");
        put("code", "Stylesheet", "css", "scss");
        put("code", "Python", "py");
        put("code", "Shell script", "sh", "bash", "zsh");
        put("code", "Java or Kotlin", "java", "kt", "kts", "gradle");
        put("code", "C or C++", "c", "h", "cpp", "hpp", "cc");
        put("code", "Source code", "go", "rs", "rb", "php", "lua", "swift", "sql", "dart");
        put("archive", "ZIP archive", "zip");
        put("archive", "Archive", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst");
        put("apk", "Android app", "apk", "apks", "xapk", "apkm");
        put("data", "Database", "db", "sqlite", "sqlite3", "realm");
        put("data", "Font", "ttf", "otf", "woff", "woff2");
        put("data", "Disk image", "iso", "img", "bin");
        put("data", "Contact card", "vcf");
        put("data", "Calendar", "ics");
        put("data", "3D model", "obj", "stl", "glb", "gltf", "fbx");
    }

    public static String ext(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static String kind(String ext) {
        String k = KIND.get(ext);
        return k == null ? "other" : k;
    }

    public static String label(String ext) {
        String l = LABEL.get(ext);
        if (l != null) return l;
        return ext.isEmpty() ? "File" : ext.toUpperCase(Locale.US) + " file";
    }
}

// GREEN PHONE EDIT
package app.filecensus;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.ParcelFileDescriptor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Pulls metadata, provenance and a one-line content hint out of a file. */
public final class Meta {

    public long taken;
    public String hint = "";
    public Double lat, lon;
    public String camera;
    public int width, height;
    public long duration;
    public boolean ai;
    public String aiInfo;
    public int shot; // 0 none, 1 Android, 2 iOS
    public String shotInfo;
    public final JSONObject extra = new JSONObject();

    private Meta() { }

    // ------------------------------------------------------------------ entry

    public static Meta extract(Context ctx, File f, String kind, String ext) {
        Meta m = new Meta();
        try {
            if (f.length() == 0) { m.hint = "Empty file"; return m; }
            switch (kind) {
                case "image": m.image(f, ext); break;
                case "video": m.video(f); break;
                case "audio": m.audio(f); break;
                case "text": case "code": m.text(f, ext); break;
                case "apk": m.apk(ctx, f); break;
                case "document":
                    if ("pdf".equals(ext)) m.pdf(f);
                    else if (ext.endsWith("x") || "epub".equals(ext) || ext.startsWith("od")) m.office(f, ext);
                    else m.hint = Kinds.label(ext);
                    break;
                case "archive":
                    if ("zip".equals(ext)) m.zip(f); else m.hint = Kinds.label(ext) + ", " + Fmt.size(f.length());
                    break;
                default: m.magic(f, ext); break;
            }
            if (kind.equals("image") || kind.equals("video") || kind.equals("audio") || "pdf".equals(ext)) {
                m.provenanceScan(f);
            }
            m.screenshot(f, ext);
            m.finishHint(kind, ext);
        } catch (Throwable t) {
            if (m.hint.isEmpty()) m.hint = Kinds.label(ext) + " (unreadable: " + t.getClass().getSimpleName() + ")";
        }
        return m;
    }

    // ------------------------------------------------------------------ images

    private void image(File f, String ext) throws IOException, JSONException {
        String path = f.getAbsolutePath();
        if ("png".equals(ext)) pngChunks(f);
        if (!"svg".equals(ext) && !"ico".equals(ext) && !"gif".equals(ext) && !"bmp".equals(ext)) {
            try {
                ExifInterface ex = new ExifInterface(path);
                String dto = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                if (dto == null) dto = ex.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED);
                if (dto == null) dto = ex.getAttribute(ExifInterface.TAG_DATETIME);
                long t = exifTime(dto, ex.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
                if (t > 0) taken = t;
                String make = clean(ex.getAttribute(ExifInterface.TAG_MAKE));
                String model = clean(ex.getAttribute(ExifInterface.TAG_MODEL));
                if (model != null) {
                    camera = (make != null && !model.toLowerCase(Locale.US).startsWith(make.toLowerCase(Locale.US)))
                            ? make + " " + model : model;
                } else if (make != null) camera = make;
                float[] ll = new float[2];
                if (ex.getLatLong(ll) && !(ll[0] == 0f && ll[1] == 0f)) { lat = (double) ll[0]; lon = (double) ll[1]; }
                width = ex.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
                height = ex.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
                putIf("Lens", ex.getAttribute("LensModel"));
                putIf("Software", ex.getAttribute(ExifInterface.TAG_SOFTWARE));
                putIf("Artist", ex.getAttribute(ExifInterface.TAG_ARTIST));
                putIf("Description", ex.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
                putIf("User comment", ex.getAttribute(ExifInterface.TAG_USER_COMMENT));
                putIf("Exposure", ex.getAttribute(ExifInterface.TAG_EXPOSURE_TIME));
                putIf("F number", ex.getAttribute(ExifInterface.TAG_F_NUMBER));
                putIf("ISO", ex.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS));
                putIf("Focal length", ex.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
                putIf("Altitude", ex.getAttribute(ExifInterface.TAG_GPS_ALTITUDE));
                putIf("Orientation", ex.getAttribute(ExifInterface.TAG_ORIENTATION));
                putIf("Original time", dto);
                putIf("Time zone", ex.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
                String sw = ex.getAttribute(ExifInterface.TAG_SOFTWARE);
                if (sw != null) aiFromText("EXIF software", sw);
                String desc = ex.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
                if (desc != null) aiFromText("EXIF description", desc);
            } catch (Throwable ignored) { }
        }
        if (width <= 0 || height <= 0) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth > 0) { width = o.outWidth; height = o.outHeight; }
        }
    }

    private static long exifTime(String s, String offset) {
        if (s == null || s.trim().length() < 19 || s.startsWith("0000")) return 0;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            if (offset != null && offset.matches("[+-]\\d{2}:\\d{2}")) fmt.setTimeZone(TimeZone.getTimeZone("GMT" + offset));
            Date d = fmt.parse(s.trim().substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ PNG text chunks

    private final Map<String, String> pngText = new LinkedHashMap<>();
    private boolean c2pa;
    private String c2paGenerator;

    private void pngChunks(File f) throws IOException, JSONException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f), 65536))) {
            byte[] sig = new byte[8];
            in.readFully(sig);
            if ((sig[0] & 0xff) != 0x89 || sig[1] != 'P') return;
            int guard = 0;
            while (guard++ < 10000) {
                int len;
                try { len = in.readInt(); } catch (EOFException e) { break; }
                byte[] typeB = new byte[4];
                in.readFully(typeB);
                String type = new String(typeB, StandardCharsets.ISO_8859_1);
                if (len < 0) break;
                boolean wanted = type.equals("tEXt") || type.equals("zTXt") || type.equals("iTXt") || type.equals("caBX") || type.equals("eXIf");
                if (wanted && len < 8 * 1024 * 1024) {
                    byte[] data = new byte[len];
                    in.readFully(data);
                    handlePngChunk(type, data);
                } else {
                    skipFully(in, len);
                }
                skipFully(in, 4); // CRC
                if (type.equals("IEND")) break;
            }
        }
        for (Map.Entry<String, String> e : pngText.entrySet()) {
            extra.put("PNG " + e.getKey(), trim(e.getValue(), 1500));
        }
        pngAi();
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) { if (in.read() < 0) throw new EOFException(); s = 1; }
            n -= s;
        }
    }

    private void handlePngChunk(String type, byte[] d) {
        try {
            switch (type) {
                case "caBX": c2pa = true; scanC2paBytes(d, d.length); break;
                case "eXIf": extra.put("PNG eXIf", d.length + " bytes of EXIF"); break;
                case "tEXt": {
                    int z = indexOf0(d, 0);
                    if (z < 0) return;
                    pngText.put(new String(d, 0, z, StandardCharsets.ISO_8859_1),
                            new String(d, z + 1, d.length - z - 1, StandardCharsets.ISO_8859_1));
                    break;
                }
                case "zTXt": {
                    int z = indexOf0(d, 0);
                    if (z < 0 || z + 2 > d.length) return;
                    byte[] raw = inflate(d, z + 2, d.length - z - 2);
                    pngText.put(new String(d, 0, z, StandardCharsets.ISO_8859_1), new String(raw, StandardCharsets.ISO_8859_1));
                    break;
                }
                case "iTXt": {
                    int z = indexOf0(d, 0);
                    if (z < 0 || z + 3 > d.length) return;
                    String key = new String(d, 0, z, StandardCharsets.ISO_8859_1);
                    boolean compressed = d[z + 1] != 0;
                    int lang = indexOf0(d, z + 3);
                    if (lang < 0) return;
                    int tkey = indexOf0(d, lang + 1);
                    if (tkey < 0) return;
                    int start = tkey + 1;
                    byte[] txt = compressed ? inflate(d, start, d.length - start) : java.util.Arrays.copyOfRange(d, start, d.length);
                    pngText.put(key, new String(txt, StandardCharsets.UTF_8));
                    break;
                }
                default: break;
            }
        } catch (Throwable ignored) { }
    }

    private static int indexOf0(byte[] d, int from) {
        for (int i = from; i < d.length; i++) if (d[i] == 0) return i;
        return -1;
    }

    private static byte[] inflate(byte[] d, int off, int len) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(d, off, len);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!inf.finished() && out.size() < 4 * 1024 * 1024) {
            int n = inf.inflate(buf);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            out.write(buf, 0, n);
        }
        inf.end();
        return out.toByteArray();
    }

    private static final Pattern A1111_MODEL = Pattern.compile("Model: ([^,\\n]+)");
    private static final Pattern A1111_STEPS = Pattern.compile("Steps: (\\d+)");
    private static final Pattern A1111_SAMPLER = Pattern.compile("Sampler: ([^,\\n]+)");
    private static final Pattern A1111_SEED = Pattern.compile("Seed: (\\d+)");
    private static final Pattern JSON_CKPT = Pattern.compile("\"(?:ckpt_name|unet_name|model_name|model)\"\\s*:\\s*\"([^\"]{1,120})\"");
    private static final Pattern JSON_PROMPT = Pattern.compile("\"(?:text|prompt|positive)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){3,600})\"");

    private void pngAi() {
        String params = pngText.get("parameters");
        if (params != null && (params.contains("Steps:") || params.contains("Sampler:"))) {
            String prompt = params;
            int neg = prompt.indexOf("Negative prompt:");
            int st = prompt.indexOf("Steps:");
            int cut = neg >= 0 ? neg : st;
            if (cut > 0) prompt = prompt.substring(0, cut);
            StringBuilder sb = new StringBuilder("Stable Diffusion WebUI parameters");
            String model = group(A1111_MODEL, params);
            if (model != null) sb.append(", model ").append(model.trim());
            String steps = group(A1111_STEPS, params);
            if (steps != null) sb.append(", ").append(steps).append(" steps");
            String sampler = group(A1111_SAMPLER, params);
            if (sampler != null) sb.append(", sampler ").append(sampler.trim());
            String seed = group(A1111_SEED, params);
            if (seed != null) sb.append(", seed ").append(seed);
            markAi(sb.toString(), prompt.trim());
            return;
        }
        String comfy = pngText.get("prompt");
        String workflow = pngText.get("workflow");
        if ((comfy != null && comfy.trim().startsWith("{")) || workflow != null) {
            String src = comfy != null ? comfy : workflow;
            String model = group(JSON_CKPT, src);
            String prompt = group(JSON_PROMPT, src);
            markAi("ComfyUI workflow" + (model != null ? ", model " + model : ""), unescape(prompt));
            return;
        }
        if (pngText.containsKey("invokeai_metadata") || pngText.containsKey("sd-metadata") || pngText.containsKey("Dream")) {
            String src = pngText.containsKey("invokeai_metadata") ? pngText.get("invokeai_metadata")
                    : pngText.containsKey("sd-metadata") ? pngText.get("sd-metadata") : pngText.get("Dream");
            String model = group(JSON_CKPT, src);
            String prompt = group(JSON_PROMPT, src);
            markAi("InvokeAI metadata" + (model != null ? ", model " + model : ""), prompt != null ? unescape(prompt) : trim(src, 300));
            return;
        }
        String sw = pngText.get("Software");
        String comment = pngText.get("Comment");
        if ((sw != null && sw.contains("NovelAI")) || (comment != null && comment.contains("\"steps\"") && comment.contains("\"sampler\""))) {
            String desc = pngText.get("Description");
            String source = pngText.get("Source");
            markAi("NovelAI" + (source != null ? ", " + source : ""), desc);
            return;
        }
        if (pngText.containsKey("generation_data") || pngText.containsKey("Generation data")) {
            String g = pngText.containsKey("generation_data") ? pngText.get("generation_data") : pngText.get("Generation data");
            markAi("Generation parameters", trim(g, 400));
            return;
        }
        for (Map.Entry<String, String> e : pngText.entrySet()) aiFromText("PNG " + e.getKey(), e.getValue());
    }

    // ------------------------------------------------------------------ provenance

    private static final String[] GENERATORS = {
            "Adobe Firefly", "Firefly", "DALL-E", "DALL·E", "dall-e", "OpenAI", "ChatGPT", "Sora", "Midjourney",
            "Stable Diffusion", "stable-diffusion", "SDXL", "ComfyUI", "Automatic1111", "NovelAI", "Leonardo.Ai",
            "Ideogram", "Flux", "Black Forest Labs", "Bing Image Creator", "Microsoft Designer", "Designer",
            "Meta AI", "Imagine with Meta", "Runway", "Pika", "Kling", "Luma", "Grok", "Aurora", "Galaxy AI",
            "Generative Fill", "Photoshop", "Canva", "Krea", "Playground", "Recraft", "Truepic"
    };

    /** Byte-level scan of the head and tail of a file for C2PA / JUMBF manifests and IPTC AI markers. */
    private void provenanceScan(File f) throws IOException {
        long len = f.length();
        int head = (int) Math.min(len, 1536 * 1024);
        int tail = (int) Math.min(Math.max(0, len - head), 512 * 1024);
        byte[] buf = new byte[head + tail];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.readFully(buf, 0, head);
            if (tail > 0) { r.seek(len - tail); r.readFully(buf, head, tail); }
        }
        scanC2paBytes(buf, buf.length);
        String s = new String(buf, StandardCharsets.ISO_8859_1);
        boolean trained = s.contains("trainedAlgorithmicMedia");
        boolean composite = s.contains("compositeWithTrainedAlgorithmicMedia");
        if (c2pa) {
            StringBuilder sb = new StringBuilder("C2PA content credentials");
            if (c2paGenerator != null) sb.append(", claim generator ").append(c2paGenerator);
            if (s.contains("c2pa.created")) sb.append(", action created");
            if (s.contains("c2pa.edited")) sb.append(", action edited");
            if (trained) sb.append(composite ? ", composite with AI" : ", AI generated (IPTC trainedAlgorithmicMedia)");
            String gen = findGenerator(s);
            if (gen != null && (c2paGenerator == null || !c2paGenerator.contains(gen))) sb.append(", mentions ").append(gen);
            try { extra.put("C2PA", sb.toString()); } catch (JSONException ignored) { }
            if (trained || isAiGen(gen) || isAiGen(c2paGenerator == null ? null : findGenerator(c2paGenerator))) markAi(sb.toString(), null);
            else if (aiInfo == null) aiInfo = sb.toString();
        } else if (trained) {
            markAi(composite ? "IPTC digital source: composite with AI" : "IPTC digital source: AI generated", null);
        }
        if (!ai && s.contains("<x:xmpmeta")) {
            int i = s.indexOf("CreatorTool");
            if (i > 0) {
                String tool = s.substring(i, Math.min(s.length(), i + 120));
                String gen = findGenerator(tool);
                if (isAiGen(gen)) markAi("XMP creator tool mentions " + gen, null);
            }
        }
    }

    private void scanC2paBytes(byte[] b, int n) {
        String s = new String(b, 0, n, StandardCharsets.ISO_8859_1);
        if (s.contains("c2pa") && (s.contains("jumb") || s.contains("jumd") || s.contains("c2pa.claim") || s.contains("c2pa.manifest"))) c2pa = true;
        int i = s.indexOf("claim_generator");
        if (i >= 0 && c2paGenerator == null) {
            int j = i + "claim_generator".length();
            // Skip CBOR header bytes, then take the printable run that follows.
            while (j < s.length() && j < i + 40 && !printable(s.charAt(j))) j++;
            if (s.startsWith("_info", j)) {
                int k = s.indexOf("name", j);
                if (k > 0 && k < j + 60) { j = k + 4; while (j < s.length() && j < k + 20 && !printable(s.charAt(j))) j++; }
            }
            int start = j;
            while (j < s.length() && j - start < 120 && printable(s.charAt(j))) j++;
            if (j - start >= 3) c2paGenerator = s.substring(start, j).trim();
        }
    }

    private static boolean isAiGen(String gen) {
        return gen != null && !gen.equals("Photoshop") && !gen.equals("Canva") && !gen.equals("Designer") && !gen.equals("Truepic");
    }

    private static boolean printable(char c) { return c >= 0x20 && c < 0x7f; }

    private static String findGenerator(String s) {
        for (String g : GENERATORS) if (s.contains(g)) return g;
        return null;
    }

    private void aiFromText(String where, String text) {
        if (text == null || ai) return;
        String low = text.toLowerCase(Locale.US);
        boolean words = low.contains("ai generated") || low.contains("ai-generated") || low.contains("generated by ai")
                || low.contains("negative prompt") || low.contains("cfg scale") || low.contains("sampler");
        String gen = findGenerator(text);
        if (!isAiGen(gen)) gen = null;
        if (words || gen != null) markAi(where + (gen != null ? " mentions " + gen : " has generation text"), trim(text, 300));
    }

    private void markAi(String info, String prompt) {
        ai = true;
        aiInfo = info;
        try {
            extra.put("AI source", info);
            if (prompt != null && !prompt.trim().isEmpty()) extra.put("AI prompt", trim(prompt.trim(), 1500));
        } catch (JSONException ignored) { }
    }

    // ------------------------------------------------------------------ screenshots

    private static final Pattern SHOT_TIME = Pattern.compile(
            "(20\\d{2})[-_]?(\\d{2})[-_]?(\\d{2})[-_ T]?(\\d{2})[-_.:]?(\\d{2})[-_.:]?(\\d{2})(?:[-_.]\\d{1,3})?(?:[_-](.+))?");

    private void screenshot(File f, String ext) {
        String name = f.getName();
        String lowPath = f.getAbsolutePath().toLowerCase(Locale.US);
        String lowName = name.toLowerCase(Locale.US);
        String userComment = extra.optString("User comment", "");
        boolean iosComment = userComment.trim().equalsIgnoreCase("Screenshot");
        boolean named = lowName.contains("screenshot") || lowName.startsWith("screen_shot") || lowName.startsWith("scr_")
                || lowPath.contains("/screenshots/");
        if (!named && !iosComment) {
            // iPhone screenshots synced over: IMG_1234.PNG with no camera and no location.
            if ("png".equals(ext) && name.matches("IMG_\\d{3,5}\\.(PNG|png)") && camera == null && lat == null) {
                shot = 2;
                shotInfo = "Likely iOS screenshot (PNG from iPhone naming, no camera, no location)" + dims();
            }
            return;
        }
        if (iosComment || (lowName.startsWith("screenshot ") && lowName.contains(" at "))) {
            shot = 2;
            StringBuilder sb = new StringBuilder("iOS screenshot");
            if (taken > 0) sb.append(", captured ").append(Fmt.dateTime(taken));
            sb.append(", minimal EXIF, no location").append(dims());
            shotInfo = sb.toString();
            return;
        }
        shot = 1;
        StringBuilder sb = new StringBuilder("Android screenshot");
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        Matcher mm = SHOT_TIME.matcher(base);
        if (mm.find()) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                Date d = fmt.parse(mm.group(1) + mm.group(2) + mm.group(3) + mm.group(4) + mm.group(5) + mm.group(6));
                if (d != null) {
                    if (taken == 0) taken = d.getTime();
                    sb.append(", captured ").append(Fmt.dateTime(d.getTime()));
                }
            } catch (ParseException ignored) { }
            String app = mm.group(7);
            if (app != null && !app.isEmpty()) {
                sb.append(", app ").append(app.replace('_', ' '));
                try { extra.put("Screenshot app", app); } catch (JSONException ignored) { }
            }
        } else if (taken > 0) {
            sb.append(", captured ").append(Fmt.dateTime(taken));
        }
        if (camera != null) sb.append(", device ").append(camera);
        String sw = extra.optString("Software", extra.optString("PNG Software", ""));
        if (!sw.isEmpty()) sb.append(", software ").append(sw);
        if (lat != null) sb.append(", has location");
        sb.append(dims());
        shotInfo = sb.toString();
    }

    private String dims() {
        return width > 0 ? ", " + width + "x" + height : "";
    }

    // ------------------------------------------------------------------ video and audio

    private void video(File f) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            duration = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            width = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long rot = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rot == 90 || rot == 270) { int t = width; width = height; height = t; }
            String date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            long t = mp4Time(date);
            if (t > 0) taken = t;
            String loc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            parseIso6709(loc);
            putIf("Rotation", rot > 0 ? String.valueOf(rot) : null);
            putIf("Bitrate", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            putIf("Frame rate", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE));
            putIf("MIME", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
            putIf("Has audio", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            putIf("Container date", date);
            putIf("Title", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            putIf("Author", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR));
            putIf("Location", loc);
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    private void audio(File f) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            duration = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            putIf("Title", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            putIf("Artist", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            putIf("Album", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            putIf("Genre", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE));
            putIf("Year", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR));
            putIf("Bitrate", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            putIf("Container date", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
            long t = mp4Time(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
            if (t > 0) taken = t;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    private static long mp4Time(String s) {
        if (s == null || s.length() < 15 || s.startsWith("1904") || s.startsWith("1970")) return 0;
        String[] fmts = {"yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss"};
        for (String p : fmts) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat(p, Locale.US);
                fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = fmt.parse(s);
                if (d != null) return d.getTime();
            } catch (ParseException ignored) { }
        }
        return 0;
    }

    private static final Pattern ISO6709 = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)");

    private void parseIso6709(String loc) {
        if (loc == null) return;
        Matcher m = ISO6709.matcher(loc);
        if (m.find()) {
            try {
                double a = Double.parseDouble(m.group(1));
                double b = Double.parseDouble(m.group(2));
                if (!(a == 0 && b == 0)) { lat = a; lon = b; }
            } catch (NumberFormatException ignored) { }
        }
    }

    // ------------------------------------------------------------------ documents and text

    private void text(File f, String ext) throws IOException {
        byte[] b = new byte[4096];
        int n;
        try (FileInputStream in = new FileInputStream(f)) { n = Math.max(0, in.read(b)); }
        String s = new String(b, 0, n, StandardCharsets.UTF_8);
        String first = "";
        for (String line : s.split("\\r?\\n")) {
            String t = line.trim();
            if ("html".equals(ext) || "htm".equals(ext)) {
                Matcher m = Pattern.compile("<title>([^<]{1,120})</title>", Pattern.CASE_INSENSITIVE).matcher(s);
                if (m.find()) { first = "Title: " + m.group(1).trim(); break; }
            }
            if (!t.isEmpty() && !t.startsWith("<!") && !t.equals("{") && !t.equals("[") && !t.startsWith("<?xml")) { first = t; break; }
        }
        if (first.isEmpty()) first = "(blank start)";
        hint = Kinds.label(ext) + ": " + trim(first.replaceAll("\\s+", " "), 90);
    }

    private static final Pattern PDF_TITLE = Pattern.compile("/Title\\s*\\(([^)]{1,120})\\)");

    private void pdf(File f) throws IOException {
        int pages = -1;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pr = new PdfRenderer(pfd)) {
            pages = pr.getPageCount();
        } catch (Throwable ignored) { }
        String title = null;
        byte[] b = new byte[(int) Math.min(f.length(), 131072)];
        try (FileInputStream in = new FileInputStream(f)) { int n = in.read(b); if (n < 0) n = 0; title = group(PDF_TITLE, new String(b, 0, n, StandardCharsets.ISO_8859_1)); }
        StringBuilder sb = new StringBuilder("PDF");
        if (pages > 0) sb.append(", ").append(pages).append(pages == 1 ? " page" : " pages");
        if (title != null && !title.trim().isEmpty()) sb.append(", titled \"").append(trim(title.trim(), 70)).append('"');
        if (pages > 0) putIf("Pages", String.valueOf(pages));
        putIf("Title", title);
        hint = sb.toString();
    }

    private static final Pattern DC_TITLE = Pattern.compile("<dc:title[^>]*>([^<]{1,160})</dc:title>");
    private static final Pattern DC_CREATOR = Pattern.compile("<dc:creator[^>]*>([^<]{1,120})</dc:creator>");

    private void office(File f, String ext) {
        String title = null, creator = null;
        int entries = 0;
        try (ZipFile z = new ZipFile(f)) {
            entries = z.size();
            String[] metas = {"docProps/core.xml", "meta.xml", "OEBPS/content.opf", "content.opf", "OPS/content.opf"};
            for (String mpath : metas) {
                ZipEntry e = z.getEntry(mpath);
                if (e == null) continue;
                try (InputStream in = z.getInputStream(e)) {
                    byte[] b = readUpTo(in, 65536);
                    String s = new String(b, StandardCharsets.UTF_8);
                    title = group(DC_TITLE, s);
                    creator = group(DC_CREATOR, s);
                }
                break;
            }
        } catch (Throwable ignored) { }
        StringBuilder sb = new StringBuilder(Kinds.label(ext));
        if (title != null) sb.append(", \"").append(trim(title.trim(), 70)).append('"');
        if (creator != null) sb.append(" by ").append(trim(creator.trim(), 40));
        if (title == null && entries > 0) sb.append(", ").append(entries).append(" parts");
        putIf("Title", title);
        putIf("Creator", creator);
        hint = sb.toString();
    }

    private void zip(File f) {
        try (ZipFile z = new ZipFile(f)) {
            int n = 0;
            long raw = 0;
            String firstName = null;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements() && n < 200000) {
                ZipEntry e = en.nextElement();
                if (firstName == null && !e.isDirectory()) firstName = e.getName();
                if (e.getSize() > 0) raw += e.getSize();
                n++;
            }
            hint = "ZIP with " + n + (n == 1 ? " entry" : " entries") + ", " + Fmt.size(raw) + " unpacked"
                    + (firstName != null ? ", starts with " + trim(firstName, 50) : "");
        } catch (Throwable t) {
            hint = "ZIP archive (could not open)";
        }
    }

    private void apk(Context ctx, File f) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi != null && pi.applicationInfo != null) {
                ApplicationInfo ai = pi.applicationInfo;
                ai.sourceDir = f.getAbsolutePath();
                ai.publicSourceDir = f.getAbsolutePath();
                CharSequence label = ai.loadLabel(pm);
                hint = "Android app: " + label + " (" + pi.packageName + ") v" + pi.versionName;
                putIf("Package", pi.packageName);
                putIf("Version", pi.versionName);
                putIf("Min SDK", String.valueOf(ai.minSdkVersion));
                return;
            }
        } catch (Throwable ignored) { }
        hint = "Android app package";
    }

    private void magic(File f, String ext) throws IOException {
        byte[] b = new byte[64];
        int n;
        try (FileInputStream in = new FileInputStream(f)) { n = Math.max(0, in.read(b)); }
        String s = new String(b, 0, n, StandardCharsets.ISO_8859_1);
        String what;
        if (s.startsWith("SQLite format 3")) what = "SQLite database";
        else if (s.startsWith("PK\u0003\u0004")) what = "ZIP-packed data";
        else if (s.startsWith("%PDF")) what = "PDF data";
        else if (s.startsWith("\u007fELF")) what = "Linux executable or library";
        else if (s.startsWith("\u0089PNG")) what = "PNG image data";
        else if (n > 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8) what = "JPEG image data";
        else if (s.startsWith("GIF8")) what = "GIF image data";
        else if (s.startsWith("RIFF")) what = "RIFF media (" + (n >= 12 ? s.substring(8, 12).trim() : "?") + ")";
        else if (s.startsWith("OggS")) what = "Ogg media";
        else if (s.startsWith("ID3")) what = "MP3 audio";
        else if (s.startsWith("fLaC")) what = "FLAC audio";
        else if (n > 8 && s.startsWith("ftyp", 4)) what = "MP4 family media (" + s.substring(8, Math.min(12, n)).trim() + ")";
        else if (s.startsWith("dex\n")) what = "Android DEX bytecode";
        else if (s.startsWith("\u001f\u008b")) what = "Gzip compressed data";
        else if (s.startsWith("BEGIN:VCARD")) what = "Contact card";
        else if (s.startsWith("BEGIN:VCALENDAR")) what = "Calendar";
        else if (looksText(b, n)) {
            String line = s.split("\\r?\\n")[0].trim();
            what = "Text: " + trim(line.isEmpty() ? "(blank start)" : line, 80);
        } else {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(4, n); i++) hex.append(String.format(Locale.US, "%02X", b[i] & 0xff));
            what = "Binary data, starts " + hex;
        }
        hint = Kinds.label(ext).equals("File") || "other".equals(Kinds.kind(ext)) ? what : Kinds.label(ext) + ", " + what;
    }

    private static boolean looksText(byte[] b, int n) {
        if (n == 0) return false;
        int bad = 0;
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xff;
            if (c == 0) return false;
            if (c < 9 || (c > 13 && c < 32)) bad++;
        }
        return bad * 10 < n;
    }

    // ------------------------------------------------------------------ hint

    private void finishHint(String kind, String ext) {
        if (kind.equals("image")) {
            StringBuilder sb = new StringBuilder();
            if (ai) sb.append("AI image").append(aiInfo != null ? " (" + trim(aiInfo, 60) + ")" : "");
            else if (shot == 1) sb.append(trim(shotInfo, 110));
            else if (shot == 2) sb.append("iOS screenshot");
            else if (camera != null) sb.append("Photo from ").append(camera);
            else sb.append(Kinds.label(ext));
            if (shot == 0 || ai) { if (width > 0) sb.append(", ").append(width).append('x').append(height); }
            if (lat != null && shot == 0) sb.append(", has GPS");
            String prompt = extra.optString("AI prompt", "");
            if (ai && !prompt.isEmpty()) sb.append(": \"").append(trim(prompt.replaceAll("\\s+", " "), 70)).append('"');
            hint = sb.toString();
        } else if (kind.equals("video")) {
            StringBuilder sb = new StringBuilder(ai ? "AI video" : "Video");
            if (width > 0) sb.append(' ').append(width).append('x').append(height);
            if (duration > 0) sb.append(", ").append(Fmt.duration(duration));
            if (shot > 0) sb.append(", screen recording");
            if (lat != null) sb.append(", has GPS");
            hint = sb.toString();
        } else if (kind.equals("audio")) {
            StringBuilder sb = new StringBuilder(f(extra, "Artist") != null || f(extra, "Title") != null ? "" : Kinds.label(ext));
            String artist = f(extra, "Artist"), title = f(extra, "Title");
            if (artist != null) sb.append(artist);
            if (title != null) sb.append(sb.length() > 0 ? " - " : "").append(title);
            if (duration > 0) sb.append(", ").append(Fmt.duration(duration));
            hint = sb.toString();
        } else if (ai && hint != null && !hint.startsWith("AI")) {
            hint = "AI provenance, " + hint;
        }
        if (hint == null || hint.isEmpty()) hint = Kinds.label(ext);
    }

    private static String f(JSONObject o, String k) {
        String v = o.optString(k, "");
        return v.isEmpty() ? null : v;
    }

    // ------------------------------------------------------------------ helpers

    private void putIf(String k, String v) {
        v = clean(v);
        if (v == null) return;
        try { extra.put(k, trim(v, 400)); } catch (JSONException ignored) { }
    }

    static String clean(String s) {
        if (s == null) return null;
        s = s.replace("\u0000", "").trim();
        if (s.startsWith("ASCII")) s = s.substring(5).trim();
        return s.isEmpty() ? null : s;
    }

    static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String group(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        if (s == null) return null;
        return s.replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static long parseLong(String s) {
        if (s == null) return 0;
        try { return (long) Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (out.size() < max && (n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}

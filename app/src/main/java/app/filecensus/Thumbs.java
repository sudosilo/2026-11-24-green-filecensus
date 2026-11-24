// GREEN PHONE EDIT
package app.filecensus;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;

/** Small JPEG thumbnails, cached in memory and on disk, keyed by path and modification time. */
public final class Thumbs {

    private static final int EDGE = 240;
    private static final LruCache<String, byte[]> MEM = new LruCache<String, byte[]>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, byte[] v) { return v.length; }
    };

    private Thumbs() { }

    public static byte[] get(Context ctx, String path) {
        File f = new File(path);
        if (!f.isFile()) return null;
        String key = path + "|" + f.lastModified();
        byte[] hit = MEM.get(key);
        if (hit != null) return hit;
        File disk = new File(new File(ctx.getCacheDir(), "thumbs"), sha1(key) + ".jpg");
        if (disk.isFile()) {
            byte[] b = readAll(disk);
            if (b != null) { MEM.put(key, b); return b; }
        }
        Bitmap bm = null;
        try { bm = make(ctx, f); } catch (Throwable ignored) { }
        if (bm == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bitmap flat = flatten(bm);
        flat.compress(Bitmap.CompressFormat.JPEG, 78, out);
        byte[] b = out.toByteArray();
        MEM.put(key, b);
        try {
            disk.getParentFile().mkdirs();
            try (FileOutputStream fo = new FileOutputStream(disk)) { fo.write(b); }
        } catch (Throwable ignored) { }
        return b;
    }

    /** A large JPEG for the in-app image viewer, so HEIC, RAW and huge photos all display. */
    public static byte[] preview(String path) {
        File f = new File(path);
        if (!f.isFile()) return null;
        try {
            Bitmap bm = null;
            if (Build.VERSION.SDK_INT >= 29) {
                try { bm = ThumbnailUtils.createImageThumbnail(f, new Size(1600, 1600), null); } catch (Throwable ignored) { }
            }
            if (bm == null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                if (o.outWidth <= 0) return null;
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 1600 || o.outHeight / (sample * 2) >= 1600) sample *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                bm = BitmapFactory.decodeFile(path, d);
                if (bm == null) return null;
                int or = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                int rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                if (rot != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(rot);
                    bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            flatten(bm).compress(Bitmap.CompressFormat.JPEG, 88, out);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap make(Context ctx, File f) throws Exception {
        String ext = Kinds.ext(f.getName());
        String kind = Kinds.kind(ext);
        Size size = new Size(EDGE, EDGE);
        switch (kind) {
            case "image":
                if (Build.VERSION.SDK_INT >= 29) {
                    try { return ThumbnailUtils.createImageThumbnail(f, size, null); } catch (Throwable ignored) { }
                }
                return decodeImage(f);
            case "video":
                if (Build.VERSION.SDK_INT >= 29) {
                    try { return ThumbnailUtils.createVideoThumbnail(f, size, null); } catch (Throwable ignored) { }
                }
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(f.getAbsolutePath());
                    Bitmap frame = r.getFrameAtTime(1000000);
                    return frame == null ? null : scale(frame);
                } finally {
                    try { r.release(); } catch (Throwable ignored) { }
                }
            case "audio":
                if (Build.VERSION.SDK_INT >= 29) {
                    try { return ThumbnailUtils.createAudioThumbnail(f, size, null); } catch (Throwable ignored) { }
                }
                MediaMetadataRetriever a = new MediaMetadataRetriever();
                try {
                    a.setDataSource(f.getAbsolutePath());
                    byte[] art = a.getEmbeddedPicture();
                    if (art == null) return null;
                    return scale(BitmapFactory.decodeByteArray(art, 0, art.length));
                } finally {
                    try { a.release(); } catch (Throwable ignored) { }
                }
            case "apk": {
                PackageManager pm = ctx.getPackageManager();
                PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null || pi.applicationInfo == null) return null;
                ApplicationInfo ai = pi.applicationInfo;
                ai.sourceDir = f.getAbsolutePath();
                ai.publicSourceDir = f.getAbsolutePath();
                return drawableToBitmap(ai.loadIcon(pm));
            }
            default:
                if ("pdf".equals(ext)) return pdfPage(f);
                return null;
        }
    }

    private static Bitmap decodeImage(File f) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= EDGE && o.outHeight / (sample * 2) >= EDGE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        if (bm == null) return null;
        int rot = 0;
        try {
            int or = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (Throwable ignored) { }
        bm = scale(bm);
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        }
        return bm;
    }

    private static Bitmap pdfPage(File f) throws Exception {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pr = new PdfRenderer(pfd)) {
            if (pr.getPageCount() == 0) return null;
            try (PdfRenderer.Page p = pr.openPage(0)) {
                float s = (float) EDGE / Math.max(p.getWidth(), p.getHeight());
                int w = Math.max(1, Math.round(p.getWidth() * s * 1.5f));
                int h = Math.max(1, Math.round(p.getHeight() * s * 1.5f));
                Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bm.eraseColor(Color.WHITE);
                p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bm;
            }
        }
    }

    private static Bitmap scale(Bitmap bm) {
        if (bm == null) return null;
        int w = bm.getWidth(), h = bm.getHeight();
        int max = Math.max(w, h);
        if (max <= EDGE * 1.5) return bm;
        float s = (float) (EDGE * 1.5) / max;
        return Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    private static Bitmap flatten(Bitmap bm) {
        Bitmap out = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(bm, 0, 0, null);
        return out;
    }

    private static Bitmap drawableToBitmap(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 144;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 144;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return bm;
    }

    private static byte[] readAll(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}

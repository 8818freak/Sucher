package de.herbers.sucher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Miniaturbild-Erzeugung/-Ablage - unabhaengig vom "Inhalt durchsuchbar
 * machen"-Ordnerschalter (Settings.contentIndexingEnabled): ein Titelbild ist
 * ein paar KB, nicht die zig-KB-bis-MB an Fliesstext pro Buch, die bei
 * Mathias' ~22000 Buechern den zweistelligen-GB-Index ausloesen wuerden -
 * darum immer versucht, wo das Format es hergibt.
 *
 * Ablage als JPEG-Dateien im App-Cache (nicht in der SQLite-Datenbank selbst -
 * Cache-Verzeichnisse duerfen vom System bei Speichernot geleert werden, genau
 * das richtige Verhalten fuer "nice to have"-Vorschaubilder, die sich beim
 * naechsten Indizierlauf einfach neu erzeugen). Es gibt bewusst KEINE eigene
 * Datenbank-Spalte dafuer - MainActivity prueft beim Anzeigen einfach, ob die
 * Cache-Datei existiert (schnell genug fuer eine Bildschirmseite Treffer,
 * unnoetig fuer 22000 Zeilen auf einmal).
 */
final class Thumbnails {

    private Thumbnails() {}

    private static final int MAX_W = 200, MAX_H = 280;
    // Groessenobergrenze, unabhaengig vom (groesseren) Limit fuer die
    // Volltext-Extraktion - ein Titelbild zu suchen lohnt sich bei einer
    // Mammut-Datei ohnehin nicht (meist eh kein Bildformat mit Cover).
    private static final long MAX_SOURCE_BYTES = 80L * 1024 * 1024;

    private static File cacheDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "thumbs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String cacheKey(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(path.hashCode());
        }
    }

    /** Die Cache-Datei fuer einen Pfad (existiert ggf. noch nicht). */
    static File fileFor(Context ctx, String path) {
        return new File(cacheDir(ctx), cacheKey(path) + ".jpg");
    }

    static boolean exists(Context ctx, String path) {
        return fileFor(ctx, path).isFile();
    }

    /** Erzeugt (falls moeglich) das Miniaturbild einer Datei und legt es im
     *  Cache ab - liefert true bei Erfolg. Ueberschreibt eine evtl.
     *  vorhandene alte Miniatur (Datei kann sich seit dem letzten Lauf
     *  geaendert haben, siehe SearchIndexer - nur dann wird ueberhaupt neu
     *  aufgerufen). */
    static boolean generate(Context ctx, File src, String extLower) {
        if (src.length() > MAX_SOURCE_BYTES) return false;
        try {
            byte[] raw = coverBytesFor(src, extLower);
            Bitmap bmp;
            if (raw != null) {
                bmp = decodeSampled(raw);
            } else if (isImageExt(extLower)) {
                bmp = decodeSampledFile(src);
            } else {
                return false;
            }
            if (bmp == null) return false;
            Bitmap scaled = scaleDown(bmp);
            File out = fileFor(ctx, src.getAbsolutePath());
            try (FileOutputStream fos = new FileOutputStream(out)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, fos);
            }
            if (scaled != bmp) scaled.recycle();
            bmp.recycle();
            return true;
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "Miniaturbild fehlgeschlagen: " + src.getName(), t);
            return false;
        }
    }

    private static boolean isImageExt(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp":
                return true;
            default:
                return false;
        }
    }

    /** Je Format die Rohbytes des Titelbilds besorgen, wo vorhanden - Bilder
     *  selbst (kein "Cover", die Datei IST das Bild) und Renderformate (PDF)
     *  liefern hier bewusst null und werden vom Aufrufer separat behandelt. */
    private static byte[] coverBytesFor(File f, String ext) {
        switch (ext) {
            case "epub": return FileExtractors.epubCoverBytes(f);
            case "mobi": case "azw": case "azw3": case "prc": return MobiExtractor.coverBytes(f);
            case "cbz": return ComicExtractor.cbzCoverBytes(f);
            case "docx": case "xlsx": case "pptx": return ooxmlThumbBytes(f);
            default: return null;
        }
    }

    /** docProps/thumbnail.jpeg ist Teil des OOXML-Pakets (Word/Excel/
     *  PowerPoint legen das beim Speichern selbst an) - nur wenn es
     *  tatsaechlich ein JPEG ist (manche Programme legen stattdessen ein
     *  Windows-EMF ab, das wir nicht dekodieren koennen). */
    private static byte[] ooxmlThumbBytes(File f) {
        try (ZipFile zf = new ZipFile(f)) {
            ZipEntry e = zf.getEntry("docProps/thumbnail.jpeg");
            if (e == null) return null;
            try (java.io.InputStream in = zf.getInputStream(e)) { return FileExtractors.readAll(in); }
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decodeSampled(byte[] raw) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
    }

    private static Bitmap decodeSampledFile(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    private static int sampleSize(int w, int h) {
        int sample = 1;
        while (w / (sample * 2) >= MAX_W * 2 && h / (sample * 2) >= MAX_H * 2) sample *= 2;
        return sample;
    }

    private static Bitmap scaleDown(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= MAX_W && h <= MAX_H) return src;
        float ratio = Math.min(MAX_W / (float) w, MAX_H / (float) h);
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    /** PDFs werden gerendert statt "eingebettet" gefunden - eigener
     *  Aufrufpfad, weil das (anders als die byte[]-Faelle oben) direkt ein
     *  Bitmap liefert, kein komprimiertes Bildformat zum Dekodieren. Von
     *  SearchIndexer separat aufgerufen. */
    static boolean generatePdf(Context ctx, File src) {
        if (src.length() > MAX_SOURCE_BYTES) return false;
        try {
            Bitmap bmp = PdfExtractorHelper.renderCoverBitmap(src);
            if (bmp == null) return false;
            Bitmap scaled = scaleDown(bmp);
            File out = fileFor(ctx, src.getAbsolutePath());
            try (FileOutputStream fos = new FileOutputStream(out)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, fos);
            }
            if (scaled != bmp) scaled.recycle();
            bmp.recycle();
            return true;
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "PDF-Miniaturbild fehlgeschlagen: " + src.getName(), t);
            return false;
        }
    }
}

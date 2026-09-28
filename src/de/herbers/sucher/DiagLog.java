package de.herbers.sucher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Kleines, dauerhaftes Diagnose-Protokoll im app-internen Speicher. Haelt
 *  fest, was der Indizierer tut und - vor allem - an welchen Dateien er sich
 *  verschluckt (uebersprungene Dateien, ein erkannter Haenger). So kann man
 *  fehlerhafte Dateien einsehen/melden, ohne ein Kabel und logcat zu brauchen.
 *  Bewusst winzig gehalten: nur der juengste Teil bleibt erhalten (MAX_BYTES). */
final class DiagLog {

    private DiagLog() {}

    private static final String FILE = "diag.log";
    private static final int MAX_BYTES = 128 * 1024;

    static synchronized void log(Context ctx, String msg) {
        if (ctx == null) return;
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        append(ctx, stamp + "  " + msg + "\n");
    }

    static String read(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return "";
            byte[] b = new byte[(int) f.length()];
            try (InputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) != -1) off += n;
            }
            return new String(b, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void clear(Context ctx) {
        try { new File(ctx.getFilesDir(), FILE).delete(); } catch (Throwable ignored) {}
    }

    private static void append(Context ctx, String text) {
        try {
            String combined = read(ctx) + text;
            if (combined.length() > MAX_BYTES) {
                combined = combined.substring(combined.length() - MAX_BYTES);
            }
            File f = new File(ctx.getFilesDir(), FILE);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8")) {
                w.write(combined);
            }
        } catch (Throwable ignored) {}
    }
}

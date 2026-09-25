package de.herbers.sucher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Xml;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Grosse Vorschau beim Antippen einer Miniatur (Mathias' Wunsch, "aehnlich
 * wie bei Apples Leertastenvorschau im Finder") - je Format unterschiedlich
 * tief:
 *  - Bilder: das Bild selbst.
 *  - PDF/CBZ: echter Seiten-Blaetterer (PagerView, fertige Bilder je Seite).
 *  - EPUB: WebView mit den echten Kapiteln (aus dem Buch temporaer entpackt,
 *    Kapitel-Reihenfolge aus der OPF-Spine) - Android hat kein anderes
 *    eingebautes HTML/EPUB-Rendering, WebView ist dafuer der Standardweg.
 *  - MOBI6 (klassisch): dieselbe WebView-Anzeige, das eingebettete HTML
 *    direkt aus den Text-Records (siehe MobiExtractor.loadPreview).
 *  - AZW3/KF8: deren Text steckt in getrennten skeleton/flow-Abschnitten,
 *    die sich nicht einfach zu HTML zusammensetzen lassen (eigenes Projekt
 *    fuer sich) - bewusster Rueckfall auf reinen, scrollbaren Text.
 *  - DOCX/XLSX/PPTX/DOC/XLS/PPT: kein Seiten-Renderer (das waere Office'
 *    Layout-Engines nachzubauen) - nur das eingebettete Vorschaubild (falls
 *    vorhanden) gross, mit Knopf zum Oeffnen in der echten App.
 *  - TXT/MD/etc.: der Text direkt aus der Datei, scrollbar - praktisch
 *    kostenlos, da reiner Text.
 *  - CBR/alles andere ohne Vorschau: Hinweis + Oeffnen-Knopf.
 */
public class PreviewActivity extends Activity {

    static final String EXTRA_PATH = "path";
    static final String EXTRA_EXT = "ext";
    static final String EXTRA_CONTENT_OK = "content_ok";

    private String path, ext;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        path = getIntent().getStringExtra(EXTRA_PATH);
        ext = getIntent().getStringExtra(EXTRA_EXT);
        boolean contentOk = getIntent().getBooleanExtra(EXTRA_CONTENT_OK, false);
        if (ext == null) ext = "";
        ext = ext.toLowerCase(Locale.ROOT);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#1C1C1E"));

        File f = path != null ? new File(path) : null;
        View content;
        try {
            content = f == null || !f.isFile() ? notFoundView() : buildFor(f, ext, contentOk);
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "Vorschau fehlgeschlagen: " + path, t);
            content = errorView();
        }
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(closeButton());
        setContentView(root);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private View closeButton() {
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(20);
        int pad = dp(12);
        close.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#88000000"));
        bg.setCornerRadius(dp(20));
        close.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp(14); lp.rightMargin = dp(14);
        close.setLayoutParams(lp);
        close.setOnClickListener(v -> finish());
        return close;
    }

    private View notFoundView() {
        return message("Datei nicht gefunden.");
    }

    private View errorView() {
        return message("Vorschau nicht möglich.");
    }

    private View message(String text) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#8899AA"));
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        return box;
    }

    // ---------- Weiterreichen an die zustaendige App (wie MainActivity.openFile) ----------

    private void openInOtherApp() {
        try {
            File f = new File(path);
            Uri uri = LocalFileProvider.uriForFile(this, f);
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime != null ? mime : "*/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Konnte nicht geöffnet werden.", Toast.LENGTH_SHORT).show();
        }
    }

    private Button openButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> openInOtherApp());
        return b;
    }

    // ---------- Format-Weiche ----------

    private View buildFor(File f, String ext, boolean contentOk) throws Exception {
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp":
                return imageView(f);
            case "pdf":
                return pdfPager(f);
            case "cbz":
                return comicPager(f);
            case "epub":
                return epubReader(f);
            case "mobi": case "azw": case "azw3": case "prc":
                return mobiReader(f);
            case "docx": case "xlsx": case "pptx": case "doc": case "xls": case "ppt":
                return officeFallback(f);
            case "txt": case "md": case "markdown": case "csv": case "log": case "json":
            case "xml": case "srt": case "ini": case "yaml": case "yml":
                return textView(f);
            default:
                return unsupportedView();
        }
    }

    // ---------- Bilder ----------

    private View imageView(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 2048 && bounds.outHeight / (sample * 2) >= 2048) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (bmp != null) iv.setImageBitmap(bmp);
        return iv;
    }

    // ---------- PDF ----------

    private View pdfPager(File f) {
        int count = PdfExtractorHelper.pageCount(f);
        if (count <= 0) return message("PDF kann nicht angezeigt werden (kopiergeschützt oder beschädigt).");
        return new PagerView(this, new PagerView.PageSource() {
            @Override public int count() { return count; }
            @Override public Bitmap load(int index) { return PdfExtractorHelper.renderPage(f, index, 1.3f); }
        }, 0);
    }

    // ---------- CBZ ----------

    private View comicPager(File f) {
        List<String> pages = ComicExtractor.cbzPageNames(f);
        if (pages.isEmpty()) return message("Keine Bildseiten in diesem Comic gefunden.");
        return new PagerView(this, new PagerView.PageSource() {
            @Override public int count() { return pages.size(); }
            @Override public Bitmap load(int index) {
                byte[] raw = ComicExtractor.cbzEntryBytes(f, pages.get(index));
                if (raw == null) return null;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= 1600 && bounds.outHeight / (sample * 2) >= 1600) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
            }
        }, 0);
    }

    // ---------- EPUB ----------

    private static final class EpubBook {
        File dir; String opfDir; List<String> spine = new ArrayList<>();
    }

    private View epubReader(File f) throws Exception {
        EpubBook book = prepareEpub(f);
        if (book == null || book.spine.isEmpty()) return message("Dieses E-Book konnte nicht geöffnet werden.");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        WebView web = new WebView(this);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setJavaScriptEnabled(false);
        web.setWebViewClient(fileLinkClient());
        web.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        box.addView(web);

        int[] chapter = {0};
        LinearLayout nav = chapterNav(book.spine.size(), chapter, idx ->
                web.loadUrl("file://" + new File(book.dir, book.opfDir + book.spine.get(idx)).getAbsolutePath()));
        box.addView(nav);

        web.loadUrl("file://" + new File(book.dir, book.opfDir + book.spine.get(0)).getAbsolutePath());
        return box;
    }

    /** Verhindert genau den Absturz, den ein interner Buch-Link (z.B. ein
     *  Eintrag im Inhaltsverzeichnis) sonst ausloest: ohne eigenen
     *  WebViewClient reicht die WebView einen angetippten Link an das System
     *  weiter, und eine file://-URI per Intent zu verschicken wirft seit
     *  Android 7 eine FileUriExposedException (Mathias' Absturzmeldung).
     *  file://-Ziele (alles innerhalb des entpackten Buchs, auch Anker auf
     *  andere Kapitel) laedt die WebView darum selbst; nur fuer echte externe
     *  Adressen (http/https/mailto - in einem lokalen E-Book selten, aber
     *  moeglich, z.B. eine Fussnote mit Weblink) wird ein System-Intent
     *  versucht, ebenfalls abgesichert statt die Vorschau abstuerzen zu lassen. */
    private WebViewClient fileLinkClient() {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && "file".equals(uri.getScheme())) {
                    view.loadUrl(uri.toString());
                    return true;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
        };
    }

    private interface IntSink { void go(int index); }

    /** Kapitel-vor/-zurueck als einfache Knopfleiste statt Wischen ueber der
     *  WebView (die verarbeitet Wischen bereits selbst fuers Scrollen
     *  innerhalb eines Kapitels - beides gleichzeitig ueber Geste zu
     *  unterscheiden waere fehleranfaellig, siehe Klassenkommentar). */
    private LinearLayout chapterNav(int count, int[] current, IntSink onChange) {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(8), dp(6), dp(8), dp(6));
        nav.setBackgroundColor(Color.parseColor("#2C2C2E"));

        Button prev = new Button(this);
        prev.setText("‹ Kapitel");
        nav.addView(prev);

        TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        label.setText((current[0] + 1) + " / " + count);
        nav.addView(label);

        Button next = new Button(this);
        next.setText("Kapitel ›");
        nav.addView(next);

        prev.setOnClickListener(v -> {
            if (current[0] > 0) { current[0]--; label.setText((current[0] + 1) + " / " + count); onChange.go(current[0]); }
        });
        next.setOnClickListener(v -> {
            if (current[0] < count - 1) { current[0]++; label.setText((current[0] + 1) + " / " + count); onChange.go(current[0]); }
        });
        return nav;
    }

    /** Buch einmalig in einen Cache-Ordner entpacken (spart wiederholtes
     *  Entpacken bei erneutem Oeffnen desselben Buchs) und die Lese-
     *  Reihenfolge (OPF-Spine) daraus bestimmen. */
    private EpubBook prepareEpub(File epubFile) throws Exception {
        String key = sha256(epubFile.getAbsolutePath());
        File dir = new File(getCacheDir(), "epub_preview/" + key);
        if (!dir.isDirectory()) {
            File tmp = new File(getCacheDir(), "epub_preview/" + key + ".tmp");
            deleteRecursive(tmp);
            tmp.mkdirs();
            try (ZipFile zf = new ZipFile(epubFile)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || e.getName().contains("..")) continue;
                    File out = new File(tmp, e.getName());
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (InputStream in = zf.getInputStream(e); FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) >= 0) fos.write(buf, 0, n);
                    }
                }
            }
            tmp.renameTo(dir);
        }

        String opfPath = findOpfPath(dir);
        if (opfPath == null) return null;
        EpubBook book = new EpubBook();
        book.dir = dir;
        book.opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        parseSpine(new File(dir, opfPath), book);
        return book;
    }

    private String findOpfPath(File dir) throws Exception {
        File container = new File(dir, "META-INF/container.xml");
        if (container.isFile()) {
            try (InputStream in = new FileInputStream(container)) {
                String p = firstAttr(in, "rootfile", "full-path");
                if (p != null) return p;
            }
        }
        return findFirstOpf(dir, "");
    }

    private String findFirstOpf(File dir, String rel) {
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File c : children) {
            String childRel = rel.isEmpty() ? c.getName() : rel + "/" + c.getName();
            if (c.isDirectory()) {
                String r = findFirstOpf(c, childRel);
                if (r != null) return r;
            } else if (c.getName().toLowerCase(Locale.ROOT).endsWith(".opf")) {
                return childRel;
            }
        }
        return null;
    }

    private void parseSpine(File opf, EpubBook book) throws Exception {
        Map<String, String> idToHref = new HashMap<>();
        List<String> spineIds = new ArrayList<>();
        try (InputStream in = new FileInputStream(opf)) {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));
            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String local = localName(p.getName());
                    if (local.equals("item")) {
                        String id = attr(p, "id"), href = attr(p, "href");
                        if (id != null && href != null) idToHref.put(id, href);
                    } else if (local.equals("itemref")) {
                        String idref = attr(p, "idref");
                        if (idref != null) spineIds.add(idref);
                    }
                }
                try { event = p.next(); } catch (Exception e) { break; }
            }
        }
        for (String id : spineIds) {
            String href = idToHref.get(id);
            if (href != null) book.spine.add(href);
        }
        if (book.spine.isEmpty()) book.spine.addAll(idToHref.values()); // Notnagel: keine Spine gefunden
    }

    private static String firstAttr(InputStream in, String tagLocal, String attrName) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && localName(p.getName()).equals(tagLocal)) {
                String v = attr(p, attrName);
                if (v != null) return v;
            }
            event = p.next();
        }
        return null;
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String an = p.getAttributeName(i);
            String local = an.contains(":") ? an.substring(an.indexOf(':') + 1) : an;
            if (local.equalsIgnoreCase(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static String localName(String name) {
        int i = name.indexOf(':');
        return (i >= 0 ? name.substring(i + 1) : name).toLowerCase(Locale.ROOT);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File child : c) deleteRecursive(child);
        }
        f.delete();
    }

    // ---------- MOBI/AZW3 ----------

    private View mobiReader(File f) {
        MobiExtractor.PreviewResult pr = MobiExtractor.loadPreview(f);
        if (pr.drm) return message("Kopiergeschütztes E-Book – keine Vorschau möglich.");
        if (pr.unsupportedCompression) return message("Dieses E-Book nutzt ein Kompressionsverfahren, das (noch) nicht unterstützt wird.");
        if (pr.html == null || pr.html.trim().isEmpty()) return message("Kein Inhalt gefunden.");

        if (!pr.kf8) {
            // Klassisches MOBI6: die Text-Records enthalten meist schon
            // fertiges HTML - direkt in einer WebView anzeigen.
            WebView web = new WebView(this);
            web.getSettings().setJavaScriptEnabled(false);
            web.setWebViewClient(fileLinkClient()); // dieselbe Absicherung wie bei EPUB, siehe dort
            web.loadDataWithBaseURL(null, pr.html, "text/html", "utf-8", null);
            return web;
        }

        // AZW3/KF8: siehe Klassenkommentar - bewusst nur reiner, scrollbarer
        // Text statt eines nachgebauten HTML-Renderers.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView note = new TextView(this);
        note.setText("Dieses Format (AZW3/KF8) zeigt hier nur reinen Text, ohne Kapitel-Formatierung.");
        note.setTextColor(Color.parseColor("#8899AA"));
        note.setTextSize(11);
        note.setPadding(dp(14), dp(10), dp(14), dp(4));
        box.addView(note);
        box.addView(scrollableText(pr.html));
        return box;
    }

    // ---------- Office (nur statisches Vorschaubild, kein Seiten-Renderer) ----------

    private View officeFallback(File f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));

        File thumb = Thumbnails.fileFor(this, path);
        if (thumb.isFile()) {
            Bitmap bmp = BitmapFactory.decodeFile(thumb.getAbsolutePath());
            if (bmp != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(16);
                iv.setLayoutParams(lp);
                box.addView(iv);
            }
        }

        TextView note = new TextView(this);
        note.setText("Für Office-Dokumente gibt es keine Seiten-Vorschau (das würde Word/Excel/"
                + "PowerPoints eigene Layout-Engine nachbauen) – nur das oben gezeigte Deckblatt, "
                + "falls die Datei eins gespeichert hat.");
        note.setTextColor(Color.parseColor("#8899AA"));
        note.setTextSize(13);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, 0, 0, dp(16));
        box.addView(note);

        box.addView(openButton("In zuständiger App öffnen"));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        return scroll;
    }

    // ---------- Klartext ----------

    private View textView(File f) {
        String text;
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 500_000)];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(buf);
                text = new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
            }
            if (f.length() > buf.length) text += "\n\n… (gekürzt)";
        } catch (Exception e) {
            text = "Konnte nicht gelesen werden.";
        }
        return scrollableText(text);
    }

    private View scrollableText(String text) {
        ScrollView scroll = new ScrollView(this);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        t.setTextIsSelectable(true);
        scroll.addView(t);
        return scroll;
    }

    private View unsupportedView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView t = new TextView(this);
        t.setText("Keine Vorschau für dieses Dateiformat verfügbar.");
        t.setTextColor(Color.parseColor("#8899AA"));
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), 0, dp(20), dp(16));
        box.addView(t);
        box.addView(openButton("In zuständiger App öffnen"));
        return box;
    }
}

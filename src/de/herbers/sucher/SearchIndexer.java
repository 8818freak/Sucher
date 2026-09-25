package de.herbers.sucher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;

/**
 * Durchsucht die in den Einstellungen gewaehlten Ordner und fuellt den
 * SearchStore. Laeuft als einfacher Hintergrund-Thread (kein eigener
 * Dienst/keine WorkManager-Abhaengigkeit noetig - EdgeService haelt den
 * Prozess ohnehin als Vordergrunddienst am Leben, darin ist ein zusaetzlicher
 * Thread waehrend eines manuell angestossenen Durchlaufs unproblematisch).
 *
 * Jede Datei bekommt einen Metadaten-Eintrag (Name/Typ/Groesse/Datum/Titel/
 * Autor/Serie) - so funktioniert Namens-/Autor-/Serien-/Typ-/Datumssuche
 * fuer JEDE Datei, auch unbekannte Formate (dann eben ohne Titel/Autor/
 * Serie). Der eigentliche FLIESSTEXT (das teure an Zeit UND Speicher - siehe
 * Mathias' ~22000-Buecher-Bibliothek) wird nur fuer Ordner geholt, die
 * Settings.contentIndexingEnabled extra freigibt; ausserhalb bleibt es bei
 * Metadaten (mtime-Vergleich macht Folgedurchlaeufe trotzdem schnell).
 */
final class SearchIndexer {

    private SearchIndexer() {}

    private static volatile boolean running = false;
    static volatile int scanned = 0;
    static volatile int contentIndexed = 0;
    static volatile String currentPath = "";

    static boolean isRunning() { return running; }

    /** Groessenobergrenze fuer Inhaltsextraktion (Metadaten werden trotzdem
     *  immer gespeichert) - verhindert dass ein Mammut-Archiv/-Video die
     *  Indizierung tagelang blockiert. */
    private static final long MAX_CONTENT_BYTES = 60L * 1024 * 1024;

    // Von start() einmal pro Lauf gesetzt.
    private static boolean searchComicsMetaCached = true;
    private static List<String> contentRootsCached = java.util.Collections.emptyList();

    static void start(Context ctx, Runnable onDone) {
        if (running) return;
        running = true;
        scanned = 0; contentIndexed = 0; currentPath = "";
        Context app = ctx.getApplicationContext();
        contextApp = app;
        searchComicsMetaCached = Settings.searchComicsMeta(app);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                PdfExtractorHelper.init(app);
                SearchStore store = SearchStore.get(app);
                long runStart = System.currentTimeMillis();
                visitedCanonical.clear();
                java.util.List<String> folders = new java.util.ArrayList<>(Settings.searchFolders(app));
                contentRootsCached = new java.util.ArrayList<>();
                for (String cf : folders) {
                    if (Settings.contentIndexingEnabled(app, cf)) contentRootsCached.add(cf);
                }
                for (String root : folders) {
                    File rootDir = new File(root);
                    if (rootDir.isDirectory()) walk(store, rootDir);
                    store.pruneStale(root, runStart);
                }
                Settings.setSearchLastRun(app, System.currentTimeMillis());
            } catch (Throwable t) {
                android.util.Log.w("EdgeTabSearch", "Indizierlauf abgebrochen", t);
            } finally {
                running = false;
                if (onDone != null) main.post(onDone);
            }
        }, "EdgeTabSearchIndexer").start();
    }

    // Kanonische Pfade bereits besuchter Ordner - Android haengt an mehreren
    // Stellen Verknuepfungen ein, die auf denselben echten Ort zeigen (z.B.
    // /storage/self/primary -> /storage/emulated/0). Ohne diese Bremse wuerde
    // ein an /storage gewurzelter Durchlauf jede Datei doppelt erfassen (im
    // schlimmsten Fall, je nach ROM, sogar in einer echten Schleife haengen).
    private static final java.util.Set<String> visitedCanonical = new java.util.HashSet<>();

    private static void walk(SearchStore store, File dir) {
        String canon;
        try { canon = dir.getCanonicalPath(); } catch (Exception e) { canon = dir.getAbsolutePath(); }
        if (!visitedCanonical.add(canon)) return;

        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                if (f.isHidden() || f.getName().startsWith(".")) continue; // .thumbnails, .trash etc.
                walk(store, f);
            } else {
                indexOne(store, f);
            }
        }
    }

    private static boolean contentWanted(String path) {
        for (String root : contentRootsCached) {
            if (path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/")) return true;
        }
        return false;
    }

    private static void indexOne(SearchStore store, File f) {
        try {
            currentPath = f.getPath();
            long mtime = f.lastModified();
            String name = f.getName();
            String ext = extOf(name);
            boolean wantContent = f.length() <= MAX_CONTENT_BYTES && contentWanted(f.getPath());
            SearchStore.KnownState known = store.known(f.getPath());
            if (known.mtime == mtime && (!wantContent || known.contentOk)) {
                // Unveraendert seit dem letzten Lauf, UND (Inhalt entweder gar
                // nicht gewuenscht oder schon vorhanden) - nichts neu zu tun.
                // Ohne die zweite Bedingung wuerde ein nachtraeglich fuer
                // diesen Ordner eingeschaltetes "Inhalt durchsuchbar machen"
                // nie greifen, solange sich keine einzige Datei mehr aendert
                // (bei einer stabilen Buchsammlung: nie) - Mathias' Verdacht,
                // dass mit dem Indizieren "irgendwas nicht stimmt".
                if (contextApp != null && !Thumbnails.exists(contextApp, f.getPath())) {
                    generateThumbSafely(contextApp, f, ext);
                }
                scanned++;
                return;
            }

            long created = readCreated(f, mtime);

            FileExtractors.Result r = new FileExtractors.Result();
            if (isSupported(ext)) { // Metadaten sind billig, immer versuchen
                boolean comic = "cbz".equals(ext) || "cbr".equals(ext);
                if (!comic || searchComicsMetaCached) {
                    r = FileExtractors.extract(f, ext, wantContent);
                    if (r.text != null && !r.text.isEmpty()) contentIndexed++;
                }
            }
            store.upsert(f.getPath(), name, ext, f.length(), mtime, created,
                    r.text, r.drm, r.title, r.author, r.series, r.seriesIndex);
            if (contextApp != null) generateThumbSafely(contextApp, f, ext);
            scanned++;
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "Datei uebersprungen: " + f.getPath(), t);
        }
    }

    // Von start() gesetzt - fuer die Cache-Ablage der Miniaturbilder
    // (Thumbnails.java braucht einen Context fuers Cache-Verzeichnis).
    private static Context contextApp;

    /** Miniaturbild erzeugen, ohne dass ein Fehler dabei je den Indizierlauf
     *  fuer die restlichen Dateien gefaehrdet - "kein Titelbild" ist immer
     *  ein akzeptables Ergebnis, ein abgebrochener Lauf nicht. */
    private static void generateThumbSafely(Context app, File f, String ext) {
        try {
            if ("pdf".equals(ext)) Thumbnails.generatePdf(app, f);
            else Thumbnails.generate(app, f, ext);
        } catch (Throwable ignored) {}
    }

    /** Erstellungsdatum ("Geburt") ueber NIO, wo der Kernel/Dateisystem es
     *  liefert (ext4-btime-Unterstuetzung variiert) - sonst Ruecksturz auf
     *  die Aenderungszeit, damit das Feld nie 0/unplausibel bleibt. */
    private static long readCreated(File f, long fallback) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
            long t = attrs.creationTime().toMillis();
            return t > 0 ? t : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isSupported(String ext) {
        switch (ext) {
            case "txt": case "md": case "markdown": case "csv": case "log": case "json":
            case "xml": case "srt": case "ini": case "yaml": case "yml":
            case "docx": case "xlsx": case "pptx":
            case "doc": case "xls": case "ppt":
            case "epub": case "fb2":
            case "mobi": case "azw": case "azw3": case "prc":
            case "pdf":
            case "cbz": case "cbr":
                return true;
            default:
                return false;
        }
    }
}

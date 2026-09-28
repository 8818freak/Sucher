package de.herbers.sucher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

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
    private static volatile boolean stopRequested = false;
    static volatile int scanned = 0;
    static volatile int contentIndexed = 0;
    static volatile String currentPath = "";
    // Aktuell durchlaufener ORDNER (unabhaengig von der Datei). Wichtig fuers
    // Diagnose-Protokoll: bei einem Ordner-Amoklauf (Pfad-Alias-Ring) haengt
    // der Lauf in walk() und erreicht nie eine Datei - dann ist currentPath
    // leer, aber currentDir zeigt, WO es feststeckt.
    static volatile String currentDir = "";

    static boolean isRunning() { return running; }

    /** Kooperativer Stopp fuer IndexJobService.onStopJob(): das System hat
     *  das Zeitfenster des Jobs beendet, aber der Scan lief bis vor diesem
     *  Fix als eigener, vom Job-Lebenszyklus abgekoppelter Thread einfach
     *  weiter - onStopJob() konnte ihn nicht wirklich anhalten. Damit blieb
     *  fuer die naechste Job-Ausfuehrung (deren Thread ja noch "running"
     *  war) nur ein sofortiger, folgenloser No-Op via start() uebrig, ohne
     *  je jobFinished() aufzurufen - das System wertete den Job darum
     *  wiederholt als haengengeblieben und brach ihn zwangsweise ab (24x
     *  laut dumpsys jobscheduler, cachten Ausfuehrungsstatistiken zufolge),
     *  was App-Prozess und -Job zunehmend drosselte. walk()/indexOne()
     *  pruefen dieses Flag jetzt zwischen Dateien und beenden sich zuegig,
     *  sodass der Job sich sauber (und rechtzeitig) als fertig meldet. */
    static void requestStop() { stopRequested = true; }

    /** Groessenobergrenze fuer Inhaltsextraktion (Metadaten werden trotzdem
     *  immer gespeichert) - verhindert dass ein Mammut-Archiv/-Video die
     *  Indizierung tagelang blockiert. */
    private static final long MAX_CONTENT_BYTES = 60L * 1024 * 1024;

    // Von start() einmal pro Lauf gesetzt.
    private static boolean searchComicsMetaCached = true;
    private static List<String> contentRootsCached = java.util.Collections.emptyList();
    // Dateien, die einen frueheren Lauf zum Haengen brachten (siehe Watchdog
    // unten) - fuer die wird kein Inhalt/Titelbild mehr geholt, nur Metadaten.
    private static java.util.Set<String> skipContentCached = java.util.Collections.emptySet();

    // ---- Selbstheilung gegen den in Sucher-Uebergabe Abschnitt 3 beschriebenen
    // Haenger (Lauf blieb tagelang bei hoher CPU-Last stehen, Telefon wurde
    // warm, kein Stoppen-Knopf/Force-Stop half zuverlaessig). Ein CPU-gebundener
    // Endlos-Loop in einer Format-Bibliothek (PDFBox/POI/Mobi) reagiert NICHT
    // auf ein kooperatives Stopp-Flag oder Thread.interrupt() - nur das harte
    // Beenden des Prozesses stoppt ihn. Der Watchdog erkennt genau diesen Fall
    // (kein Fortschritt mehr) und beendet den Prozess, nachdem er sich die
    // ausloesende Datei gemerkt hat, damit der naechste Lauf sie ueberspringt. --
    private static final long WATCHDOG_INTERVAL_MS = 30_000L;
    private static final long STALL_MS = 5L * 60 * 1000; // 5 Min ohne Fortschritt = haengt

    static void start(Context ctx, Runnable onDone) {
        if (running) return;
        running = true;
        stopRequested = false;
        scanned = 0; contentIndexed = 0; currentPath = "";
        Context app = ctx.getApplicationContext();
        contextApp = app;
        searchComicsMetaCached = Settings.searchComicsMeta(app);
        skipContentCached = Settings.skipContentPaths(app);
        Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            try {
                PdfExtractorHelper.init(app);
                SearchStore store = SearchStore.get(app);
                long runStart = System.currentTimeMillis();
                DiagLog.log(app, "Indizierlauf gestartet"
                        + (skipContentCached.isEmpty() ? "" : " (" + skipContentCached.size()
                        + " Datei(en) auf der Überspringen-Liste)"));
                visitedCanonical.clear();
                // Nicht lesbare Wurzelordner auf den zugänglichen internen
                // Speicher abbilden: "/storage/emulated" (bzw. "/storage")
                // selbst kann eine App nicht auflisten - gemeint ist praktisch
                // immer "/storage/emulated/0". So funktioniert eine bestehende
                // (im mageren Ordner-Picker gewählte) Einstellung von selbst,
                // und der vorhandene Index wird per mtime wiederverwendet.
                java.util.Collection<String> stored = Settings.searchFolders(app);
                java.util.List<String> folders = new java.util.ArrayList<>();
                contentRootsCached = new java.util.ArrayList<>();
                for (String cf : stored) {
                    String norm = normalizeRoot(cf);
                    folders.add(norm);
                    if (Settings.contentIndexingEnabled(app, cf)) contentRootsCached.add(norm);
                }
                for (String root : folders) {
                    if (stopRequested) break;
                    File rootDir = new File(root);
                    // WICHTIG: Nur laufen/aufraeumen, wenn der Wurzelordner
                    // tatsaechlich lesbar ist. Ein nicht auflistbarer Ordner
                    // (z.B. "/storage/emulated" selbst - Rechte drwxrws---, von
                    // einer normalen App nicht listbar; zugaenglich ist erst
                    // "/storage/emulated/0") liefert listFiles()==null. Frueher
                    // lief dann trotzdem pruneStale und sortierte ALLE Eintraege
                    // dieses Ordners als "verwaist" aus - zeilenweise, minuten-
                    // lang, das Telefon wurde warm, und beim naechsten Lauf
                    // musste alles neu indiziert werden. Jetzt: ueberspringen,
                    // Index NICHT anfassen, deutlich ins Protokoll schreiben.
                    if (!rootDir.isDirectory() || rootDir.listFiles() == null) {
                        DiagLog.log(app, "Wurzelordner »" + root + "« ist nicht lesbar bzw. kein "
                                + "Verzeichnis – übersprungen, Index NICHT bereinigt. Bitte in den "
                                + "Einstellungen einen zugänglichen Ordner wählen (z. B. /storage/emulated/0 "
                                + "statt /storage/emulated).");
                        continue;
                    }
                    walk(store, rootDir, 0);
                    // Nur als vollstaendig behandeln (und Verschwundenes
                    // entfernen), wenn der Ordner nicht durch einen Stopp
                    // mittendrin abgebrochen wurde - sonst wuerden noch
                    // nicht wieder erreichte Dateien faelschlich als
                    // geloescht/verschoben aussortiert.
                    if (!stopRequested) store.pruneStale(root, runStart);
                }
                if (!stopRequested) Settings.setSearchLastRun(app, System.currentTimeMillis());
                DiagLog.log(app, (stopRequested ? "Indizierlauf gestoppt" : "Indizierlauf fertig")
                        + ": " + scanned + " geprüft, " + contentIndexed + " mit neuem Volltext");
            } catch (Throwable t) {
                android.util.Log.w("EdgeTabSearch", "Indizierlauf abgebrochen", t);
                StackTraceElement[] st = t.getStackTrace();
                String where = (st != null && st.length > 0) ? " @ " + st[0] : "";
                DiagLog.log(app, "Indizierlauf mit Fehler abgebrochen: " + t + where
                        + " (zuletzt: Ordner »" + currentDir + "«"
                        + (currentPath == null || currentPath.isEmpty() ? "" : ", Datei »" + currentPath + "«") + ")");
            } finally {
                running = false;
                if (onDone != null) main.post(onDone);
            }
        }, "EdgeTabSearchIndexer");
        worker.start();
        startWatchdog(app, worker);
    }

    /** Beobachtet den laufenden Indizierer und beendet den Prozess hart, wenn
     *  er ueber {@link #STALL_MS} keinen Fortschritt mehr macht (weder eine
     *  weitere Datei noch einen weiteren Ordner) - der klassische Fall eines
     *  Endlos-Loops in einer Format-Bibliothek, den ein kooperatives Stoppen
     *  nicht erreicht. Die ausloesende Datei wird vorher gemerkt und kuenftig
     *  uebersprungen. */
    private static void startWatchdog(Context app, Thread worker) {
        Thread wd = new Thread(() -> {
            long lastAdvance = System.currentTimeMillis();
            int lastScanned = scanned;
            int lastWalk = walkCallCounter;
            while (running) {
                try { Thread.sleep(WATCHDOG_INTERVAL_MS); } catch (InterruptedException e) { return; }
                if (!running) return;
                // Diagnose-Herzschlag: alle 30 s festhalten, WO der Lauf gerade
                // ist (Ordner + Datei + Zaehler). So zeigt der Log auch dann
                // Pfade, wenn keine Einzeldatei einen Fehler wirft - und man
                // sieht an steigender Ordnerzahl bei stehendem "geprüft" sofort
                // einen Ordner-Amoklauf (Pfad-Alias-Ring) statt nur Wärme.
                try {
                    DiagLog.log(app, "läuft: " + scanned + " geprüft, " + visitedCanonical.size()
                            + " Ordner besucht; gerade: Ordner »" + currentDir + "«"
                            + (currentPath == null || currentPath.isEmpty() ? "" : ", Datei »" + currentPath + "«"));
                } catch (Throwable ignored) {}
                if (scanned != lastScanned || walkCallCounter != lastWalk) {
                    lastScanned = scanned;
                    lastWalk = walkCallCounter;
                    lastAdvance = System.currentTimeMillis();
                    continue;
                }
                if (System.currentTimeMillis() - lastAdvance >= STALL_MS) {
                    // Bei einem Ordner-Hänger ist currentPath leer -> dann den
                    // Ordner als Verursacher merken/melden.
                    String stuck = (currentPath != null && !currentPath.isEmpty()) ? currentPath : currentDir;
                    android.util.Log.w("EdgeTabSearch", "Indizierer haengt seit "
                            + (STALL_MS / 1000) + "s ohne Fortschritt bei: " + stuck
                            + " - wird kuenftig uebersprungen, Prozess wird beendet.");
                    try {
                        DiagLog.log(app, "HÄNGER erkannt: kein Fortschritt seit "
                                + (STALL_MS / 1000) + "s bei »" + stuck + "« (Ordner »" + currentDir
                                + "«). Wird künftig übersprungen, Prozess wird jetzt beendet.");
                    } catch (Throwable ignored) {}
                    if (stuck != null && !stuck.isEmpty()) {
                        try { Settings.addSkipContent(app, stuck); } catch (Throwable ignored) {}
                    }
                    stopRequested = true;
                    worker.interrupt();
                    Process.killProcess(Process.myPid());
                    return;
                }
            }
        }, "EdgeTabSearchWatchdog");
        wd.setDaemon(true);
        wd.start();
    }

    // Kanonische Pfade bereits besuchter Ordner - Android haengt an mehreren
    // Stellen Verknuepfungen ein, die auf denselben echten Ort zeigen (z.B.
    // /storage/self/primary -> /storage/emulated/0). Ohne diese Bremse wuerde
    // ein an /storage gewurzelter Durchlauf jede Datei doppelt erfassen (im
    // schlimmsten Fall, je nach ROM, sogar in einer echten Schleife haengen).
    private static final java.util.Set<String> visitedCanonical = new java.util.HashSet<>();

    // Harte Grenze GEGEN den Fall, dass getCanonicalPath() zwei verschiedene
    // Pfade zum selben echten Ort NICHT auf denselben String abbildet (auf
    // Androids FUSE-Speicher beobachtet: /storage/emulated/0 vs.
    // /storage/self/primary vs. /sdcard koennen je nach ROM uneinheitlich
    // aufgeloest werden) - dann greift visitedCanonical NICHT, und ein
    // Alias-Ring wuerde sonst unbemerkt endlos rekursieren (beobachtet:
    // Sucher lief tagelang mit hoher CPU-Last fest, "gerade dran" zeigte nie
    // eine Datei - die Rekursion kam nie bis zu einer Datei durch). Jede real
    // sinnvolle Ordnerstruktur ist weit flacher als das.
    private static final int MAX_DEPTH = 40;

    // Zweite, breiten-orientierte Notbremse gegen einen Pfad-Alias-Ring, den
    // MAX_DEPTH nicht faengt: rekursiert die Explosion nicht in die Tiefe,
    // sondern immer wieder ueber neue (nicht deduplizierte) Aliaspfade in die
    // Breite, waechst visitedCanonical unbegrenzt und der Lauf macht scheinbar
    // ewig "Fortschritt" (der Watchdog greift dann nicht). Jede real sinnvolle
    // Ordnerstruktur - auch eine sehr grosse Buchsammlung - hat weit weniger
    // Ordner als diese Grenze; ein Alias-Ring erreicht sie in Sekunden.
    private static final int MAX_DIRS = 300_000;

    // Nur voruebergehende Diagnose (siehe Log-Tag "EdgeTabSearchDiag") fuer
    // den bislang nicht sicher root-verursachten CPU-/Akku-Haenger: loggt
    // jeden 2000. walk()-Aufruf mit Tiefe/Pfad, damit ein naechstes
    // Auftreten per logcat tatsaechlich zeigt, WO die Rekursion feststeckt,
    // statt weiter zu raten. Wieder entfernen, sobald die Ursache klar ist.
    private static int walkCallCounter = 0;

    /** Einen nicht auflistbaren Wurzelordner auf den zugänglichen internen
     *  Speicher abbilden. "/storage/emulated" und "/storage" sind für eine App
     *  nicht listbar; gemeint ist der primäre geteilte Speicher, den
     *  {@link android.os.Environment#getExternalStorageDirectory()} liefert
     *  (typisch "/storage/emulated/0"). Lesbare Ordner bleiben unverändert. */
    private static String normalizeRoot(String path) {
        try {
            File f = new File(path);
            if (f.isDirectory() && f.listFiles() != null) return path; // lesbar -> so lassen
            if ("/storage/emulated".equals(path) || "/storage".equals(path) || "/sdcard".equals(path)) {
                File ext = android.os.Environment.getExternalStorageDirectory();
                if (ext != null && ext.isDirectory() && ext.listFiles() != null) {
                    android.util.Log.i("SucherDiag", "Wurzelordner »" + path + "« nicht lesbar -> "
                            + "verwende stattdessen »" + ext.getPath() + "«.");
                    return ext.getPath();
                }
            }
        } catch (Throwable ignored) {}
        return path;
    }

    private static void walk(SearchStore store, File dir, int depth) {
        int n = ++walkCallCounter;
        if (n % 2000 == 0) {
            android.util.Log.d("EdgeTabSearchDiag", "walk #" + n + " depth=" + depth
                    + " visitedSize=" + visitedCanonical.size() + " dir=" + dir);
        }
        if (depth > MAX_DEPTH) {
            android.util.Log.w("EdgeTabSearch", "Abbruch: Ordner zu tief verschachtelt (moeglicher Pfad-Alias-Ring): " + dir);
            return;
        }
        if (visitedCanonical.size() >= MAX_DIRS) {
            android.util.Log.w("EdgeTabSearch", "Abbruch: zu viele Ordner besucht ("
                    + visitedCanonical.size() + ", moeglicher Pfad-Alias-Ring) - Lauf wird gestoppt.");
            DiagLog.log(contextApp, "Abbruch: unplausibel viele Ordner besucht ("
                    + visitedCanonical.size() + ", möglicher Pfad-Alias-Ring) bei »" + dir + "«.");
            stopRequested = true; // beendet die restliche Rekursion sauber
            return;
        }
        currentDir = dir.getPath();
        String canon;
        try { canon = dir.getCanonicalPath(); } catch (Exception e) { canon = dir.getAbsolutePath(); }
        if (!visitedCanonical.add(canon)) return;

        File[] children = dir.listFiles();
        if (children == null) return;
        if (children.length > 5000) {
            android.util.Log.d("EdgeTabSearchDiag", "grosser Ordner: " + children.length + " Eintraege in " + dir);
        }
        for (File f : children) {
            if (stopRequested) return;
            if (f.isDirectory()) {
                if (f.isHidden() || f.getName().startsWith(".")) continue; // .thumbnails, .trash etc.
                walk(store, f, depth + 1);
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
            // isSupported() zuerst: fuer ein Format, das ohnehin nie Inhalt
            // liefern kann (Fotos, Musik, Videos, APKs - die Mehrheit der
            // Dateien auf jedem Geraet), darf "content_ok" in der DB (bleibt
            // dort fuer immer 0) den Schnell-Ueberspringen-Pfad nicht
            // blockieren - sonst wuerde JEDE nicht-Dokument-Datei bei JEDEM
            // Lauf komplett neu verarbeitet, nie uebersprungen (gefunden, weil
            // ein Lauf reproduzierbar bei einer .mp3 haengen blieb: die lief
            // immer wieder in den teuren Pfad, obwohl laengst "fertig").
            // Dateien, die einen frueheren Lauf zum Haengen brachten, bekommen
            // weder Inhalt noch Titelbild - nur Metadaten (der teure, moeglich
            // in einer Endlosschleife haengende Pfad wird komplett gemieden).
            boolean poisoned = skipContentCached.contains(f.getPath());
            boolean wantContent = !poisoned && isSupported(ext) && f.length() <= MAX_CONTENT_BYTES
                    && contentWanted(f.getPath());
            SearchStore.KnownState known = store.known(f.getPath());
            if (known.mtime == mtime && (!wantContent || known.contentOk)) {
                // Unveraendert seit dem letzten Lauf, UND (Inhalt entweder gar
                // nicht gewuenscht oder schon vorhanden) - nichts neu zu tun.
                // Ohne die zweite Bedingung wuerde ein nachtraeglich fuer
                // diesen Ordner eingeschaltetes "Inhalt durchsuchbar machen"
                // nie greifen, solange sich keine einzige Datei mehr aendert
                // (bei einer stabilen Buchsammlung: nie) - Mathias' Verdacht,
                // dass mit dem Indizieren "irgendwas nicht stimmt".
                if (!poisoned && contextApp != null && !Thumbnails.exists(contextApp, f.getPath())) {
                    generateThumbSafely(contextApp, f, ext);
                }
                scanned++;
                return;
            }

            long created = readCreated(f, mtime);

            FileExtractors.Result r = new FileExtractors.Result();
            // Bei einer vergifteten Datei den Extraktor GANZ auslassen (auch
            // die Metadaten-Extraktion, denn schon die kann der Haenger gewesen
            // sein) - es bleibt beim reinen Datei-Eintrag (Name/Typ/Groesse/
            // Datum), der bleibt ueber die Namenssuche auffindbar.
            if (!poisoned && isSupported(ext)) { // Metadaten sind billig, immer versuchen
                boolean comic = "cbz".equals(ext) || "cbr".equals(ext);
                if (!comic || searchComicsMetaCached) {
                    // Breadcrumb VOR der (teuren, evtl. haengenden) Extraktion -
                    // nur nach logcat (nicht in die kleine Datei), damit bei
                    // einem harten Prozess-Kill die zuletzt begonnene Datei
                    // sichtbar bleibt (Tag "SucherDiag").
                    android.util.Log.i("SucherDiag", "extrahiere" + (wantContent ? " Inhalt" : " Metadaten")
                            + " [" + ext + ", " + f.length() + "B]: " + f.getPath());
                    r = FileExtractors.extract(f, ext, wantContent);
                    if (r.text != null && !r.text.isEmpty()) contentIndexed++;
                }
            }
            store.upsert(f.getPath(), name, ext, f.length(), mtime, created,
                    r.text, r.drm, r.title, r.author, r.series, r.seriesIndex);
            if (!poisoned && contextApp != null) generateThumbSafely(contextApp, f, ext);
            scanned++;
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "Datei uebersprungen: " + f.getPath(), t);
            DiagLog.log(contextApp, "Datei übersprungen (Fehler beim Verarbeiten): »"
                    + f.getPath() + "« - " + t);
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

    // Auch von SearchStore.contentEligibleCount() genutzt (Fortschritts-
    // Prozentanzeige) - eine einzige Quelle statt einer zweiten, leicht
    // auseinanderlaufenden Kopie dieser Liste.
    static final String[] SUPPORTED_EXTS = {
            "txt", "md", "markdown", "csv", "log", "json", "xml", "srt", "ini", "yaml", "yml",
            "docx", "xlsx", "pptx", "doc", "xls", "ppt", "epub", "fb2",
            "mobi", "azw", "azw3", "prc", "pdf", "cbz", "cbr"
    };
    private static final java.util.Set<String> SUPPORTED_EXT_SET =
            new java.util.HashSet<>(java.util.Arrays.asList(SUPPORTED_EXTS));

    private static boolean isSupported(String ext) {
        return SUPPORTED_EXT_SET.contains(ext);
    }
}

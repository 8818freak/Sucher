package de.herbers.sucher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sucher braucht, anders als EdgeTab, nur eine Handvoll Einstellungen -
 *  welche Ordner durchsucht werden, ob Comic-Metadaten mitindiziert werden,
 *  wann zuletzt indiziert wurde. */
public final class Settings {

    private static final String PREFS = "sucher_settings";
    private static final String K_SEARCH_FOLDERS = "search_folders";
    private static final String K_CONTENT_FOLDERS = "content_folders"; // Teilmenge von search_folders
    private static final String K_SEARCH_COMICS  = "search_comics";
    private static final String K_SEARCH_LAST_RUN = "search_last_run";
    private static final String K_NOTIF_SOURCES = "notif_sources"; // freigegebene Paketnamen
    private static final String K_AUTO_REINDEX = "auto_reindex"; // periodischer Hintergrund-Abgleich
    private static final String K_FONT_SCALE = "font_scale"; // Prozent, 100 = normal
    private static final String K_SEARCH_HISTORY = "search_history"; // neueste zuerst, ""-getrennt
    private static final String K_SKIP_CONTENT = "skip_content_paths"; // Dateien, die den Indizierer haengen liessen
    private static final String K_THUMBS_PERSIST = "thumbs_persistent"; // Vorschaubilder dauerhaft (Dateien) statt Cache
    private static final int MAX_HISTORY = 15;

    private Settings() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Leer als Voreinstellung, damit nicht ungefragt das ganze Geraet
    // durchsucht wird (dauert, kostet Akku, ist Privatsache).
    public static Set<String> searchFolders(Context c) {
        return new HashSet<>(p(c).getStringSet(K_SEARCH_FOLDERS, Collections.<String>emptySet()));
    }
    public static void addSearchFolder(Context c, String path) {
        Set<String> s = searchFolders(c); s.add(path);
        p(c).edit().putStringSet(K_SEARCH_FOLDERS, s).apply();
    }
    public static void removeSearchFolder(Context c, String path) {
        Set<String> s = searchFolders(c); s.remove(path);
        p(c).edit().putStringSet(K_SEARCH_FOLDERS, s).apply();
        setContentIndexingEnabled(c, path, false);
    }

    // ---- Voller Dateiinhalt nur fuer ausdruecklich freigegebene Ordner -
    // Name/Titel/Autor/Serie/Datum werden fuer ALLE gewaehlten Ordner erfasst
    // (billig), aber der eigentliche Text (teuer an Zeit UND Speicher - bei
    // Mathias' ~22000 Buechern waere das ein zweistelliger GB-Index) nur dort,
    // wo das hier angehakt ist. Leer/aus als Voreinstellung fuer neu
    // hinzugefuegte Ordner - wer nur ein paar hundert Dokumente hat, tippt
    // einmal "Inhalt durchsuchbar machen" und hat trotzdem alles; wer wie
    // Mathias sehr viel mitschleppt, waehlt gezielt aus. ----
    public static boolean contentIndexingEnabled(Context c, String path) {
        return p(c).getStringSet(K_CONTENT_FOLDERS, Collections.<String>emptySet()).contains(path);
    }
    public static void setContentIndexingEnabled(Context c, String path, boolean on) {
        Set<String> s = new HashSet<>(p(c).getStringSet(K_CONTENT_FOLDERS, Collections.<String>emptySet()));
        if (on) s.add(path); else s.remove(path);
        p(c).edit().putStringSet(K_CONTENT_FOLDERS, s).apply();
    }

    // ---- Dateien, die einen Indizierlauf zum Haengen brachten (Endlosschleife
    // in einer Format-Bibliothek o.ae.). Die Selbstheilung im SearchIndexer
    // merkt sich so eine Datei hier, bevor sie den Prozess beendet; kuenftige
    // Laeufe erfassen dann nur noch ihre Metadaten (Name/Groesse/Datum), nie
    // wieder den teuren Inhalt/das Titelbild - so wird derselbe Haenger nicht
    // endlos wiederholt. commit() (nicht apply()), weil direkt danach der
    // Prozess hart beendet wird und der Eintrag den Neustart ueberleben muss. --
    public static Set<String> skipContentPaths(Context c) {
        return new HashSet<>(p(c).getStringSet(K_SKIP_CONTENT, Collections.<String>emptySet()));
    }
    public static void addSkipContent(Context c, String path) {
        Set<String> s = skipContentPaths(c);
        if (s.add(path)) p(c).edit().putStringSet(K_SKIP_CONTENT, s).commit();
    }
    public static void removeSkipContent(Context c, String path) {
        Set<String> s = skipContentPaths(c);
        if (s.remove(path)) p(c).edit().putStringSet(K_SKIP_CONTENT, s).apply();
    }

    public static boolean searchComicsMeta(Context c) { return p(c).getBoolean(K_SEARCH_COMICS, true); }
    public static void setSearchComicsMeta(Context c, boolean on) { p(c).edit().putBoolean(K_SEARCH_COMICS, on).apply(); }
    public static long searchLastRun(Context c) { return p(c).getLong(K_SEARCH_LAST_RUN, 0); }
    public static void setSearchLastRun(Context c, long t) { p(c).edit().putLong(K_SEARCH_LAST_RUN, t).apply(); }

    // ---- Welche Apps' Benachrichtigungen durchsuchbar sein sollen -----
    // NotificationCapture schneidet technisch von jeder App etwas mit (muss
    // es auch, sonst koennte man hier nie eine App zur Auswahl anbieten -
    // dasselbe Henne-Ei-Problem wie bei EdgeTabs Posteingang-Quellen), aber
    // durchsuchbar/anzeigbar ist nur, was hier ausdruecklich freigegeben ist.
    // Leer als Voreinstellung - bewusst nichts sichtbar, bis man selbst waehlt. ----
    public static Set<String> notifSources(Context c) {
        return new HashSet<>(p(c).getStringSet(K_NOTIF_SOURCES, Collections.<String>emptySet()));
    }
    public static boolean isNotifSourceEnabled(Context c, String pkg) { return notifSources(c).contains(pkg); }
    public static void setNotifSourceEnabled(Context c, String pkg, boolean on) {
        Set<String> s = notifSources(c);
        if (on) s.add(pkg); else s.remove(pkg);
        p(c).edit().putStringSet(K_NOTIF_SOURCES, s).apply();
    }

    // ---- Regelmaessiger Hintergrund-Abgleich (IndexJobService) - Standard AN,
    // sobald Zugriff+Ordner stehen; ueber JobScheduler statt eines echten
    // Dauer-Dienstes wie EdgeTabs (batterieschonender, laeuft trotzdem auch
    // wenn Sucher geschlossen ist). ----
    public static boolean autoReindex(Context c) { return p(c).getBoolean(K_AUTO_REINDEX, true); }
    public static void setAutoReindex(Context c, boolean on) { p(c).edit().putBoolean(K_AUTO_REINDEX, on).apply(); }

    // ---- Vorschaubilder dauerhaft speichern? AN = app-interner Dateien-Ordner
    // (ueberlebt "Cache leeren" / SD Maid, zaehlt aber als App-Daten). AUS =
    // Cache-Ordner (darf vom System/Werkzeugen bei Speichernot geraeumt werden,
    // spart dauerhaften Platz). Standard AN (Vorschaubilder bleiben erhalten). --
    public static boolean thumbsPersistent(Context c) { return p(c).getBoolean(K_THUMBS_PERSIST, true); }
    public static void setThumbsPersistent(Context c, boolean on) { p(c).edit().putBoolean(K_THUMBS_PERSIST, on).apply(); }

    // ---- Schriftgroesse - wie EdgeTab, 100 = normal, Bereich 80..150 (%) ----
    public static float fontScale(Context c) { return p(c).getInt(K_FONT_SCALE, 100) / 100f; }
    public static int fontScalePercent(Context c) { return p(c).getInt(K_FONT_SCALE, 100); }
    public static void setFontScale(Context c, int percent) {
        p(c).edit().putInt(K_FONT_SCALE, Math.max(80, Math.min(150, percent))).apply();
    }

    // ---- Suchverlauf (nur die einfache Freitextsuche, nicht die erweiterte
    // Suche mit ihren vielen Einzelfeldern) - neueste zuerst, ohne Duplikate,
    // auf MAX_HISTORY Eintraege gedeckelt. Als ein einziger String statt
    // StringSet gespeichert, weil StringSet die Reihenfolge nicht erhaelt,
    // "neueste zuerst" aber der ganze Sinn eines Verlaufs ist. ----
    public static List<String> searchHistory(Context c) {
        String raw = p(c).getString(K_SEARCH_HISTORY, "");
        List<String> out = new ArrayList<>();
        if (!raw.isEmpty()) for (String s : raw.split("")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    public static void addSearchHistory(Context c, String query) {
        if (query == null || query.trim().isEmpty()) return;
        String q = query.trim();
        List<String> h = searchHistory(c);
        h.remove(q); // Dubletten vermeiden - wandert stattdessen nach vorn
        h.add(0, q);
        while (h.size() > MAX_HISTORY) h.remove(h.size() - 1);
        p(c).edit().putString(K_SEARCH_HISTORY, String.join("", h)).apply();
    }
    public static void removeSearchHistory(Context c, String query) {
        List<String> h = searchHistory(c);
        h.remove(query);
        p(c).edit().putString(K_SEARCH_HISTORY, String.join("", h)).apply();
    }
    public static void clearSearchHistory(Context c) {
        p(c).edit().remove(K_SEARCH_HISTORY).apply();
    }

    // ---- Sicherung/Wiederherstellen ---------------------------------------
    // Generischer Dump/Restore der GESAMTEN sucher_settings-Datei (nicht nur
    // der Felder in dieser Klasse) - deckt automatisch auch kuenftig
    // hinzukommende Einstellungen mit ab. Der eigentliche Suchindex
    // (SearchStore/edgetab_search.db) ist NICHT hier drin, sondern wird
    // separat als Datei mitgesichert (siehe Backup.java).
    private static final String BACKUP_HEADER = "Sucher-Backup 1";

    public static String exportText(Context c) {
        StringBuilder sb = new StringBuilder(BACKUP_HEADER).append('\n');
        for (java.util.Map.Entry<String, ?> e : p(c).getAll().entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if (v instanceof String) {
                sb.append("s").append(key).append('').append(b64((String) v)).append('\n');
            } else if (v instanceof Boolean) {
                sb.append("b").append(key).append('').append(v).append('\n');
            } else if (v instanceof Integer) {
                sb.append("i").append(key).append('').append(v).append('\n');
            } else if (v instanceof Long) {
                sb.append("l").append(key).append('').append(v).append('\n');
            } else if (v instanceof Float) {
                sb.append("f").append(key).append('').append(v).append('\n');
            } else if (v instanceof Set) {
                StringBuilder joined = new StringBuilder();
                for (Object s : (Set<?>) v) joined.append(String.valueOf(s)).append('');
                sb.append("x").append(key).append('').append(b64(joined.toString())).append('\n');
            }
        }
        return sb.toString();
    }

    /** Ersetzt ALLE aktuellen Einstellungen durch den Inhalt einer Sicherung.
     *  Liefert false bei erkennbar falschem/beschaedigtem Format, ohne etwas
     *  zu aendern. */
    public static boolean importText(Context c, String text) {
        if (text == null || !text.startsWith(BACKUP_HEADER)) return false;
        SharedPreferences.Editor ed = p(c).edit().clear();
        try {
            for (String line : text.split("\n", -1)) {
                if (line.isEmpty()) continue;
                String[] f = line.split("", -1);
                if (f.length < 3) continue;
                String type = f[0], key = f[1], val = f[2];
                switch (type) {
                    case "s": ed.putString(key, unb64(val)); break;
                    case "b": ed.putBoolean(key, Boolean.parseBoolean(val)); break;
                    case "i": ed.putInt(key, Integer.parseInt(val)); break;
                    case "l": ed.putLong(key, Long.parseLong(val)); break;
                    case "f": ed.putFloat(key, Float.parseFloat(val)); break;
                    case "x":
                        String joined = unb64(val);
                        Set<String> set = new HashSet<>();
                        for (String part : joined.split("", -1)) if (!part.isEmpty()) set.add(part);
                        ed.putStringSet(key, set);
                        break;
                    default: break;
                }
            }
        } catch (Exception e) {
            return false;
        }
        ed.apply();
        return true;
    }

    private static String b64(String s) {
        return android.util.Base64.encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
    }
    private static String unb64(String s) {
        return new String(android.util.Base64.decode(s, android.util.Base64.NO_WRAP),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}

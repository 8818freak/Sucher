package de.herbers.sucher;

import de.herbers.docextract.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.herbers.sucher.search.SearchQuery;

/**
 * Eigener Datei-Volltext-Index (Name, Ort, Aenderungszeit + extrahierter
 * Inhalt) - wie NotificationStore, nur mit einer FTS4-Volltexttabelle statt
 * einer normalen. SQLites FTS4 ist im Android-System eingebaut, keine
 * Bibliothek noetig (anders als bei PDF/altem Office, siehe FileExtractors).
 */
public class SearchStore extends SQLiteOpenHelper {

    private static final String DB = "edgetab_search.db";
    private static final int VERSION = 4;
    private static SearchStore instance;

    public static synchronized SearchStore get(Context ctx) {
        if (instance == null) instance = new SearchStore(ctx.getApplicationContext());
        return instance;
    }

    /** Datei des Index auf der Platte - fuer Sichern/Wiederherstellen
     *  (Backup.java kopiert diese Datei direkt, statt jede Zeile einzeln zu
     *  exportieren). */
    public static File dbFile(Context ctx) {
        return ctx.getApplicationContext().getDatabasePath(DB);
    }

    /** Schliesst eine offene Instanz (falls vorhanden) und checkpointet WAL
     *  vorher komplett in die Hauptdatei, damit eine Kopie/ein Ersetzen der
     *  Datei konsistent ist. Die naechste get()-Anfrage oeffnet automatisch
     *  neu. Fuer Sichern (Kopie muss vollstaendig sein) UND Wiederherstellen
     *  (Datei darf nicht gerade offen sein, wenn sie ersetzt wird). */
    public static synchronized void closeForBackup() {
        if (instance != null) {
            try { instance.getWritableDatabase().execSQL("PRAGMA wal_checkpoint(FULL)"); } catch (Exception ignored) {}
            instance.close();
            instance = null;
        }
    }

    private SearchStore(Context ctx) {
        super(ctx, DB, null, VERSION);
        // Without WAL, SQLiteOpenHelper serialises all access onto one
        // connection - a search query on the main thread would queue up
        // behind the indexer's writes during a long (re-)index run.
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Metadaten (fuer Datum/Typ-Filter und um beim Neu-Indizieren
        // unveraenderte Dateien per mtime zu ueberspringen).
        db.execSQL(
            "CREATE TABLE files (" +
            "  path TEXT PRIMARY KEY," +
            "  name TEXT NOT NULL," +
            "  ext TEXT," +
            "  size INTEGER," +
            "  mtime INTEGER," +
            "  created INTEGER," +      // Erstellungszeit, wo das Dateisystem sie liefert, sonst = mtime
            "  indexed_at INTEGER," +
            "  content_ok INTEGER DEFAULT 0," + // 0 = nur Name/Metadaten, 1 = Inhalt mitindiziert
            "  drm INTEGER DEFAULT 0," +       // 1 = erkannt kopiergeschuetzt, Inhalt bewusst ausgelassen
            "  title TEXT," +           // aus Dokument-/Buch-Metadaten, nicht der Dateiname
            "  author TEXT," +
            "  series TEXT," +
            "  series_index REAL" +
            ")");
        db.execSQL("CREATE INDEX idx_files_ext ON files(ext)");
        db.execSQL("CREATE INDEX idx_files_mtime ON files(mtime)");
        db.execSQL("CREATE INDEX idx_files_created ON files(created)");
        db.execSQL("CREATE INDEX idx_files_author ON files(author)");
        db.execSQL("CREATE INDEX idx_files_series ON files(series)");
        // Volltext getrennt (FTS4) - "path" hier nur zum Zurueckverknuepfen,
        // die eigentliche Suche laeuft ueber MATCH auf title/body.
        db.execSQL("CREATE VIRTUAL TABLE content_fts USING fts4(path, title, body)");
        createNotifTables(db);
    }

    private void createNotifTables(SQLiteDatabase db) {
        // Von Benachrichtigungen mitgeschnittener Text - deckt Chats/Mails
        // teilweise ab, da wir nicht an deren eigene Datenbanken kommen (siehe
        // EdgeTabs Recherche zu BBM Enterprise/Hub+ Services): nur das, was
        // tatsaechlich als Systembenachrichtigung durchkam, waehrend Sucher
        // lief und die Berechtigung hatte - keine volle Chat-Historie.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS notifications (" +
            "  _id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  pkg TEXT NOT NULL," +
            "  app_label TEXT," +
            "  title TEXT," +
            "  text TEXT," +
            "  posted INTEGER," +
            "  info_json TEXT" +   // vollstaendiger, verlustfreier Abzug (Notifications.toJson)
            ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_posted ON notifications(posted)");
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS notif_fts USING fts4(nid, title, body)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) createNotifTables(db);
        if (oldV < 3) {
            db.execSQL("ALTER TABLE files ADD COLUMN created INTEGER");
            db.execSQL("ALTER TABLE files ADD COLUMN title TEXT");
            db.execSQL("ALTER TABLE files ADD COLUMN author TEXT");
            db.execSQL("ALTER TABLE files ADD COLUMN series TEXT");
            db.execSQL("ALTER TABLE files ADD COLUMN series_index REAL");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_created ON files(created)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_author ON files(author)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_series ON files(series)");
        }
        // Wenn die notifications-Tabelle schon bestand (oldV>=2), ihr die neue
        // info_json-Spalte nachruesten. Bei oldV<2 hat createNotifTables sie
        // oben bereits MIT dieser Spalte angelegt - dann NICHT erneut altern.
        if (oldV >= 2 && oldV < 4) {
            db.execSQL("ALTER TABLE notifications ADD COLUMN info_json TEXT");
        }
    }

    /** Metadaten + (falls vorhanden) Inhalt einer Datei ablegen/aktualisieren.
     *  clearOldContent: nur dann die evtl. vorhandene Volltext-Zeile vorher
     *  loeschen, wenn die Datei ueberhaupt schon Volltext hatte. Das ist teuer:
     *  content_fts ist eine FTS4-Tabelle mit den kompletten Buchtexten, und ein
     *  DELETE ... WHERE path=? MUSS dort alle Zeilen (Gigabytes) durchsuchen,
     *  weil path kein durchsuchbarer Schluessel ist - fuer eine NEUE Datei (ohne
     *  alten Volltext) wuerde das nur unnoetig ~10-15 s je Datei kosten. */
    public void upsert(String path, String name, String ext, long size, long mtime, long created,
                        String content, boolean drm, String title, String author, String series,
                        float seriesIndex, boolean clearOldContent) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (clearOldContent) db.delete("content_fts", "path=?", new String[]{path});
            ContentValues v = new ContentValues();
            v.put("path", path);
            v.put("name", name);
            v.put("ext", ext);
            v.put("size", size);
            v.put("mtime", mtime);
            v.put("created", created);
            v.put("indexed_at", System.currentTimeMillis());
            v.put("content_ok", (content != null && !content.isEmpty()) ? 1 : 0);
            v.put("drm", drm ? 1 : 0);
            v.put("title", title);
            v.put("author", author);
            v.put("series", series);
            v.put("series_index", seriesIndex);
            db.insertWithOnConflict("files", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            if (content != null && !content.isEmpty()) {
                ContentValues fv = new ContentValues();
                fv.put("path", path);
                fv.put("title", title != null ? title : name);
                fv.put("body", content);
                db.insert("content_fts", null, fv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Schlanke Metadaten-Ablage OHNE Inhalt/Titel (Phase 1 der zweiphasigen
     *  Indizierung): schreibt nur Name/Typ/Groesse/Datum, damit JEDE Datei
     *  sofort per Namenssuche gefunden wird - auch bevor die (teure) Inhalts-
     *  und Titel-Erfassung (Phase 2) an die Reihe kommt. content_ok=0, ein evtl.
     *  vorhandener alter Volltext (geaenderte Datei) wird entfernt und in Phase 2
     *  neu erfasst. Fuer UNVERAENDERTE Dateien wird diese Methode gar nicht erst
     *  aufgerufen (siehe SearchIndexer), deren Inhalt/Titel bleibt also erhalten. */
    public void upsertMeta(String path, String name, String ext, long size, long mtime, long created,
                           boolean clearOldContent) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Nur wenn die Datei vorher Volltext hatte (siehe upsert()): der
            // DELETE auf der FTS4-Tabelle ist sonst ein teurer Komplettscan.
            if (clearOldContent) db.delete("content_fts", "path=?", new String[]{path});
            ContentValues v = new ContentValues();
            v.put("path", path);
            v.put("name", name);
            v.put("ext", ext);
            v.put("size", size);
            v.put("mtime", mtime);
            v.put("created", created);
            v.put("indexed_at", System.currentTimeMillis());
            v.put("content_ok", 0);
            db.insertWithOnConflict("files", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Bekannter Stand einer bereits indizierten Datei: Aenderungszeit
     *  (-1, falls unbekannt) und ob ihr Inhalt bereits mitindiziert wurde. */
    public static final class KnownState {
        public long mtime = -1;
        public long size = -1;
        public boolean contentOk;
    }

    public KnownState known(String path) {
        KnownState s = new KnownState();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT mtime, content_ok, size FROM files WHERE path=?", new String[]{path});
        if (c.moveToFirst()) {
            s.mtime = c.getLong(0);
            s.contentOk = c.getInt(1) != 0;
            s.size = c.getLong(2);
        }
        c.close();
        return s;
    }

    /** Kandidaten fuer die Titelbild-Tranche (Phase 3): zuletzt geaenderte
     *  Dateien zuerst (die schaut man am ehesten an), je Aufruf gedeckelt.
     *  Liefert Pfad + Endung; der Aufrufer filtert titelbildfaehige und erzeugt
     *  fehlende Bilder.
     *  Reine Bildformate werden schon hier ausgeschlossen: sie SIND ihr eigenes
     *  Titelbild und erscheinen beim Anzeigen sofort - so fuellt sich das
     *  gedeckelte Fenster (limit) mit den teuren Titelbildern (Buch-Cover, PDF,
     *  Office) statt mit Fotos. Endungsliste = Thumbnails.isImageExt(); der
     *  Aufrufer (thumbPass) prueft dieselbe Bedingung nochmals als Riegel. */
    public List<String[]> thumbCandidates(int limit) {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT path, ext FROM files "
                + "WHERE ext NOT IN ('jpg','jpeg','png','gif','webp','bmp') "
                + "ORDER BY mtime DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
        c.close();
        return out;
    }

    /** Markiert die angegebenen (in diesem Lauf angetroffenen, aber
     *  unveraenderten) Dateien als "gesehen", indem indexed_at auf ts gesetzt
     *  wird. OHNE das hielte {@link #pruneStale} jede unveraenderte Datei fuer
     *  verwaist (ihr indexed_at bliebe < runStart) und wuerde sie loeschen -
     *  inklusive des teuren FTS-Loeschens ihres Volltexts, was den Lauf zum
     *  Haengen brachte. path ist PRIMARY KEY, das UPDATE ist guenstig; in
     *  Bloecken und einer Transaktion (nicht tausende Einzel-Commits). */
    public void touchIndexed(java.util.List<String> paths, long ts) {
        if (paths == null || paths.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        final int CHUNK = 400; // unter SQLites Variablenlimit (999)
        db.beginTransaction();
        try {
            for (int i = 0; i < paths.size(); i += CHUNK) {
                java.util.List<String> part = paths.subList(i, Math.min(i + CHUNK, paths.size()));
                StringBuilder ph = new StringBuilder();
                for (int k = 0; k < part.size(); k++) ph.append(k == 0 ? "?" : ",?");
                String[] args = new String[part.size() + 1];
                args[0] = String.valueOf(ts);
                for (int k = 0; k < part.size(); k++) args[k + 1] = part.get(k);
                db.execSQL("UPDATE files SET indexed_at=? WHERE path IN (" + ph + ")", args);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Alle Eintraege zu Pfaden entfernen, die beim letzten Durchlauf nicht
     *  mehr angetroffen wurden (geloeschte/verschobene Dateien) - alles
     *  unterhalb eines der gerade durchsuchten Wurzelordner, aelter als
     *  runStart. */
    public void pruneStale(String rootPrefix, long runStart) {
        SQLiteDatabase db = getWritableDatabase();
        // LIKE-Sonderzeichen im Ordnernamen selbst escapen (z.B. "my_books"
        // wuerde "_" sonst als Ein-Zeichen-Platzhalter lesen und faelschlich
        // auch "myXbooks" treffen) und eine echte Pfadgrenze erzwingen, sonst
        // wuerde ein Wurzelordner "/sd/books" auch "/sd/books2/..." erfassen.
        String escaped = rootPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = (escaped.endsWith("/") ? escaped : escaped + "/") + "%";
        Cursor c = db.rawQuery("SELECT path FROM files WHERE path LIKE ? ESCAPE '\\' AND indexed_at < ?",
                new String[]{pattern, String.valueOf(runStart)});
        List<String> stale = new ArrayList<>();
        while (c.moveToNext()) stale.add(c.getString(0));
        c.close();
        if (stale.isEmpty()) return;
        // In Bloecken mit "path IN (...)" loeschen, in EINER Transaktion.
        // Wichtig: ein DELETE auf der FTS4-Tabelle ist ein Komplettscan des
        // Volltexts (path ist dort keine indizierte Spalte). Frueher lief das
        // PRO verwaister Datei in einer Schleife -> O(N x Indexgroesse), bei
        // vielen Verwaisten und grossen Buchtexten ein minutenlanger Haenger
        // (der Watchdog beschuldigte dann faelschlich die zuletzt gemerkte
        // Datei). Ein Block-DELETE scannt den Volltext nur EINMAL je Block
        // statt einmal je Datei - um Groessenordnungen schneller und kuehler.
        final int CHUNK = 400; // unter SQLites Variablenlimit (999)
        db.beginTransaction();
        try {
            for (int i = 0; i < stale.size(); i += CHUNK) {
                List<String> part = stale.subList(i, Math.min(i + CHUNK, stale.size()));
                StringBuilder ph = new StringBuilder();
                for (int k = 0; k < part.size(); k++) ph.append(k == 0 ? "?" : ",?");
                String[] args = part.toArray(new String[0]);
                db.delete("files", "path IN (" + ph + ")", args);
                db.delete("content_fts", "path IN (" + ph + ")", args);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public static class FileHit {
        public String path, name, ext, snippet, title, author, series;
        public long size, mtime, created;
        public float seriesIndex;
        public boolean drm;
        public boolean isFolder;   // true = Ordner-Treffer (Suchbereich "Ordnernamen")
    }

    private static final String FILE_COLS =
            "path, name, ext, size, mtime, created, drm, title, author, series, series_index";

    /** Baut ein "path IN (?,?,...)" -Fragment fuer eine Pfad-Einschraenkung
     *  (Ergebnis-eingrenzen, siehe MainActivity) - leerer/null scope liefert
     *  ein leeres Fragment (keine Einschraenkung). Scopes stammen immer aus
     *  einem vorigen, bereits limitierten Suchergebnis (siehe search()/
     *  advancedSearch() limit-Parameter), bleiben also klein genug fuer
     *  Inline-Parameter statt einer eigenen Temp-Tabelle. */
    private static String scopeClause(Collection<String> scope, List<String> argsOut) {
        if (scope == null || scope.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" AND path IN (");
        boolean first = true;
        for (String p : scope) {
            sb.append(first ? "?" : ",?");
            first = false;
            argsOut.add(p);
        }
        return sb.append(')').toString();
    }

    /** Einschraenkung auf EINEN Ordner samt Unterordnern (Mathias' Wunsch,
     *  "in einem bestimmten Ordner und seinen Unterordnern suchen") - als
     *  Pfad-Praefix "<ordner>/%" per LIKE. Leerer/null Praefix schraenkt nicht
     *  ein. LIKE-Sonderzeichen im Ordnernamen werden escaped (wie in
     *  pruneStale), damit z.B. "_" nicht als Platzhalter wirkt. col ist die
     *  Pfadspalte im jeweiligen SQL ("path" bzw. "f.path" im FTS-Join). */
    private static String folderClause(String folderPrefix, List<String> argsOut, String col) {
        if (folderPrefix == null || folderPrefix.isEmpty()) return "";
        String escaped = folderPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = (escaped.endsWith("/") ? escaped : escaped + "/") + "%";
        argsOut.add(pattern);
        return " AND " + col + " LIKE ? ESCAPE '\\'";
    }

    // ---- Neue, engine-basierte Suche (Suchsyntax-Spezifikation §10) ----------

    public static final int AREA_FOLDER = 0;   // Ordnernamen (aus Pfaden abgeleitet)
    public static final int AREA_FILE = 1;     // Dateinamen
    public static final int AREA_CONTENT = 2;  // Dateiinhalt
    public static final int AREA_ALL = 3;      // Dateiname ODER Inhalt (wie fruehere Standardsuche)

    /** Engine-basierte Suche mit der neuen Syntax (siehe SearchQuery). Der
     *  Suchbereich (area) ist einzeln gewaehlt; FTS/LIKE liefern nur Kandidaten,
     *  der exakte Abgleich macht SearchQuery.matches(). Bei Inhalt wird ein
     *  Schnipsel erzeugt; bei Ordnernamen werden die Ordner aus den Dateipfaden
     *  abgeleitet (FileHit.isFolder = true). */
    public List<FileHit> searchEngine(String query, int area, SearchQuery.Options opts,
                                      int limit, Collection<String> scope, String folderPrefix) {
        List<FileHit> out = new ArrayList<>();
        SearchQuery q = SearchQuery.parse(query);
        if (q.isEmpty()) return out;
        SQLiteDatabase db = getReadableDatabase();

        if (area == AREA_ALL) {
            // "Alles" = Dateiname ODER Inhalt (wie die fruehere Standardsuche),
            // mit der neuen Syntax, nach Pfad entdoppelt. Inhalts-Treffer zuerst
            // (die bringen einen Schnipsel mit).
            java.util.LinkedHashMap<String, FileHit> merged = new java.util.LinkedHashMap<>();
            for (FileHit h : searchEngine(query, AREA_CONTENT, opts, limit, scope, folderPrefix))
                merged.put(h.path, h);
            for (FileHit h : searchEngine(query, AREA_FILE, opts, limit, scope, folderPrefix))
                merged.putIfAbsent(h.path, h);
            out.addAll(merged.values());
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }

        if (area == AREA_CONTENT) {
            // Schnellpfad: einfache Wortsuche (ganzes Wort / Wort mit Endstern,
            // ohne Ausschluss/Phrase/Innenstern, nicht case-sensitiv) kann FTS
            // exakt beantworten - OHNE die kompletten Dateitexte zu laden. Das
            // verhindert, dass ein haeufiger Begriff in einer grossen Bibliothek
            // hunderte MB Text in den (Single-Thread-)Suchthread zieht und jede
            // weitere Suche minutenlang blockiert (Haenger-Bericht 2026-10-06).
            // Der SQLite-snippet() liefert den Fundstellen-Ausschnitt direkt.
            if (!opts.caseSensitive && !q.needsBodyScan()) {
                String ftsX = q.ftsExact();
                if (ftsX != null) {
                    // Ausschluss-Begriffe: Pfade sammeln, die einen Ausschluss
                    // treffen (eigene FTS-Abfragen statt des unsicheren NOT),
                    // und danach herausfiltern.
                    java.util.Set<String> excluded = new java.util.HashSet<>();
                    for (String negExpr : q.ftsExcludeExprs()) {
                        Cursor ce = db.rawQuery(
                                "SELECT path FROM content_fts WHERE content_fts MATCH ?",
                                new String[]{negExpr});
                        while (ce.moveToNext()) excluded.add(ce.getString(0));
                        ce.close();
                    }
                    List<String> a = new ArrayList<>();
                    a.add(ftsX);
                    String sc = scopeClause(scope, a).replace("path", "f.path");
                    String fo = folderClause(folderPrefix, a, "f.path");
                    // Bei Ausschluessen mehr Kandidaten ziehen, da danach welche
                    // wegfallen - sonst faellt die Trefferzahl unter das Limit.
                    a.add(String.valueOf(excluded.isEmpty() ? limit : limit * 3));
                    Cursor cf = db.rawQuery(
                            "SELECT f." + FILE_COLS.replace(", ", ", f.") + ", "
                            // Treffer-Marker als Steuerzeichen (U+0002/U+0003), die
                            // in echtem Text nicht vorkommen - deutsche Buecher nutzen
                            // »...« als Anfuehrungszeichen, die duerfen NICHT als
                            // Fundstelle missdeutet werden. Die UI faerbt + rahmt sie.
                            // 24 Tokens: kurzes, um die Fundstelle zentriertes
                            // Fenster, damit die markierten Fundwoerter sicher in
                            // den sichtbaren Zeilen liegen (nicht unter maxLines).
                            + "snippet(content_fts, char(2), char(3), '…', 2, 24) "
                            + "FROM content_fts JOIN files f ON f.path = content_fts.path "
                            + "WHERE content_fts MATCH ?" + sc + fo + " LIMIT ?",
                            a.toArray(new String[0]));
                    int snipCol = cf.getColumnCount() - 1;
                    while (cf.moveToNext() && out.size() < limit) {
                        FileHit h = row(cf);
                        if (excluded.contains(h.path)) continue;
                        h.snippet = cf.getString(snipCol);
                        out.add(h);
                    }
                    cf.close();
                    return out;
                }
            }
            // Genauer Pfad (Phrase/Ausschluss/Innenstern/case-sensitiv): Text
            // pruefen. Die Kandidaten werden mit CAND_CAP gedeckelt, damit nie
            // die ganze Bibliothek in Java gescannt wird.
            final int CAND_CAP = Math.max(limit * 3, 1000);
            // Vorfilter so ENG wie moeglich: der exakte FTS-Ausdruck (Phrase/Wort)
            // gilt auch bei "Gross/klein beachten" als case-insensitiver Superset
            // -> dann nur die wenigen echten FTS-Treffer scannen (statt lockerem
            // Praefix ueber zehntausende Buecher). Sonst lockerer Praefix-Vorfilter.
            String fts = q.ftsExact();
            if (fts == null) fts = q.ftsMatch();
            List<String> args = new ArrayList<>();
            String where;
            if (fts != null) {
                args.add(fts);
                where = "content_fts MATCH ?"
                        + scopeClause(scope, args).replace("path", "f.path")
                        + folderClause(folderPrefix, args, "f.path");
            } else {
                // Kein praefix-sicherer Begriff (z.B. *rot* oder reine Ausschluss-
                // suche) -> begrenzter Scan ueber den Inhalt, der Matcher filtert.
                String sc = scopeClause(scope, args).replace("path", "f.path");
                String fo = folderClause(folderPrefix, args, "f.path");
                where = (sc.isEmpty() && fo.isEmpty()) ? "1=1" : ("1=1" + sc + fo);
            }
            Cursor c = db.rawQuery(
                    "SELECT f.path, content_fts.body FROM content_fts "
                    + "JOIN files f ON f.path = content_fts.path WHERE " + where
                    + " LIMIT " + CAND_CAP,
                    args.toArray(new String[0]));
            while (c.moveToNext() && out.size() < limit) {
                String path = c.getString(0);
                String body = c.getString(1);
                if (body == null) continue;
                if (q.matches(body, false, opts)) {
                    FileHit h = fileRowByPath(db, path);
                    if (h == null) continue;
                    h.snippet = q.snippet(body, 40);
                    out.add(h);
                }
            }
            c.close();
            return out;
        }

        if (area == AREA_FILE) {
            String frag = q.likeFragment();
            List<String> args = new ArrayList<>();
            StringBuilder where = new StringBuilder(frag != null ? "name LIKE ?" : "1=1");
            if (frag != null) args.add("%" + frag + "%");
            where.append(scopeClause(scope, args));
            where.append(folderClause(folderPrefix, args, "path"));
            Cursor c = db.rawQuery(
                    "SELECT " + FILE_COLS + " FROM files WHERE " + where + " ORDER BY mtime DESC",
                    args.toArray(new String[0]));
            while (c.moveToNext() && out.size() < limit) {
                FileHit h = row(c);
                if (q.matches(h.name, true, opts)) out.add(h);
            }
            c.close();
            return out;
        }

        // AREA_FOLDER: Ordner aus den Dateipfaden ableiten und matchen - gegen
        // den Ordnernamen UND den ganzen Ordnerpfad (Mathias' Wunsch: nicht
        // oder, sondern und). LIKE-Vorfilter auf path verengt die Kandidaten
        // (ein Namenstreffer steckt immer auch im Pfad).
        List<String> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("1=1");
        String frag = q.likeFragment();
        if (frag != null) { where.append(" AND path LIKE ?"); args.add("%" + frag + "%"); }
        where.append(scopeClause(scope, args));
        where.append(folderClause(folderPrefix, args, "path"));
        Cursor c = db.rawQuery(
                "SELECT DISTINCT path FROM files WHERE " + where, args.toArray(new String[0]));
        java.util.LinkedHashSet<String> folders = new java.util.LinkedHashSet<>();
        while (c.moveToNext()) {
            String p = c.getString(0);
            int idx = p.lastIndexOf('/');
            while (idx > 0) {
                String dir = p.substring(0, idx);
                if (!folders.add(dir)) break;   // Ordner + Vorfahren schon erfasst
                idx = dir.lastIndexOf('/');
            }
        }
        c.close();
        for (String dir : folders) {
            if (out.size() >= limit) break;
            String name = dir.substring(dir.lastIndexOf('/') + 1);
            if (name.isEmpty()) continue;
            // Treffer, wenn der Ordnername ODER der ganze Pfad die Suche erfuellt.
            if (q.matches(name, true, opts) || q.matches(dir, true, opts)) {
                FileHit h = new FileHit();
                h.path = dir;
                h.name = name;
                h.isFolder = true;
                out.add(h);
            }
        }
        return out;
    }

    /** Eine einzelne Datei-Zeile per Pfad holen (fuer Inhalts-Treffer). */
    private FileHit fileRowByPath(SQLiteDatabase db, String path) {
        Cursor c = db.rawQuery("SELECT " + FILE_COLS + " FROM files WHERE path = ?",
                new String[]{path});
        FileHit h = c.moveToFirst() ? row(c) : null;
        c.close();
        return h;
    }

    /** Ein Feld der erweiterten Suche als Suchanfrage parsen; null, wenn leer. */
    private static SearchQuery parseField(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        SearchQuery q = SearchQuery.parse(s);
        return q.isEmpty() ? null : q;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Erweiterte Suche: jedes nicht-leere Feld wird per AND kombiniert -
     *  Dateiname/Autor/Titel/Serie/Dateiart/Erstellt-Zeitraum/Geaendert-
     *  Zeitraum (Mathias' Wunsch nach kombinierbaren Kriterien). Die Textfelder
     *  nutzen jetzt die volle Suchsyntax (ganzes Wort, Stern, Phrase, Ausschluss)
     *  via SearchQuery; LIKE dient nur noch als grober Kandidaten-Vorfilter, der
     *  genaue Abgleich passiert mit matches() pro Feld. */
    public List<FileHit> advancedSearch(String name, String author, String title, String series,
                                         java.util.Set<String> exts, long createdFrom, long createdTo,
                                         long modifiedFrom, long modifiedTo, int limit,
                                         Collection<String> scope, String folderPrefix,
                                         SearchQuery.Options opts) {
        List<FileHit> out = new ArrayList<>();
        SearchQuery qName = parseField(name), qAuthor = parseField(author),
                    qTitle = parseField(title), qSeries = parseField(series);
        if (opts == null) opts = new SearchQuery.Options();

        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        // LIKE-Vorfilter nur mit dem laengsten positiven Literal des Feldes
        // (schnelle SQL-Vorauswahl); der genaue Abgleich folgt in Java.
        appendLikePrefilter(where, args, "name", qName);
        appendLikePrefilter(where, args, "author", qAuthor);
        appendLikePrefilter(where, args, "title", qTitle);
        appendLikePrefilter(where, args, "series", qSeries);
        if (exts != null && !exts.isEmpty()) {
            // Mehrere Dateiarten gleichzeitig auswaehlbar (Mathias' Wunsch) -
            // "ext IN (?,?,...)" statt der frueheren Einzelauswahl "ext = ?".
            where.append(" AND ext IN (");
            for (int i = 0; i < exts.size(); i++) where.append(i == 0 ? "?" : ",?");
            where.append(')');
            for (String e : exts) args.add(e.toLowerCase(java.util.Locale.ROOT));
        }
        if (createdFrom > 0) { where.append(" AND created >= ?"); args.add(String.valueOf(createdFrom)); }
        if (createdTo > 0) { where.append(" AND created <= ?"); args.add(String.valueOf(createdTo)); }
        if (modifiedFrom > 0) { where.append(" AND mtime >= ?"); args.add(String.valueOf(modifiedFrom)); }
        if (modifiedTo > 0) { where.append(" AND mtime <= ?"); args.add(String.valueOf(modifiedTo)); }
        where.append(scopeClause(scope, args));
        where.append(folderClause(folderPrefix, args, "path"));

        // Muss nach dem SQL-Vorfilter noch in Java gefiltert werden?
        boolean needJava = qName != null || qAuthor != null || qTitle != null || qSeries != null;
        int cap = limit < 0 ? Integer.MAX_VALUE : limit;
        // Bei Java-Filter mehr Kandidaten ziehen (es fallen welche weg); sonst exakt.
        String sqlLimit = needJava ? (limit < 0 ? "-1" : String.valueOf(Math.max(limit * 4, 2000)))
                                    : String.valueOf(limit);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + FILE_COLS + " FROM files WHERE " + where + " ORDER BY mtime DESC LIMIT " + sqlLimit,
                args.toArray(new String[0]));
        while (c.moveToNext() && out.size() < cap) {
            FileHit h = row(c);
            if (qName   != null && !qName.matches(nz(h.name),   true, opts)) continue;
            if (qAuthor != null && !qAuthor.matches(nz(h.author), true, opts)) continue;
            if (qTitle  != null && !qTitle.matches(nz(h.title),  true, opts)) continue;
            if (qSeries != null && !qSeries.matches(nz(h.series), true, opts)) continue;
            out.add(h);
        }
        c.close();
        return out;
    }

    /** Haengt fuer ein Feld einen LIKE-'%literal%'-Vorfilter an, falls die
     *  Anfrage ein positives Literal hat (sonst kein Vorfilter -> Java filtert). */
    private void appendLikePrefilter(StringBuilder where, List<String> args, String col, SearchQuery q) {
        if (q == null) return;
        String frag = q.likeFragment();
        if (frag != null) { where.append(" AND ").append(col).append(" LIKE ?"); args.add("%" + frag + "%"); }
    }

    /** Eine einzelne Datei aus dem Index entfernen (nach dem Löschen der Datei
     *  über das Datei-Kontextmenü). */
    public void forgetPath(String path) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("files", "path = ?", new String[]{path});
        db.delete("content_fts", "path = ?", new String[]{path});
    }

    /** Alle im Index vorkommenden Dateiendungen - fuer die Dateiart-Auswahl
     *  in der erweiterten Suche, nicht fest verdrahtet. */
    public List<String> distinctExts() {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT ext FROM files WHERE ext IS NOT NULL AND ext != '' ORDER BY ext", null);
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    private FileHit row(Cursor c) {
        FileHit h = new FileHit();
        h.path = c.getString(0);
        h.name = c.getString(1);
        h.ext = c.getString(2);
        h.size = c.getLong(3);
        h.mtime = c.getLong(4);
        h.created = c.getLong(5);
        h.drm = c.getInt(6) != 0;
        h.title = c.getString(7);
        h.author = c.getString(8);
        h.series = c.getString(9);
        h.seriesIndex = c.getFloat(10);
        return h;
    }

    /** FTS4-Sonderzeichen ("-, *, MATCH-Operatoren) grob entschaerfen, damit
     *  eine normale Wortsuche nicht an Syntaxfehlern scheitert. */
    private static String ftsEscape(String q) {
        StringBuilder sb = new StringBuilder();
        for (String w : q.split("\\s+")) {
            String clean = w.replaceAll("[^\\p{L}\\p{N}]", "");
            if (clean.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append('"').append(clean).append('"').append('*');
        }
        return sb.length() == 0 ? "\"" + q.replace("\"", "") + "\"" : sb.toString();
    }

    /** Eine mitgeschnittene Benachrichtigung ablegen. Keine Entdopplung ueber
     *  einen Schluessel wie bei EdgeTabs NotificationStore (Sucher braucht
     *  keine Antworten-/Loeschen-Aktionen, nur den Text durchsuchbar zu
     *  machen) - grobe Inhaltsgleichheit reicht, um Update-Spam zu vermeiden. */
    public void addNotification(String pkg, String appLabel, String title, String text, long posted,
                                String infoJson) {
        if ((title == null || title.isEmpty()) && (text == null || text.isEmpty())) return;
        SQLiteDatabase db = getWritableDatabase();
        Cursor dup = db.rawQuery(
                "SELECT _id FROM notifications WHERE pkg=? AND title=? AND text=? ORDER BY posted DESC LIMIT 1",
                new String[]{pkg, title == null ? "" : title, text == null ? "" : text});
        boolean exists = dup.moveToFirst();
        dup.close();
        if (exists) return;
        ContentValues v = new ContentValues();
        v.put("pkg", pkg);
        v.put("app_label", appLabel);
        v.put("title", title);
        v.put("text", text);
        v.put("posted", posted);
        v.put("info_json", infoJson);
        long id = db.insert("notifications", null, v);
        if (id >= 0) {
            ContentValues fv = new ContentValues();
            fv.put("nid", String.valueOf(id));
            fv.put("title", title);
            fv.put("body", text);
            db.insert("notif_fts", null, fv);
        }
    }

    public static class NotifHit {
        public String pkg, appLabel, title, text, snippet;
        public long posted;
        public String infoJson;   // vollstaendiger Abzug (kann null sein bei Alt-Eintraegen)
    }

    public List<NotifHit> searchNotifications(String query, int limit) {
        List<NotifHit> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        SQLiteDatabase db = getReadableDatabase();
        try {
            String ftsQuery = ftsEscape(query.trim());
            Cursor c = db.rawQuery(
                    "SELECT n.pkg, n.app_label, n.title, n.text, n.posted, "
                    + "snippet(notif_fts, '', '', '…', -1, 40), n.info_json "
                    + "FROM notif_fts JOIN notifications n ON n._id = CAST(notif_fts.nid AS INTEGER) "
                    + "WHERE notif_fts MATCH ? ORDER BY n.posted DESC LIMIT ?",
                    new String[]{ftsQuery, String.valueOf(limit)});
            while (c.moveToNext()) {
                NotifHit h = new NotifHit();
                h.pkg = c.getString(0);
                h.appLabel = c.getString(1);
                h.title = c.getString(2);
                h.text = c.getString(3);
                h.posted = c.getLong(4);
                h.snippet = c.getString(5);
                h.infoJson = c.getString(6);
                out.add(h);
            }
            c.close();
        } catch (Exception ignored) {
            // Ungueltige FTS-Syntax - einfach ohne Treffer weitermachen.
        }
        return out;
    }

    /** Alle Apps, von denen je eine Benachrichtigung mitgeschnitten wurde -
     *  fuer die Freigabe-Auswahl in den Einstellungen (gleiches Henne-Ei-
     *  Vorgehen wie EdgeTabs Posteingang-Quellen). */
    public List<String[]> distinctNotifPackages() {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pkg, MAX(app_label), COUNT(*) FROM notifications "
                + "GROUP BY pkg ORDER BY COUNT(*) DESC", null);
        while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
        c.close();
        return out;
    }

    /** Alte Benachrichtigungen wegwerfen (Standard: 90 Tage, siehe MainActivity). */
    public void pruneNotificationsOlderThan(long cutoffMillis) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT _id FROM notifications WHERE posted < ?",
                new String[]{String.valueOf(cutoffMillis)});
        List<String> ids = new ArrayList<>();
        while (c.moveToNext()) ids.add(c.getString(0));
        c.close();
        for (String id : ids) {
            db.delete("notifications", "_id=?", new String[]{id});
            db.delete("notif_fts", "nid=?", new String[]{id});
        }
    }

    public int indexedCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM files", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** Wie viele der bekannten Dateien schon Volltext haben (content_ok=1) -
     *  bei aktivierter Inhalts-Indizierung die eigentlich aussagekraeftige
     *  Fortschrittszahl, da indexedCount() nur "bekannt", nicht "mit
     *  Inhalt erfasst" zaehlt (Mathias' Wunsch nach sichtbarem Fortschritt -
     *  gerade bei einem grossen Nachhol-Durchlauf bewegt sich sonst nur
     *  diese Zahl, nicht die Gesamtzahl). Nur unter den Dateiarten gezaehlt,
     *  fuer die ueberhaupt Inhalt extrahiert werden koennte (siehe
     *  SearchIndexer.isSupported), sonst waere die "Gesamtzahl" durch
     *  Fotos/Videos/APKs etc. kuenstlich aufgeblaeht. */
    public int contentIndexedCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM files WHERE content_ok=1", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int contentEligibleCount() {
        String[] exts = SearchIndexer.SUPPORTED_EXTS;
        String placeholders = String.join(",", java.util.Collections.nCopies(exts.length, "?"));
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM files WHERE ext IN (" + placeholders + ")", exts);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** Datei-Index leeren (alle indizierten Dateien + Volltext). Der naechste
     *  Indizierlauf baut ihn neu auf. */
    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("files", null, null);
        db.delete("content_fts", null, null);
    }

    /** Alle mitgeschnittenen Benachrichtigungen (inkl. Volltext) loeschen. */
    public void clearNotifications() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("notifications", null, null);
        db.delete("notif_fts", null, null);
    }
}

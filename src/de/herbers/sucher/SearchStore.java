package de.herbers.sucher;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Eigener Datei-Volltext-Index (Name, Ort, Aenderungszeit + extrahierter
 * Inhalt) - wie NotificationStore, nur mit einer FTS4-Volltexttabelle statt
 * einer normalen. SQLites FTS4 ist im Android-System eingebaut, keine
 * Bibliothek noetig (anders als bei PDF/altem Office, siehe FileExtractors).
 */
public class SearchStore extends SQLiteOpenHelper {

    private static final String DB = "edgetab_search.db";
    private static final int VERSION = 3;
    private static SearchStore instance;

    public static synchronized SearchStore get(Context ctx) {
        if (instance == null) instance = new SearchStore(ctx.getApplicationContext());
        return instance;
    }

    private SearchStore(Context ctx) { super(ctx, DB, null, VERSION); }

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
            "  posted INTEGER" +
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
    }

    /** Metadaten + (falls vorhanden) Inhalt einer Datei ablegen/aktualisieren. */
    public void upsert(String path, String name, String ext, long size, long mtime, long created,
                        String content, boolean drm, String title, String author, String series, float seriesIndex) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("content_fts", "path=?", new String[]{path});
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

    /** Bekannte Aenderungszeit einer bereits indizierten Datei, oder -1. */
    public long knownMtime(String path) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT mtime FROM files WHERE path=?", new String[]{path});
        long m = c.moveToFirst() ? c.getLong(0) : -1;
        c.close();
        return m;
    }

    /** Alle Eintraege zu Pfaden entfernen, die beim letzten Durchlauf nicht
     *  mehr angetroffen wurden (geloeschte/verschobene Dateien) - alles
     *  unterhalb eines der gerade durchsuchten Wurzelordner, aelter als
     *  runStart. */
    public void pruneStale(String rootPrefix, long runStart) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT path FROM files WHERE path LIKE ? AND indexed_at < ?",
                new String[]{rootPrefix + "%", String.valueOf(runStart)});
        List<String> stale = new ArrayList<>();
        while (c.moveToNext()) stale.add(c.getString(0));
        c.close();
        for (String p : stale) {
            db.delete("files", "path=?", new String[]{p});
            db.delete("content_fts", "path=?", new String[]{p});
        }
    }

    public static class FileHit {
        public String path, name, ext, snippet, title, author, series;
        public long size, mtime, created;
        public float seriesIndex;
        public boolean drm;
    }

    private static final String FILE_COLS =
            "path, name, ext, size, mtime, created, drm, title, author, series, series_index";

    /** Volltext- und Namenssuche kombiniert: FTS-Treffer (Inhalt+Titel) plus
     *  Dateien, deren Name/Titel/Autor/Serie passt, auch ohne indizierten
     *  Inhalt (z.B. Bilder, Comics ohne ComicInfo.xml, Dateien ohne
     *  unterstuetztes Format). */
    public List<FileHit> search(String query, int limit) {
        List<FileHit> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        String q = query.trim();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        SQLiteDatabase db = getReadableDatabase();

        // 1) Volltext (Titel+Inhalt), mit Fundstellen-Schnipsel.
        try {
            String ftsQuery = ftsEscape(q);
            Cursor c = db.rawQuery(
                    "SELECT f." + FILE_COLS.replace(", ", ", f.") + ", "
                    + "snippet(content_fts, '', '', '…', -1, 40) "
                    + "FROM content_fts JOIN files f ON f.path = content_fts.path "
                    + "WHERE content_fts MATCH ? LIMIT ?",
                    new String[]{ftsQuery, String.valueOf(limit)});
            while (c.moveToNext()) {
                FileHit h = row(c);
                h.snippet = c.getString(11);
                if (seen.add(h.path)) out.add(h);
            }
            c.close();
        } catch (Exception ignored) {
            // Ungueltige FTS-Syntax (z.B. einzelnes Sonderzeichen) - einfach
            // ohne Volltext-Treffer weitermachen, Namenssuche greift unten.
        }

        // 2) Dateiname/Titel/Autor/Serie passt (LIKE), unabhaengig von Volltext.
        String like = "%" + q + "%";
        Cursor c2 = db.rawQuery(
                "SELECT " + FILE_COLS + " FROM files "
                + "WHERE name LIKE ? OR title LIKE ? OR author LIKE ? OR series LIKE ? "
                + "ORDER BY mtime DESC LIMIT ?",
                new String[]{like, like, like, like, String.valueOf(limit)});
        while (c2.moveToNext()) {
            FileHit h = row(c2);
            if (seen.add(h.path)) out.add(h);
        }
        c2.close();
        return out;
    }

    /** Erweiterte Suche: jedes nicht-leere Feld wird per AND kombiniert -
     *  Dateiname/Autor/Titel/Serie/Dateiart/Erstellt-Zeitraum/Geaendert-
     *  Zeitraum (Mathias' Wunsch nach kombinierbaren Kriterien). */
    public List<FileHit> advancedSearch(String name, String author, String title, String series,
                                         java.util.Set<String> exts, long createdFrom, long createdTo,
                                         long modifiedFrom, long modifiedTo, int limit) {
        List<FileHit> out = new ArrayList<>();
        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        if (name != null && !name.trim().isEmpty()) { where.append(" AND name LIKE ?"); args.add("%" + name.trim() + "%"); }
        if (author != null && !author.trim().isEmpty()) { where.append(" AND author LIKE ?"); args.add("%" + author.trim() + "%"); }
        if (title != null && !title.trim().isEmpty()) { where.append(" AND title LIKE ?"); args.add("%" + title.trim() + "%"); }
        if (series != null && !series.trim().isEmpty()) { where.append(" AND series LIKE ?"); args.add("%" + series.trim() + "%"); }
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
        args.add(String.valueOf(limit));
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + FILE_COLS + " FROM files WHERE " + where + " ORDER BY mtime DESC LIMIT ?",
                args.toArray(new String[0]));
        while (c.moveToNext()) out.add(row(c));
        c.close();
        return out;
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
    public void addNotification(String pkg, String appLabel, String title, String text, long posted) {
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
    }

    public List<NotifHit> searchNotifications(String query, int limit) {
        List<NotifHit> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        SQLiteDatabase db = getReadableDatabase();
        try {
            String ftsQuery = ftsEscape(query.trim());
            Cursor c = db.rawQuery(
                    "SELECT n.pkg, n.app_label, n.title, n.text, n.posted, "
                    + "snippet(notif_fts, '', '', '…', -1, 40) "
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

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("files", null, null);
        db.delete("content_fts", null, null);
    }
}

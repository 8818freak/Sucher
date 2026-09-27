package de.herbers.sucher;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Sichert/liest Einstellungen UND den eigentlichen Suchindex (die SQLite-
 * Datenbank von SearchStore) als eine einzige Zip-Datei - anders als bei
 * EdgeTab/BBMe Ping reicht ein reiner Text-Export hier nicht, weil der Index
 * selbst (Volltexte von tausenden Dateien) das eigentlich Wertvolle ist, das
 * eine Neuinstallation sonst komplett neu einlesen muesste (bei Mathias'
 * Bibliothek: Stunden). Kein externes Zip-Format/-Bibliothek noetig -
 * java.util.zip ist Teil des JDK.
 */
final class Backup {

    private static final String ENTRY_SETTINGS = "settings.txt";
    private static final String ENTRY_DB = "index.db";

    private Backup() {}

    static void exportZip(Context ctx, OutputStream out) throws IOException {
        // Checkpoint+schliessen VOR dem Kopieren, damit die Datei-Kopie einen
        // konsistenten, vollstaendigen Stand hat (siehe SearchStore).
        SearchStore.closeForBackup();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(ENTRY_SETTINGS));
            zos.write(Settings.exportText(ctx).getBytes("UTF-8"));
            zos.closeEntry();

            File db = SearchStore.dbFile(ctx);
            if (db.exists()) {
                // STORED statt DEFLATED: die Datenbank ist oft schon
                // mehrere hundert MB gross (Volltexte einer grossen
                // Bibliothek) - komprimieren würde die Sicherung von
                // Sekunden auf Minuten verlangsamen, bei kaum kleinerem
                // Ergebnis (SQLite-Seiten komprimieren ohnehin schlecht).
                // STORED verlangt Groesse+CRC32 vorab, deshalb der
                // zusaetzliche Lesedurchlauf fuer die Pruefsumme.
                ZipEntry entry = new ZipEntry(ENTRY_DB);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(db.length());
                entry.setCompressedSize(db.length());
                entry.setCrc(crc32Of(db));
                zos.putNextEntry(entry);
                try (FileInputStream in = new FileInputStream(db)) { copy(in, zos); }
                zos.closeEntry();
            }
        }
    }

    /** Ersetzt Einstellungen UND Index-Datenbank durch den Inhalt einer
     *  Sicherung. Liefert false bei erkennbar falschem/beschaedigtem Format
     *  (fehlende settings.txt), ohne etwas zu aendern - eine fehlende
     *  index.db dagegen ist kein Fehler (aeltere Sicherung ohne DB-Teil),
     *  dann bleibt der bisherige Index unangetastet. */
    static boolean importZip(Context ctx, InputStream in) throws IOException {
        String settingsText = null;
        File dbTarget = SearchStore.dbFile(ctx);
        File tmpDb = new File(dbTarget.getParentFile(), dbTarget.getName() + ".importing");
        boolean gotDb = false;
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (ENTRY_SETTINGS.equals(e.getName())) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    copy(zis, bos);
                    settingsText = bos.toString("UTF-8");
                } else if (ENTRY_DB.equals(e.getName())) {
                    dbTarget.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(tmpDb)) { copy(zis, fos); }
                    gotDb = true;
                }
            }
        }
        if (settingsText == null) {
            if (tmpDb.exists()) tmpDb.delete();
            return false;
        }
        boolean ok = Settings.importText(ctx, settingsText);
        if (!ok) {
            if (tmpDb.exists()) tmpDb.delete();
            return false;
        }
        if (gotDb) {
            // Offene Instanz UND alte -wal/-shm-Reste weg, bevor die neue
            // Datei an ihre Stelle tritt (siehe SearchStore.closeForBackup).
            SearchStore.closeForBackup();
            new File(dbTarget.getParentFile(), dbTarget.getName() + "-wal").delete();
            new File(dbTarget.getParentFile(), dbTarget.getName() + "-shm").delete();
            dbTarget.delete();
            tmpDb.renameTo(dbTarget);
        }
        return true;
    }

    private static long crc32Of(File f) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }
}

package de.herbers.sucher;

import android.app.Application;

import de.herbers.common.DiagLog;
import de.herbers.common.Diagnostics;
import de.herbers.docextract.Thumbnails;

/**
 * App-Einstieg fuer alle Prozesse (UI, Benachrichtigungs-Listener, Index-Job).
 * Richtet die gemeinsame Diagnose ein: logcat-Tag "SucherDiag" und einen
 * Absturz-Logger, der unbehandelte Ausnahmen ins Diagnose-Protokoll schreibt.
 * Legt zudem den Ablageort der Vorschaubilder fest (die Extraktoren/Thumbnails
 * kommen aus der gemeinsamen Bibliothek de.herbers.docextract und sind vom
 * "dauerhaft speichern"-Schalter entkoppelt - hier wird er verdrahtet).
 */
public class SucherApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DiagLog.setTag("SucherDiag");
        Diagnostics.installCrashLogger(this);
        applyThumbStorage(this);
    }

    /** Ablageort der Vorschaubilder gemaess Einstellung setzen: AN (Standard) =
     *  app-interner Dateien-Ordner (ueberlebt "Cache leeren"/SD Maid), AUS =
     *  Cache-Ordner (raeumbar). Auch beim Umschalten in den Einstellungen
     *  aufrufen. */
    static void applyThumbStorage(android.content.Context ctx) {
        Thumbnails.setStorageDir(Settings.thumbsPersistent(ctx)
                ? ctx.getFilesDir() : ctx.getCacheDir());
    }
}

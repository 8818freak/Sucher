package de.herbers.sucher;

import android.app.Application;

import de.herbers.common.DiagLog;
import de.herbers.common.Diagnostics;

/**
 * App-Einstieg fuer alle Prozesse (UI, Benachrichtigungs-Listener, Index-Job).
 * Richtet die gemeinsame Diagnose ein: logcat-Tag "SucherDiag" und einen
 * Absturz-Logger, der unbehandelte Ausnahmen ins Diagnose-Protokoll schreibt.
 */
public class SucherApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DiagLog.setTag("SucherDiag");
        Diagnostics.installCrashLogger(this);
    }
}

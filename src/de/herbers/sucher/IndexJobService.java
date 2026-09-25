package de.herbers.sucher;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Regelmaessiger Hintergrund-Abgleich des Index, ueber Androids JobScheduler
 * statt eines echten Dauer-Dienstes wie EdgeTabs (genau der Unterschied, den
 * wir beim Herauslösen aus EdgeTab bewusst vermeiden wollten - JobScheduler
 * laeuft trotzdem auch wenn Sucher geschlossen ist, nur batterieschonender
 * und vom System zeitlich gesteuert statt dauerhaft im Vordergrund).
 *
 * Deckt nebenbei auch "abgebrochener Lauf soll fortgesetzt werden" ab, ohne
 * dass es dafuer einen eigenen Mechanismus braucht: SearchIndexer.walk()
 * schreibt jede Datei SOFORT beim Durchlaufen in die Datenbank und ueberspringt
 * beim naechsten Lauf per mtime-Vergleich alles bereits Erfasste (siehe dort)
 * - ein unterbrochener Lauf hinterlaesst also nie einen kaputten Zustand,
 * sondern nur eine Teilmenge, die der naechste (hier: automatische) Lauf
 * einfach fertig macht.
 */
public class IndexJobService extends JobService {

    private static final int JOB_ID = 4711;
    // Kompromiss zwischen "Index bleibt einigermassen frisch" und Akku -
    // Android haelt sich wegen Doze/Batterie-Optimierung ohnehin nicht
    // sklavisch an das Intervall, das ist hier nur eine grobe Vorgabe.
    private static final long INTERVAL_MS = 4L * 60 * 60 * 1000;

    /** Bei jedem App-Start neu anmelden (statt setPersisted+Boot-Empfaenger -
     *  eine zusaetzliche Berechtigung nur dafuer waere unverhaeltnismaessig).
     *  schedule() mit derselben JOB_ID ersetzt eine evtl. vorhandene
     *  Anmeldung einfach, mehrfaches Aufrufen ist unproblematisch. */
    static void ensureScheduled(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!Settings.autoReindex(ctx) || !MainActivity.hasStoragePermission()
                || Settings.searchFolders(ctx).isEmpty()) {
            js.cancel(JOB_ID);
            return;
        }
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, IndexJobService.class))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(false)
                .build();
        js.schedule(info);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!Settings.autoReindex(this) || !MainActivity.hasStoragePermission()
                || Settings.searchFolders(this).isEmpty()) {
            return false;
        }
        if (SearchIndexer.isRunning()) {
            // Laeuft schon (z.B. manuell angestossen, oder ein vorheriger
            // Job-Lauf klingt gerade erst aus) - nichts zu tun anmelden,
            // statt "true" zurueckzugeben und nie jobFinished() aufzurufen.
            // Genau das fuehrte vorher dazu, dass das System den Job nach
            // Ablauf des Zeitfensters immer wieder zwangsbeenden musste
            // (siehe SearchIndexer.requestStop()-Kommentar).
            return false;
        }
        PdfExtractorHelper.init(getApplicationContext());
        SearchIndexer.start(this, () -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Das System braucht die Zeit anderweitig - den laufenden Scan
        // kooperativ zum baldigen Aufhoeren bewegen (er hinterlaesst dank
        // des mtime-Vergleichs sowieso nie einen kaputten Zwischenstand),
        // statt ihn wie zuvor als unbeaufsichtigten Thread weiterlaufen zu
        // lassen. "false" zurueckgeben: kein sofortiger Neustart-Versuch -
        // der naechste periodische Lauf (oder der naechste App-Start)
        // reicht, ein knapper Retry-Loop wuerde nur erneut ins selbe
        // Zeitfenster-Problem laufen.
        SearchIndexer.requestStop();
        return false;
    }
}

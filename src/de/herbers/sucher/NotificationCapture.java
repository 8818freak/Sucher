package de.herbers.sucher;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import de.herbers.common.Notifications;

/**
 * Schneidet Systembenachrichtigungen mit und legt Titel/Text im SearchStore
 * ab, damit sie durchsuchbar sind - deckt Chats/Mails/Messenger teilweise ab,
 * ohne an deren eigene (verschluesselte/gesperrte) Datenbanken zu muessen.
 * Gleicher Kern wie EdgeTabs NotificationCollector (de.herbers.common.
 * Notifications), hier aber nur zum Indizieren - keine Antworten-/Loeschen-
 * Aktionen noetig.
 *
 * Grenzen (wichtig, siehe MainActivity.notifPermissionHint): nur was
 * tatsaechlich als Benachrichtigung durchkam, waehrend Sucher lief und die
 * Berechtigung hatte - keine volle Chat-Historie, keine bereits gelesenen/
 * weggewischten alten Nachrichten, keine Gruppen-Sammelbenachrichtigungen
 * ("3 neue Nachrichten" ohne Einzelinhalt).
 */
public class NotificationCapture extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            store(sbn);
        } catch (Exception ignored) {}
    }

    private void store(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification n = sbn.getNotification();
        if (n == null) return;

        // Reine Gruppen-Zusammenfassung ueberspringen (kein Einzelinhalt).
        if (Notifications.isGroupSummary(n)) return;

        String[] tt = Notifications.titleAndText(n);
        String title = tt[0], text = tt[1];
        if (Notifications.isBlank(title, text)) return;

        String pkg = sbn.getPackageName();
        String label = Notifications.appLabel(this, pkg);
        String finalText = text;
        long postTime = sbn.getPostTime();
        // onNotificationPosted runs on the main thread; addNotification() hits
        // SQLite, which can block waiting for a connection while SearchIndexer
        // is mid-run (observed as ANRs: "Input dispatching timed out" with the
        // main thread stuck in SQLiteConnectionPool.waitForConnection).
        new Thread(() ->
            SearchStore.get(this).addNotification(pkg, label, title, finalText, postTime)
        ).start();
    }
}

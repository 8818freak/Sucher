package de.herbers.sucher;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Schneidet Systembenachrichtigungen mit und legt Titel/Text im SearchStore
 * ab, damit sie durchsuchbar sind - deckt Chats/Mails/Messenger teilweise ab,
 * ohne an deren eigene (verschluesselte/gesperrte) Datenbanken zu muessen.
 * Gleicher Mechanismus wie EdgeTabs NotificationCollector fuer die
 * Posteingang-Karte, hier aber nur zum Indizieren - keine Antworten-/
 * Loeschen-Aktionen noetig.
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
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle ex = n.extras;
        CharSequence titleCs = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigCs = ex == null ? null : ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        String title = titleCs == null ? null : titleCs.toString();
        String text = textCs == null ? null : textCs.toString();
        if (bigCs != null && (text == null || bigCs.length() > text.length())) text = bigCs.toString();
        if ((title == null || title.isEmpty()) && (text == null || text.isEmpty())) return;

        String pkg = sbn.getPackageName();
        SearchStore.get(this).addNotification(pkg, appLabel(pkg), title, text, sbn.getPostTime());
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Exception ignored) {}
        return pkg;
    }
}

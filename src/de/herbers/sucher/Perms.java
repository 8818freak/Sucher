package de.herbers.sucher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import de.herbers.common.PermReminder;

import java.util.ArrayList;
import java.util.List;

/** Eine Quelle fuer Suchers Berechtigungen - fuer die Erinnerung bei Verlust
 *  (PermReminder) UND den aufklappbaren, erklaerten Berechtigungs-Abschnitt. */
final class Perms {
    private Perms() {}

    static boolean storage(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }
    static boolean notif(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(c.getPackageName());
    }
    private static Intent appDetails(Context c) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + c.getPackageName()));
    }

    static List<PermReminder.Perm> list(Context ctx) {
        boolean con = ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        boolean cal = ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        Intent files = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        List<PermReminder.Perm> l = new ArrayList<>();
        l.add(new PermReminder.Perm("storage", "Alle Dateien", storage(ctx), files,
                "Um deine Dateien zu durchsuchen und zu indizieren – die Grundlage der App."));
        l.add(new PermReminder.Perm("notif", "Benachrichtigungszugriff", notif(ctx),
                new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                "Ab dem Erteilen werden eingehende Benachrichtigungen mitgeschnitten und "
                + "durchsuchbar. Schon erfasste bleiben auch nach Entzug der Berechtigung in "
                + "der Datenbank und weiter durchsuchbar (siehe „Erfasste Benachrichtigungen "
                + "löschen“). Optional."));
        l.add(new PermReminder.Perm("contacts", "Kontakte", con, appDetails(ctx),
                "Um Kontakte in die Suche einzubeziehen (optional)."));
        l.add(new PermReminder.Perm("calendar", "Termine", cal, appDetails(ctx),
                "Um Termine in die Suche einzubeziehen (optional)."));
        return l;
    }

    static void checkReminders(Context ctx) {
        PermReminder.check(ctx, "Sucher", list(ctx));
    }
}

package de.herbers.sucher;

import de.herbers.docextract.*;

import de.herbers.common.DiagLog;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Sucher: eigenstaendige App fuer Datei-Volltextsuche (Name UND, wo
 * unterstuetzt, Inhalt - siehe FileExtractors/SearchIndexer) plus Kontakte
 * und Termine in einem Feld. Angelehnt an das Karten-Muster von BlackBerrys
 * eigener Geraetesuche (com.blackberry.universalsearch,
 * activity_device_search.xml/view_base_result_card.xml: Kopfzeile je
 * Kategorie, ein paar Zeilen, bei mehr Treffern "Mehr anzeigen"), aber in
 * einer eigenen dunklen Optik statt BBs hellem Grau/Blau.
 *
 * Urspruenglich als "Suche"-Karte in EdgeTab gebaut, auf Mathias' Wunsch
 * (2026-09-24) zu einer eigenen App herausgeloest - der Aufwand
 * (MANAGE_EXTERNAL_STORAGE, POI/PDFBox) und der Anwendungsfall (direkt
 * aufrufen statt erst die Randleiste aufzuziehen) passten nicht zu EdgeTabs
 * schlankem Dauer-Dienst.
 */
public class MainActivity extends Activity {

    private static final Handler DEBOUNCE = new Handler(Looper.getMainLooper());
    private static Runnable pendingSearch;

    private static final int PAGE = 5;
    private static final java.util.Set<String> EXPANDED = new java.util.HashSet<>();

    // Ueberlebt einen Programmwechsel (App zu, wieder zurueck) - statische
    // Felder wie EXPANDED oben, nicht an die Activity-Instanz gebunden.
    private static String lastQuery = "";
    private static String lastOpenedPath = null;
    // Je Suche abwaehlbare Kategorien (Dateien/Kontakte/Termine/Nachrichten) -
    // bewusst nicht in Settings gespeichert, nur fuer die laufende Sitzung
    // (Mathias: "Quellen fuer einzelne Suchen ausschliessen").
    private static final java.util.Set<String> EXCLUDED_CATS = new java.util.HashSet<>();

    // Ergebnis-Eingrenzung ("in diesen Treffern weitersuchen", Mathias'
    // Wunsch): null = keine Einschraenkung. Bewusst unabhaengig von
    // lastQuery/den erweiterten Feldern - genau der Sinn ist ja, denselben
    // Treffer-Ausschnitt mit EINER ANDEREN Suche/anderen Parametern erneut
    // zu durchsuchen. Static wie lastQuery, ueberlebt darum auch die
    // Rueckkehr aus der Vorschau.
    private static List<String> scopePaths = null;

    // Einschraenkung auf EINEN Ordner samt Unterordnern (Mathias' Wunsch, "in
    // einem bestimmten Ordner und seinen Unterordnern suchen") - als
    // Pfad-Praefix. Static wie scopePaths, ueberlebt die Rueckkehr aus der
    // Vorschau. null = keine Ordner-Einschraenkung.
    private static String scopeFolder = null;

    // Suchverlauf auf-/zugeklappt (Aufklappmenue mit drehendem Pfeil).
    private static boolean historyOpen = false;

    // Ordner-Auswahl fuer den Suchbereich (feature "in Ordner suchen") -
    // eigener kleiner Browser, unabhaengig vom Ordner-Browser der Einstellungen.
    private boolean scopeFolderPicking = false;
    private String scopeBrowsePath = null;

    // Datei-Unterkategorien fuer eigene Ergebnis-Karten (Mathias' Wunsch:
    // Videos/Bilder/Musik als eigene Kategorien). Alles andere ist "Dateien"
    // (Dokumente). Alle Dateien liegen ohnehin im Index (auch Medien - nur
    // ueber den Namen), die Aufteilung passiert also allein bei der Anzeige.
    private static final java.util.Set<String> IMAGE_EXTS = new java.util.HashSet<>(java.util.Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff", "svg", "ico", "jfif"));
    private static final java.util.Set<String> VIDEO_EXTS = new java.util.HashSet<>(java.util.Arrays.asList(
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "3gpp", "flv", "wmv", "mpg", "mpeg", "ts", "m2ts"));
    private static final java.util.Set<String> AUDIO_EXTS = new java.util.HashSet<>(java.util.Arrays.asList(
            "mp3", "flac", "ogg", "oga", "m4a", "aac", "wav", "wma", "opus", "aiff", "aif", "mid", "midi", "amr"));

    // Vorschaubilder bei Bedarf im Hintergrund nacherzeugen (siehe
    // rowThumbOrIcon) - ein einzelner Thread reicht, und je Pfad nur ein
    // Versuch pro Sitzung, damit wiederholte rebuild()s nicht dieselbe (evtl.
    // erfolglose) Erzeugung immer wieder anstossen.
    // Mehrere Threads, damit die Vorschaubilder sichtbarer Treffer zügig
    // erscheinen (ein einzelner Thread war bei Bildordnern zu langsam - es
    // blieben lange nur die Datei-Symbole stehen).
    private static final java.util.concurrent.Executor THUMB_EXEC =
            java.util.concurrent.Executors.newFixedThreadPool(3);
    // Ein einzelner Thread fuer die Suche selbst (Datenabruf), damit eine
    // haeufige Volltext-Anfrage den UI-Thread nicht mehr blockiert (ANR).
    private static final java.util.concurrent.ExecutorService SEARCH_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    // Obergrenze fuer die Sofort-Suche (Suche-beim-Tippen): ein sehr haeufiges
    // Wort wie "sex" trifft als Praefix ("sex"*) zehntausende Dateien; die alle
    // samt Schnipsel zu holen dauert selbst im Hintergrund ewig. Erste Treffer
    // schnell zeigen, Rest per Verfeinern/erweiterter Suche. (Die erweiterte
    // Suche selbst filtert ueber Metadaten, nicht Volltext, und bleibt schnell.)
    private static final int SEARCH_LIMIT = 500;
    private static final java.util.Set<String> THUMB_TRIED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    // Wie viele Datei-Zeilen je Kategorie aktuell gezeichnet werden (waechst per
    // "Mehr anzeigen" in Schritten). Kein fester Deckel mehr - der Nutzer
    // entscheidet, wie viele er sehen will (Mathias: Begrenzungen nerven).
    private static final java.util.Map<String, Integer> FILE_SHOWN = new java.util.HashMap<>();
    private static final int FILE_SHOW_STEP = 50;

    private static String fileCategory(String ext) {
        if (ext == null) return "files";
        String e = ext.toLowerCase(java.util.Locale.ROOT);
        if (IMAGE_EXTS.contains(e)) return "images";
        if (VIDEO_EXTS.contains(e)) return "videos";
        if (AUDIO_EXTS.contains(e)) return "music";
        return "files";
    }

    private static final int REQ_PIM = 501;
    private static final int REQ_BACKUP_EXPORT = 502;
    private static final int REQ_BACKUP_IMPORT = 503;

    /** Wo Datensicherungen gesammelt werden, bevor sie auf einen eigenen
     *  Server geladen werden - als Startordner vorschlagen, wenn der Datei-
     *  Picker das unterstuetzt (rein optional, faellt still zurueck). */
    private static final Uri DASIS_FOLDER =
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADaSis");

    private boolean showSettings = false;
    private boolean folderPicking = false;
    private boolean notifSourcesOpen = false;
    private boolean advancedOpen = false;
    private String browsePath = null;
    private boolean browseWantContent = false;
    private float fs = 1f;

    // Erweiterte-Suche-Eingaben - ueberleben einen rebuild() (Nutzer tippt in
    // mehrere Felder, bevor er "Suchen" antippt), aber nicht die Activity
    // selbst (anders als lastQuery/lastOpenedPath - eine ausgefuellte
    // erweiterte Suche muss nicht ueber einen Programmwechsel ueberleben).
    private String advName = "", advAuthor = "", advTitle = "", advSeries = "";
    // Mehrere Dateiarten gleichzeitig waehlbar (Mathias' Wunsch) - vorher
    // eine einzelne String advExt.
    private final java.util.Set<String> advExts = new java.util.HashSet<>();
    private long advCreatedFrom = 0, advCreatedTo = 0, advModifiedFrom = 0, advModifiedTo = 0;
    // Eingeklappt bis der Pfeil angetippt wird - vorher eine einzeilige,
    // wischbare Liste, die bei vielen Dateiarten unhandlich war (Mathias:
    // "eine mehrzeilige Liste, die man nach Auswahl wieder schliessen kann").
    // Die Auswahl selbst (advExts) bleibt unabhaengig vom Auf-/Zugeklapptsein
    // erhalten, bis "Zuruecksetzen" angetippt wird.
    private boolean extPickerOpen = false;

    // Ob gerade Ergebnisse der ERWEITERTEN Suche angezeigt werden (statt der
    // einfachen) - static wie lastQuery, damit es auch nach Rueckkehr aus der
    // Vorschau (eigene Activity) bekannt ist, welche der beiden Ansichten neu
    // ausgefuehrt werden muss (Mathias: "schliesse ich die Vorschau, leert
    // sich die Suche auch").
    private static boolean lastSearchWasAdvanced = false;

    private LinearLayout root;
    private ScrollView scroll;
    private LinearLayout advancedResults;
    private int advancedD;
    // Scroll-Position ueber einen rebuild() hinweg erhalten - sonst sprang
    // die Ansicht bei JEDER Kleinigkeit (Dateiart antippen, Datum waehlen...)
    // zurueck nach oben (Mathias: "das ist laestig").
    private int pendingScrollY = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int d = dp(1);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#1C1C1E"));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(14 * d, 14 * d, 14 * d, 14 * d);
        scroll.addView(root);
        setContentView(scroll);

        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Nach Rueckkehr vom "Alle Dateien"-Berechtigungsbildschirm neu
        // aufbauen, damit der Hinweis verschwindet ohne dass man selbst
        // zurueck navigieren muss.
        rebuild();
        IndexJobService.ensureScheduled(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopIndexTicker();
    }

    // Aktualisiert die Fortschrittsanzeige in den Einstellungen waehrend
    // eines laufenden Indizierlaufs von selbst weiter (nicht nur bei einer
    // Nutzeraktion) - sonst wirkte ein aktiver, aber langsamer Lauf wie
    // Stillstand (Mathias: "das aendert nichts an den Anzeigen"). Nur aktiv,
    // waehrend die Einstellungen sichtbar UND tatsaechlich indiziert wird -
    // pausiert/stoppt sich also von selbst, sobald beides nicht mehr gilt.
    // rebuild() selbst ruft startIndexTicker() am Ende erneut auf (statt der
    // Tick-Callback sich selbst nachzuplanen) - so heilt sich die Kette bei
    // JEDEM rebuild() von selbst, auch nach einem Wechsel Suche<->
    // Einstellungen zwischendurch, der sie sonst lautlos abriss (beobachtet:
    // Anzeige blieb bei "402" stehen, obwohl im Hintergrund laengst 3075
    // Dateien durch waren).
    private final Handler indexTicker = new Handler(Looper.getMainLooper());
    private final Runnable indexTick = this::rebuild;

    private void startIndexTicker() {
        indexTicker.removeCallbacks(indexTick);
        if (showSettings && SearchIndexer.isRunning()) indexTicker.postDelayed(indexTick, 1000);
    }

    private void stopIndexTicker() {
        indexTicker.removeCallbacks(indexTick);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---------- Kopfzeile + Umschaltung Suche/Einstellungen ----------

    private void rebuild() {
        if (scroll != null) pendingScrollY = scroll.getScrollY();
        root.removeAllViews();
        int d = dp(1);
        fs = Settings.fontScale(this);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(showSettings ? "Einstellungen" : "Sucher");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20 * fs);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        TextView gear = new TextView(this);
        gear.setText(showSettings ? "✕" : "⚙");
        gear.setTextColor(Color.parseColor("#B0B0B0"));
        gear.setTextSize(20 * fs);
        gear.setPadding(10 * d, 6 * d, 10 * d, 6 * d);
        gear.setOnClickListener(v -> { showSettings = !showSettings; folderPicking = false; rebuild(); });
        head.addView(gear);
        root.addView(head);

        if (showSettings) buildSettings(root, d);
        else buildSearch(root, d);

        if (pendingScrollY >= 0) {
            final int y = pendingScrollY;
            pendingScrollY = -1;
            scroll.post(() -> scroll.scrollTo(0, y));
        }
        startIndexTicker();
    }

    // ---------- Suche ----------

    private void buildSearch(LinearLayout root, int d) {
        EditText field = new EditText(this);
        field.setHint("Dateien, Kontakte, Termine durchsuchen…");
        field.setHintTextColor(Color.parseColor("#8899AA"));
        field.setTextColor(Color.WHITE);
        field.setTextSize(15 * fs);
        field.setSingleLine(true);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(Color.parseColor("#2C2C2E"));
        fieldBg.setCornerRadius(20 * d);
        field.setBackground(fieldBg);
        // Rechts Platz fuer den ✕-Loeschknopf lassen.
        field.setPadding(16 * d, 10 * d, 44 * d, 10 * d);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        // Feld + ✕-Loeschknopf (Mathias' Wunsch) in einem FrameLayout - der
        // Knopf schwebt rechts im Feld und leert es (der TextWatcher blendet
        // dann Verlauf ein und leert die Trefferliste ganz normal).
        android.widget.FrameLayout fieldWrap = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams fwlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fwlp.topMargin = 10 * d;
        fieldWrap.setLayoutParams(fwlp);
        fieldWrap.addView(field);
        TextView clearBtn = new TextView(this);
        clearBtn.setText("✕");
        clearBtn.setTextColor(Color.parseColor("#8899AA"));
        clearBtn.setTextSize(15 * fs);
        clearBtn.setPadding(12 * d, 10 * d, 14 * d, 10 * d);
        android.widget.FrameLayout.LayoutParams clp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.END);
        clearBtn.setLayoutParams(clp);
        clearBtn.setVisibility(field.getText().length() == 0 ? View.GONE : View.VISIBLE);
        clearBtn.setOnClickListener(v -> field.setText(""));
        fieldWrap.addView(clearBtn);
        root.addView(fieldWrap);

        // Steuerzeile: in einem bestimmten Ordner suchen + Suche zuruecksetzen.
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, 8 * d, 0, 0);
        TextView folderBtn = new TextView(this);
        folderBtn.setText(scopeFolder == null ? "📁 In Ordner suchen…" : "📁 Ordner ändern…");
        folderBtn.setTextColor(Color.parseColor("#2E9BE6"));
        folderBtn.setTextSize(13 * fs);
        folderBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        folderBtn.setOnClickListener(v -> {
            scopeFolderPicking = !scopeFolderPicking;
            if (scopeFolderPicking && scopeBrowsePath == null) {
                java.util.List<String> f = new ArrayList<>(Settings.searchFolders(this));
                scopeBrowsePath = f.isEmpty() ? Environment.getExternalStorageDirectory().getAbsolutePath()
                        : java.util.Collections.min(f);
            }
            rebuild();
        });
        controls.addView(folderBtn);
        TextView newSearch = new TextView(this);
        newSearch.setText("Neue Suche");
        newSearch.setTextColor(Color.parseColor("#2E9BE6"));
        newSearch.setTextSize(13 * fs);
        newSearch.setPadding(10 * d, 6 * d, 4 * d, 6 * d);
        newSearch.setOnClickListener(v -> resetSearch());
        controls.addView(newSearch);
        root.addView(controls);

        if (scopeFolderPicking) root.addView(scopeFolderBrowser(d));

        // Suchverlauf - nur sichtbar, solange das Feld leer ist (kein
        // rebuild() noetig zum Ein-/Ausblenden waehrend der Eingabe, siehe
        // TextWatcher unten). Als Aufklappmenue mit drehendem Pfeil (Mathias'
        // Wunsch). Aufgenommen wird ein Begriff, wenn er ueber die Sucher-Taste
        // bestaetigt wird ODER wenn ein Treffer geoeffnet/vorangezeigt wird -
        // jede 250ms-Tipp-Pause mitzuschreiben wuerde den Verlauf mit
        // Zwischenstaenden ("b", "bu", "buc"...) zumuellen.
        LinearLayout historyBox = buildHistoryBox(field, d);
        root.addView(historyBox);

        root.addView(categoryChips(d));

        if (scopeFolder != null) root.addView(folderScopeBanner(d));
        if (scopePaths != null) root.addView(scopeBanner(d));

        TextView advToggle = new TextView(this);
        advToggle.setText(advancedOpen ? "▾ Erweiterte Suche" : "▸ Erweiterte Suche");
        advToggle.setTextColor(Color.parseColor("#2E9BE6"));
        advToggle.setTextSize(15 * fs); // groesser - war zu klein zum bequemen Antippen
        advToggle.setPadding(0, 14 * d, 0, 6 * d);
        advToggle.setOnClickListener(v -> { advancedOpen = !advancedOpen; rebuild(); });
        root.addView(advToggle);

        if (advancedOpen) root.addView(advancedSearchPanel(d));

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, 10 * d, 0, 0);
        root.addView(results);
        advancedResults = results;
        advancedD = d;

        if (!hasStoragePermission()) results.addView(storagePermissionHint(d));
        if (!hasPimPermission()) results.addView(pimPermissionHint(d));
        if (!hasNotifPermission()) results.addView(notifPermissionHint(d));

        if (hasStoragePermission() && Settings.searchFolders(this).isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Noch keine Ordner zum Durchsuchen gewählt – oben ⚙.");
            hint.setTextColor(Color.parseColor("#8899AA"));
            hint.setTextSize(13 * fs);
            hint.setPadding(0, 8 * d, 0, 0);
            results.addView(hint);
        }

        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                String q = e.toString();
                lastQuery = q;
                lastSearchWasAdvanced = false;
                clearBtn.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                historyBox.setVisibility(q.trim().isEmpty() ? View.VISIBLE : View.GONE);
                if (pendingSearch != null) DEBOUNCE.removeCallbacks(pendingSearch);
                pendingSearch = () -> runSearch(q, results, d);
                DEBOUNCE.postDelayed(pendingSearch, 250);
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                Settings.addSearchHistory(this, field.getText().toString());
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        // Nach Rueckkehr aus der grossen Vorschau (eigene Activity) oder einer
        // anderen App war die gerade laufende Suche weg (Mathias: "schliesse
        // ich die Vorschau, leert sich die Suche auch") - je nachdem, welche
        // der beiden Ansichten zuletzt lief, genau die wiederherstellen.
        if (lastSearchWasAdvanced && advancedOpen) {
            runAdvancedSearch();
        } else if (!lastQuery.isEmpty()) {
            field.setText(lastQuery);
            field.setSelection(lastQuery.length());
            historyBox.setVisibility(View.GONE);
            runSearch(lastQuery, results, d);
        }
    }

    /** Suchverlauf als Aufklappmenue (Mathias' Wunsch): sichtbar, solange das
     *  Suchfeld leer ist; eine Kopfzeile mit drehendem Pfeil (▸/▾) klappt die
     *  Liste auf und zu. Ein Eintrag antippen fuellt das Feld und stoesst ueber
     *  den bestehenden TextWatcher ganz normal eine Suche an. Im aufgeklappten
     *  Bereich unten "Verlauf löschen". */
    private LinearLayout buildHistoryBox(EditText field, int d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        List<String> history = Settings.searchHistory(this);
        box.setVisibility(field.getText().length() == 0 && !history.isEmpty() ? View.VISIBLE : View.GONE);
        if (history.isEmpty()) return box;

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 10 * d, 0, 6 * d);
        TextView arrow = new TextView(this);
        arrow.setText(historyOpen ? "▾" : "▸");
        arrow.setTextColor(Color.parseColor("#2E9BE6"));
        arrow.setTextSize(17 * fs);
        arrow.setPadding(0, 0, 10 * d, 0);
        head.addView(arrow);
        TextView label = new TextView(this);
        label.setText("🕐 Letzte Suchen (" + history.size() + ")");
        label.setTextColor(Color.parseColor("#8899AA"));
        label.setTextSize(12 * fs);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label);
        head.setOnClickListener(v -> { historyOpen = !historyOpen; rebuild(); });
        box.addView(head);

        if (!historyOpen) return box;

        for (String q : history) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8 * d, 0, 8 * d);
            TextView icon = new TextView(this);
            icon.setText("🕐 ");
            icon.setTextColor(Color.parseColor("#6E6E73"));
            icon.setTextSize(13 * fs);
            row.addView(icon);
            TextView t = new TextView(this);
            t.setText(q);
            t.setTextColor(Color.WHITE);
            t.setTextSize(13 * fs);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);
            TextView remove = new TextView(this);
            remove.setText("✕");
            remove.setTextColor(Color.parseColor("#6E6E73"));
            remove.setTextSize(15 * fs);
            remove.setPadding(14 * d, 6 * d, 6 * d, 6 * d);
            remove.setOnClickListener(v -> { Settings.removeSearchHistory(this, q); rebuild(); });
            row.addView(remove);
            row.setOnClickListener(v -> { field.setText(q); field.setSelection(q.length()); });
            box.addView(row);
        }

        TextView clearAll = new TextView(this);
        clearAll.setText("Verlauf löschen");
        clearAll.setTextColor(Color.parseColor("#E06666"));
        clearAll.setTextSize(12 * fs);
        clearAll.setPadding(0, 8 * d, 0, 4 * d);
        clearAll.setOnClickListener(v -> { Settings.clearSearchHistory(this); historyOpen = false; rebuild(); });
        box.addView(clearAll);
        return box;
    }

    /** Suche komplett zuruecksetzen (Mathias' Wunsch "Neue Suche"): Feld leeren,
     *  alle Eingrenzungen/erweiterten Felder/Kategorie-Filter/aufgeklappten
     *  Zustaende zuruecksetzen. */
    private void resetSearch() {
        lastQuery = "";
        lastSearchWasAdvanced = false;
        lastOpenedPath = null;
        scopePaths = null;
        scopeFolder = null;
        scopeFolderPicking = false;
        advName = advAuthor = advTitle = advSeries = "";
        advExts.clear();
        advCreatedFrom = advCreatedTo = advModifiedFrom = advModifiedTo = 0;
        extPickerOpen = false;
        advancedOpen = false;
        EXCLUDED_CATS.clear();
        EXPANDED.clear();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
        rebuild();
    }

    /** Chip-Leiste zum Ein-/Ausschliessen einzelner Ergebnis-Kategorien fuer
     *  die laufende Suche (Mathias: "Quellen fuer einzelne Suchen
     *  ausschliessen") - wie EdgeTabs Posteingang-Kategoriefilter, hier aber
     *  auf die vier Ergebnisarten selbst statt auf Quell-Apps bezogen. */
    private View categoryChips(int d) {
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8 * d, 0, 0);
        String[][] cats = {{"files", "Dateien"}, {"images", "Bilder"}, {"videos", "Videos"},
                {"music", "Musik"}, {"contacts", "Kontakte"}, {"events", "Termine"},
                {"notifs", "Nachrichten"}};
        for (String[] cat : cats) row.addView(chip(cat[0], cat[1], d));
        hsv.addView(row);
        return hsv;
    }

    private View chip(String key, String label, int d) {
        boolean on = !EXCLUDED_CATS.contains(key);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(on ? Color.WHITE : Color.parseColor("#8899AA"));
        t.setTextSize(12 * fs);
        t.setPadding(12 * d, 6 * d, 12 * d, 6 * d);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14 * d);
        bg.setColor(on ? Color.parseColor("#2E9BE6") : Color.parseColor("#2C2C2E"));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 8 * d;
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            if (on) EXCLUDED_CATS.add(key); else EXCLUDED_CATS.remove(key);
            rebuild();
        });
        return t;
    }

    /** Kombinierbare erweiterte Suche - Dateiname/Autor/Titel/Buchserie/
     *  Dateiart/Erstellt-/Geaendert-Zeitraum, alle per UND verknuepft, wer
     *  ein Feld leer laesst schraenkt danach einfach nicht ein (Mathias'
     *  Wunsch, wortwoertlich diese Kriterien). */
    private View advancedSearchPanel(int d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(10 * d, 10 * d, 10 * d, 10 * d);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF1C1C1E"));
        bg.setCornerRadius(10 * d);
        box.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = 8 * d;
        box.setLayoutParams(blp);

        box.addView(advField("Dateiname", advName, s -> advName = s, d));
        box.addView(advField("Autor", advAuthor, s -> advAuthor = s, d));
        box.addView(advField("Titel", advTitle, s -> advTitle = s, d));
        box.addView(advField("Buchserie", advSeries, s -> advSeries = s, d));

        box.addView(extPicker(d));

        box.addView(advDateRange("Erstellt", advCreatedFrom, advCreatedTo,
                (from, to) -> { advCreatedFrom = from; advCreatedTo = to; }, d));
        box.addView(advDateRange("Geändert", advModifiedFrom, advModifiedTo,
                (from, to) -> { advModifiedFrom = from; advModifiedTo = to; }, d));

        Button go = new Button(this);
        go.setText("Suchen");
        go.setOnClickListener(v -> runAdvancedSearch());
        box.addView(go);

        Button reset = new Button(this);
        reset.setText("Zurücksetzen");
        reset.setOnClickListener(v -> {
            advName = advAuthor = advTitle = advSeries = "";
            advExts.clear();
            advCreatedFrom = advCreatedTo = advModifiedFrom = advModifiedTo = 0;
            extPickerOpen = false;
            lastSearchWasAdvanced = false;
            rebuild();
        });
        box.addView(reset);
        return box;
    }

    /** Kopfzeile "Dateiart" mit drehendem Pfeil, dahinter eingeklappt nur eine
     *  Zusammenfassung der Auswahl - antippen klappt ein mehrzeiliges Raster
     *  aller Endungen auf (Mathias' Wunsch, statt der vorherigen einzeiligen,
     *  wischbaren Liste, die bei jeder Auswahl an den Seitenanfang sprang).
     *  Die Auswahl (advExts) bleibt unabhaengig vom Auf-/Zugeklapptsein
     *  erhalten, bis "Zuruecksetzen" im umgebenden Panel angetippt wird. */
    private View extPicker(int d) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, 8 * d, 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 6 * d, 0, 6 * d);

        TextView arrow = new TextView(this);
        arrow.setText(extPickerOpen ? "▾" : "▸");
        arrow.setTextColor(Color.parseColor("#2E9BE6"));
        arrow.setTextSize(19 * fs); // groesser - war zu klein zum bequemen Antippen/Erkennen
        arrow.setPadding(0, 0, 10 * d, 0);
        head.addView(arrow);

        TextView label = new TextView(this);
        String summary = advExts.isEmpty() ? "Dateiart: alle"
                : "Dateiart: " + advExts.size() + " ausgewählt (" + String.join(", ", advExts) + ")";
        label.setText(summary);
        label.setTextColor(Color.parseColor("#8899AA"));
        label.setTextSize(12 * fs);
        label.setSingleLine(!extPickerOpen);
        if (!extPickerOpen) label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label);

        head.setOnClickListener(v -> { extPickerOpen = !extPickerOpen; rebuild(); });
        wrap.addView(head);

        if (extPickerOpen) {
            // (1) Manuelle Eingabe: beliebige Endung(en) hinzufuegen, auch solche
            //     die (noch) nicht im Index vorkommen. Mehrere per Leerzeichen/Komma.
            LinearLayout manualRow = new LinearLayout(this);
            manualRow.setOrientation(LinearLayout.HORIZONTAL);
            manualRow.setGravity(Gravity.CENTER_VERTICAL);
            manualRow.setPadding(0, 4 * d, 0, 4 * d);
            EditText in = new EditText(this);
            in.setHint("Eigene Endung(en), z. B. epub pdf");
            in.setHintTextColor(Color.parseColor("#6E6E73"));
            in.setTextColor(Color.WHITE);
            in.setTextSize(13 * fs);
            in.setSingleLine(true);
            in.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            manualRow.addView(in);
            TextView addBtn = new TextView(this);
            addBtn.setText("+ Hinzufügen");
            addBtn.setTextColor(Color.WHITE);
            addBtn.setTextSize(13 * fs);
            addBtn.setPadding(12 * d, 8 * d, 12 * d, 8 * d);
            GradientDrawable abg = new GradientDrawable();
            abg.setCornerRadius(10 * d);
            abg.setColor(Color.parseColor("#2E9BE6"));
            addBtn.setBackground(abg);
            addBtn.setOnClickListener(v -> { addManualExts(in.getText().toString()); rebuild(); });
            manualRow.addView(addBtn);
            wrap.addView(manualRow);

            // (2) Vorschlagsliste mit Klartext-Erklaerung, antippen zum
            //     An-/Abwaehlen (mehrere gleichzeitig moeglich).
            TextView sugHead = new TextView(this);
            sugHead.setText("Vorschläge (antippen):");
            sugHead.setTextColor(Color.parseColor("#6E6E73"));
            sugHead.setTextSize(11 * fs);
            sugHead.setPadding(0, 10 * d, 0, 2 * d);
            wrap.addView(sugHead);
            for (java.util.Map.Entry<String, String[][]> catE : EXT_CATS.entrySet()) {
                TextView catHead = new TextView(this);
                catHead.setText(catE.getKey());
                catHead.setTextColor(Color.parseColor("#7FB0D0"));
                catHead.setTextSize(12 * fs);
                catHead.setPadding(2 * d, 8 * d, 0, 2 * d);
                wrap.addView(catHead);
                for (String[] it : catE.getValue()) {
                    final String key = it[0];
                    boolean on = advExts.contains(key);
                    TextView row = new TextView(this);
                    row.setText(key + "  —  " + it[1]);
                    row.setTextColor(on ? Color.WHITE : Color.parseColor("#B0BEC5"));
                    row.setTextSize(13 * fs);
                    row.setPadding(10 * d, 8 * d, 10 * d, 8 * d);
                    GradientDrawable rbg = new GradientDrawable();
                    rbg.setCornerRadius(8 * d);
                    rbg.setColor(on ? Color.parseColor("#2E9BE6") : Color.parseColor("#2C2C2E"));
                    row.setBackground(rbg);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    rlp.setMargins(0, 2 * d, 0, 2 * d);
                    row.setLayoutParams(rlp);
                    row.setOnClickListener(v -> { if (advExts.contains(key)) advExts.remove(key); else advExts.add(key); rebuild(); });
                    wrap.addView(row);
                }
            }

            // (3) Tatsaechlich im Index vorhandene Endungen (Ueberblick, deckt
            //     auch die ohne Eintrag in der Vorschlagsliste ab).
            java.util.List<String> present = SearchStore.get(this).distinctExts();
            if (!present.isEmpty()) {
                TextView presHead = new TextView(this);
                presHead.setText("In deinem Index vorhanden:");
                presHead.setTextColor(Color.parseColor("#6E6E73"));
                presHead.setTextSize(11 * fs);
                presHead.setPadding(0, 12 * d, 0, 2 * d);
                wrap.addView(presHead);
                android.widget.GridLayout grid = new android.widget.GridLayout(this);
                grid.setColumnCount(3);
                for (String ext : present) {
                    final String key = ext.toLowerCase(java.util.Locale.ROOT);
                    boolean on = advExts.contains(key);
                    TextView chip = new TextView(this);
                    chip.setText(ext);
                    chip.setGravity(Gravity.CENTER);
                    chip.setTextColor(on ? Color.WHITE : Color.parseColor("#8899AA"));
                    chip.setTextSize(12 * fs);
                    chip.setPadding(8 * d, 8 * d, 8 * d, 8 * d);
                    GradientDrawable cbg = new GradientDrawable();
                    cbg.setCornerRadius(10 * d);
                    cbg.setColor(on ? Color.parseColor("#2E9BE6") : Color.parseColor("#2C2C2E"));
                    chip.setBackground(cbg);
                    android.widget.GridLayout.LayoutParams glp = new android.widget.GridLayout.LayoutParams();
                    glp.width = 0;
                    glp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    glp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
                    glp.setMargins(3 * d, 3 * d, 3 * d, 3 * d);
                    chip.setLayoutParams(glp);
                    chip.setOnClickListener(v -> {
                        if (advExts.contains(key)) advExts.remove(key); else advExts.add(key);
                        rebuild();
                    });
                    grid.addView(chip);
                }
                wrap.addView(grid);
            }
        }
        return wrap;
    }

    /** Kommagetrennte/leerzeichengetrennte Endungen zur Auswahl hinzufuegen
     *  (fuehrender Punkt und Gross-/Kleinschreibung egal). */
    private void addManualExts(String raw) {
        if (raw == null) return;
        for (String tok : raw.split("[,;\\s]+")) {
            String x = tok.trim().toLowerCase(java.util.Locale.ROOT);
            if (x.startsWith(".")) x = x.substring(1);
            if (!x.isEmpty()) advExts.add(x);
        }
    }

    /** Bekannte Dateiendungen nach Kategorie (mit Kurzerklaerung), innerhalb je
     *  Kategorie alphabetisch - fuer die Vorschlagsliste der erweiterten Suche. */
    private static final java.util.LinkedHashMap<String, String[][]> EXT_CATS = new java.util.LinkedHashMap<>();
    static {
        EXT_CATS.put("E-Books", new String[][]{{"azw","Kindle-Buch (AZW)"},{"azw3","Kindle-Buch (AZW3)"},{"epub","E-Book (EPUB)"},{"fb2","E-Book (FictionBook)"},{"mobi","Kindle-Buch (MOBI)"}});
        EXT_CATS.put("Dokumente", new String[][]{{"doc","Word-Dokument (älter)"},{"docx","Word-Dokument"},{"md","Markdown-Text"},{"odt","LibreOffice-Text (ODT)"},{"pdf","PDF-Dokument"},{"rtf","Rich-Text-Dokument"},{"txt","Textdokument"}});
        EXT_CATS.put("Tabellen", new String[][]{{"csv","Tabelle (CSV)"},{"ods","LibreOffice-Tabelle (ODS)"},{"xls","Excel-Tabelle (älter)"},{"xlsx","Excel-Tabelle"}});
        EXT_CATS.put("Präsentationen", new String[][]{{"odp","LibreOffice-Präsentation (ODP)"},{"ppt","PowerPoint (älter)"},{"pptx","PowerPoint-Präsentation"}});
        EXT_CATS.put("Comics", new String[][]{{"cbr","Comic-Archiv (CBR/RAR)"},{"cbz","Comic-Archiv (CBZ/ZIP)"}});
        EXT_CATS.put("Bilder", new String[][]{{"bmp","Bild (BMP)"},{"gif","Bild (GIF)"},{"jpg","Bild (JPEG)"},{"png","Bild (PNG)"},{"webp","Bild (WebP)"}});
        EXT_CATS.put("Audio", new String[][]{{"aac","Audio (AAC)"},{"flac","Audio (FLAC)"},{"m4a","Audio (M4A)"},{"mp3","Audio (MP3)"},{"ogg","Audio (OGG Vorbis)"},{"opus","Audio (Opus)"},{"wav","Audio (WAV)"},{"wma","Audio (WMA)"}});
        EXT_CATS.put("Video", new String[][]{{"avi","Video (AVI)"},{"mkv","Video (MKV)"},{"mov","Video (QuickTime)"},{"mp4","Video (MP4)"},{"webm","Video (WebM)"}});
        EXT_CATS.put("Archive", new String[][]{{"7z","Archiv (7-Zip)"},{"bz2","Bzip2-Datei / TAR.BZ2"},{"gz","Gzip-Datei / TAR.GZ"},{"rar","Archiv (RAR, nur Name)"},{"tar","Archiv (TAR)"},{"tgz","Archiv (TAR.GZ)"},{"xz","XZ-Datei / TAR.XZ"},{"zip","Archiv (ZIP)"}});
        EXT_CATS.put("Web & Daten", new String[][]{{"html","Webseite (HTML)"},{"json","JSON-Datei"},{"xml","XML-Datei"}});
    }

    private interface TextSink { void set(String s); }

    private View advField(String hint, String value, TextSink sink, int d) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.parseColor("#6E6E73"));
        e.setTextColor(Color.WHITE);
        e.setTextSize(13 * fs);
        e.setSingleLine(true);
        e.setText(value);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable ed) { sink.set(ed.toString()); }
        });
        return e;
    }

    private interface RangeSink { void set(long from, long to); }

    private View advDateRange(String label, long from, long to, RangeSink sink, int d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 8 * d, 0, 0);
        TextView l = new TextView(this);
        l.setText(label + ": ");
        l.setTextColor(Color.parseColor("#8899AA"));
        l.setTextSize(12 * fs);
        row.addView(l);
        Button fromBtn = new Button(this);
        fromBtn.setText(from > 0 ? DateUtils.formatDateTime(this, from, DateUtils.FORMAT_SHOW_DATE) : "von");
        fromBtn.setOnClickListener(v -> pickDate(from, d2 -> { sink.set(d2, to); rebuild(); }));
        row.addView(fromBtn);
        Button toBtn = new Button(this);
        toBtn.setText(to > 0 ? DateUtils.formatDateTime(this, to, DateUtils.FORMAT_SHOW_DATE) : "bis");
        toBtn.setOnClickListener(v -> pickDate(to, d2 -> { sink.set(from, d2); rebuild(); }));
        row.addView(toBtn);
        if (from > 0 || to > 0) {
            TextView clear = new TextView(this);
            clear.setText(" ✕");
            clear.setTextColor(Color.parseColor("#E06666"));
            clear.setPadding(8 * d, 0, 0, 0);
            clear.setOnClickListener(v -> { sink.set(0, 0); rebuild(); });
            row.addView(clear);
        }
        return row;
    }

    private interface DateSink { void set(long millis); }

    private void pickDate(long initial, DateSink sink) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (initial > 0) cal.setTimeInMillis(initial);
        new android.app.DatePickerDialog(this, (view, y, m, dOfM) -> {
            java.util.Calendar picked = java.util.Calendar.getInstance();
            picked.set(y, m, dOfM, 0, 0, 0);
            sink.set(picked.getTimeInMillis());
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    private void runAdvancedSearch() {
        if (advancedResults == null) return;
        lastSearchWasAdvanced = true;
        LinearLayout results = advancedResults;
        int d = advancedD;
        results.removeAllViews();
        FILE_SHOWN.clear(); // frische Suche -> Anzeige-Zähler zurücksetzen
        if (!hasStoragePermission()) { results.addView(storagePermissionHint(d)); return; }
        // "bis" schliesst den ganzen Tag ein, nicht nur 00:00 Uhr.
        long createdTo = advCreatedTo > 0 ? advCreatedTo + DateUtils.DAY_IN_MILLIS - 1 : 0;
        long modifiedTo = advModifiedTo > 0 ? advModifiedTo + DateUtils.DAY_IN_MILLIS - 1 : 0;
        List<SearchStore.FileHit> files = SearchStore.get(this).advancedSearch(
                advName, advAuthor, advTitle, advSeries, advExts,
                advCreatedFrom, createdTo, advModifiedFrom, modifiedTo, -1, scopePaths, scopeFolder);
        if (files.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Keine Treffer.");
            none.setTextColor(Color.parseColor("#8899AA"));
            none.setTextSize(13 * fs);
            results.addView(none);
            return;
        }
        View[] highlightHolder = new View[1];
        addFileCards(results, files, d, highlightHolder);
        if (highlightHolder[0] != null) scrollToRow(highlightHolder[0]);
    }

    /** Zeile unter einer Dateien-Trefferliste, um denselben Ausschnitt mit
     *  EINER ANDEREN Suche weiter einzugrenzen (Mathias' Wunsch: "innerhalb
     *  der Suchergebnisse erneut suchen, aber auch mit anderen Parametern").
     *  Ersetzt scopePaths bei jedem Antippen - so laesst sich mehrfach
     *  hintereinander eingrenzen, jedes Mal auf Basis der zuletzt sichtbaren
     *  Treffer, unabhaengig davon ob per einfacher oder erweiterter Suche. */
    private View narrowRow(List<SearchStore.FileHit> files, int d) {
        TextView t = new TextView(this);
        t.setText("🔎 Nur in diesen " + files.size() + " Treffern weitersuchen");
        t.setTextColor(Color.parseColor("#2E9BE6"));
        t.setTextSize(13 * fs);
        t.setPadding(4 * d, 6 * d, 4 * d, 16 * d);
        t.setOnClickListener(v -> {
            List<String> paths = new ArrayList<>();
            for (SearchStore.FileHit h : files) paths.add(h.path);
            scopePaths = paths;
            rebuild();
        });
        return t;
    }

    /** Banner ueber den Ergebnissen, solange eine Eingrenzung aktiv ist -
     *  zeigt worauf eingegrenzt wurde und erlaubt, sie wieder aufzuheben. */
    private View scopeBanner(int d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1B3A52"));
        bg.setCornerRadius(10 * d);
        row.setBackground(bg);
        row.setPadding(12 * d, 8 * d, 12 * d, 8 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10 * d;
        row.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText("🔎 Eingegrenzt auf " + scopePaths.size() + " Treffer");
        label.setTextColor(Color.parseColor("#8ecbff"));
        label.setTextSize(12.5f * fs);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        TextView clear = new TextView(this);
        clear.setText("Aufheben ✕");
        clear.setTextColor(Color.parseColor("#2E9BE6"));
        clear.setTextSize(12.5f * fs);
        clear.setPadding(12 * d, 0, 0, 0);
        clear.setOnClickListener(v -> { scopePaths = null; rebuild(); });
        row.addView(clear);
        return row;
    }

    /** Banner, solange eine Ordner-Einschraenkung aktiv ist - zeigt den Ordner
     *  und erlaubt, sie wieder aufzuheben. */
    private View folderScopeBanner(int d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1B3A52"));
        bg.setCornerRadius(10 * d);
        row.setBackground(bg);
        row.setPadding(12 * d, 8 * d, 12 * d, 8 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10 * d;
        row.setLayoutParams(lp);

        String nm = scopeFolder;
        int slash = nm.replaceAll("/$", "").lastIndexOf('/');
        String shortNm = slash >= 0 ? nm.substring(slash + 1) : nm;
        TextView label = new TextView(this);
        label.setText("📁 Nur in " + shortNm + " (mit Unterordnern)");
        label.setTextColor(Color.parseColor("#8ecbff"));
        label.setTextSize(12.5f * fs);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        TextView clear = new TextView(this);
        clear.setText("Aufheben ✕");
        clear.setTextColor(Color.parseColor("#2E9BE6"));
        clear.setTextSize(12.5f * fs);
        clear.setPadding(12 * d, 0, 0, 0);
        clear.setOnClickListener(v -> { scopeFolder = null; rebuild(); });
        row.addView(clear);
        return row;
    }

    /** Kleiner Ordner-Browser, um den Such-Bereich auf einen Ordner (samt
     *  Unterordnern) einzugrenzen - eigenstaendig, nicht der Ordner-Browser der
     *  Einstellungen (der fuegt dauerhaft Index-Ordner hinzu). */
    private View scopeFolderBrowser(int d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(8 * d, 8 * d, 8 * d, 8 * d);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF141416"));
        bg.setCornerRadius(8 * d);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = 8 * d;
        box.setLayoutParams(blp);
        box.setBackground(bg);

        TextView path = new TextView(this);
        path.setText(scopeBrowsePath);
        path.setTextColor(Color.parseColor("#2E9BE6"));
        path.setTextSize(12 * fs);
        box.addView(path);

        Button choose = new Button(this);
        choose.setText("In diesem Ordner suchen");
        choose.setOnClickListener(v -> {
            scopeFolder = scopeBrowsePath;
            scopeFolderPicking = false;
            rebuild();
        });
        box.addView(choose);

        java.io.File dir = new java.io.File(scopeBrowsePath);
        java.io.File parent = dir.getParentFile();
        if (parent != null) {
            TextView up = new TextView(this);
            up.setText("⬆ .. (nach oben)");
            up.setTextColor(Color.parseColor("#B0B0B5"));
            up.setTextSize(13 * fs);
            up.setPadding(0, 6 * d, 0, 6 * d);
            up.setOnClickListener(v -> { scopeBrowsePath = parent.getAbsolutePath(); rebuild(); });
            box.addView(up);
        }

        java.io.File[] children = dir.listFiles(java.io.File::isDirectory);
        if (children != null) {
            java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (java.io.File c : children) {
                if (c.getName().startsWith(".")) continue;
                TextView row = new TextView(this);
                row.setText("📁 " + c.getName());
                row.setTextColor(Color.WHITE);
                row.setTextSize(13 * fs);
                row.setPadding(0, 6 * d, 0, 6 * d);
                row.setOnClickListener(v -> { scopeBrowsePath = c.getAbsolutePath(); rebuild(); });
                box.addView(row);
            }
        }

        Button cancel = new Button(this);
        cancel.setText("Abbrechen");
        cancel.setOnClickListener(v -> { scopeFolderPicking = false; rebuild(); });
        box.addView(cancel);
        return box;
    }

    /** Datei-Treffer in eigene Karten je Kategorie aufteilen (Dateien/Bilder/
     *  Videos/Musik, Mathias' Wunsch) - abgewaehlte Kategorien werden nicht
     *  gezeigt. Unter jeder Karte die "in diesen Treffern weitersuchen"-Zeile.
     *  Die zuletzt geoeffnete Datei wird ggf. aufgeklappt, damit sie nach
     *  Rueckkehr aus einer anderen App/der Vorschau sichtbar ist. */
    private void addFileCards(LinearLayout results, List<SearchStore.FileHit> files, int d, View[] highlightHolder) {
        java.util.LinkedHashMap<String, List<SearchStore.FileHit>> byCat = new java.util.LinkedHashMap<>();
        byCat.put("files", new ArrayList<>());
        byCat.put("images", new ArrayList<>());
        byCat.put("videos", new ArrayList<>());
        byCat.put("music", new ArrayList<>());
        for (SearchStore.FileHit h : files) byCat.get(fileCategory(h.ext)).add(h);

        String[][] order = {{"files", "Dateien"}, {"images", "Bilder"},
                {"videos", "Videos"}, {"music", "Musik"}};
        for (String[] cat : order) {
            if (EXCLUDED_CATS.contains(cat[0])) continue;
            List<SearchStore.FileHit> list = byCat.get(cat[0]);
            if (list.isEmpty()) continue;
            if (lastOpenedPath != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).path.equals(lastOpenedPath) && i + 1 > shown(cat[0])) {
                        FILE_SHOWN.put(cat[0], i + 1); // so weit aufklappen, dass sie sichtbar ist
                        break;
                    }
                }
            }
            results.addView(fileCard(cat[1], list, d, highlightHolder, cat[0]));
            results.addView(narrowRow(list, d));
        }
    }

    /** Datei-Ergebniskarte mit LAZY aufgebauten Zeilen: eingeklappt PAGE, per
     *  "Mehr anzeigen" wächst die Zahl schrittweise (kein fester Deckel - der
     *  Nutzer entscheidet). Es werden nur die tatsächlich gezeigten Zeilen
     *  erzeugt, damit auch sehr viele Treffer flüssig bleiben. */
    private View fileCard(String title, List<SearchStore.FileHit> hits, int d, View[] highlightHolder, String key) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E1E20"));
        bg.setCornerRadius(12 * d);
        bg.setStroke((int) (1 * d), Color.parseColor("#3A3A3C"));
        box.setBackground(bg);
        box.setPadding(0, 0, 0, 6 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10 * d;
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable headBg = new GradientDrawable();
        headBg.setColor(Color.parseColor("#2E9BE6"));
        headBg.setCornerRadii(new float[]{12*d,12*d,12*d,12*d,0,0,0,0});
        head.setBackground(headBg);
        head.setPadding(14 * d, 8 * d, 14 * d, 8 * d);
        TextView t = new TextView(this);
        t.setText(title + " (" + hits.size() + ")");
        t.setTextColor(Color.WHITE);
        t.setTextSize(13 * fs);
        head.addView(t);
        box.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        box.addView(body);
        fillFileCardBody(body, hits, key, d, highlightHolder);
        return box;
    }

    /** Aktuell gezeichnete Zeilenzahl je Datei-Kategorie (Standard PAGE). */
    private int shown(String key) {
        Integer n = FILE_SHOWN.get(key);
        return n == null ? PAGE : n;
    }

    private void fillFileCardBody(LinearLayout body, List<SearchStore.FileHit> hits, String key,
                                  int d, View[] highlightHolder) {
        body.removeAllViews();
        int shown = Math.min(shown(key), hits.size());
        for (int i = 0; i < shown; i++) body.addView(fileRow(hits.get(i), d, highlightHolder));
        int rest = hits.size() - shown;
        if (rest > 0) {
            TextView more = new TextView(this);
            int step = Math.min(FILE_SHOW_STEP, rest);
            more.setText("Mehr anzeigen (" + step + " von " + rest + " weiteren) ▼");
            more.setTextColor(Color.parseColor("#2E9BE6"));
            more.setTextSize(12 * fs);
            more.setPadding(14 * d, 8 * d, 14 * d, 4 * d);
            more.setOnClickListener(v -> {
                FILE_SHOWN.put(key, shown + FILE_SHOW_STEP);
                fillFileCardBody(body, hits, key, d, highlightHolder);
            });
            body.addView(more);
        }
        if (shown > PAGE) {
            TextView less = new TextView(this);
            less.setText("Weniger anzeigen ▲");
            less.setTextColor(Color.parseColor("#2E9BE6"));
            less.setTextSize(12 * fs);
            less.setPadding(14 * d, 8 * d, 14 * d, 4 * d);
            less.setOnClickListener(v -> {
                FILE_SHOWN.put(key, PAGE);
                fillFileCardBody(body, hits, key, d, highlightHolder);
            });
            body.addView(less);
        }
    }

    private void runSearch(String q, LinearLayout results, int d) {
        FILE_SHOWN.clear(); // frische Suche -> Anzeige-Zähler zurücksetzen
        results.removeAllViews();
        if (q == null || q.trim().length() < 2) {
            TextView hint = new TextView(this);
            hint.setText("Mindestens 2 Zeichen eingeben.");
            hint.setTextColor(Color.parseColor("#8899AA"));
            hint.setTextSize(13 * fs);
            results.addView(hint);
            return;
        }
        // Datenabruf im HINTERGRUND: eine haeufige Volltext-Anfrage (z.B. "sex")
        // trifft zehntausende Treffer; getCount()/fillWindow auf dem UI-Thread
        // blockierte >5 s -> ANR (Absturzbericht 2026-09-29). Ergebnis wird auf
        // dem Main-Thread aufgebaut; veraltete (weitergetippte) Anfragen verworfen.
        TextView busy = new TextView(this);
        busy.setText("Suche läuft …");
        busy.setTextColor(Color.parseColor("#8899AA"));
        busy.setTextSize(13 * fs);
        results.addView(busy);

        final String query = q;
        final boolean storage = hasStoragePermission();
        final boolean pim = hasPimPermission();
        final boolean notif = hasNotifPermission();
        final boolean anyFileCat = !(EXCLUDED_CATS.contains("files") && EXCLUDED_CATS.contains("images")
                && EXCLUDED_CATS.contains("videos") && EXCLUDED_CATS.contains("music"));
        final java.util.Collection<String> scopeSnap = scopePaths;
        final String folderSnap = scopeFolder;
        SEARCH_EXEC.execute(() -> {
            // Schon ueberholt (weitergetippt)? Dann gar nicht erst die (evtl. teure)
            // Abfrage starten - sonst staut sich der Single-Thread mit veralteten
            // Suchen zu und blockiert die aktuelle.
            if (!query.equals(lastQuery)) return;
            final List<SearchStore.FileHit> files = (storage && anyFileCat)
                    ? SearchStore.get(this).search(query, SEARCH_LIMIT, scopeSnap, folderSnap) : new ArrayList<>();
            final List<ContactHit> contacts = EXCLUDED_CATS.contains("contacts") ? new ArrayList<>() : queryContacts(query);
            final List<EventHit> events = EXCLUDED_CATS.contains("events") ? new ArrayList<>() : queryEvents(query);
            final List<SearchStore.NotifHit> notifs = new ArrayList<>();
            if (notif && !EXCLUDED_CATS.contains("notifs")) {
                java.util.Set<String> allowed = Settings.notifSources(this);
                for (SearchStore.NotifHit h : SearchStore.get(this).searchNotifications(query, 200)) {
                    if (allowed.contains(h.pkg)) notifs.add(h);
                    if (notifs.size() >= 60) break;
                }
            }
            runOnUiThread(() -> {
                // Nur anzeigen, wenn diese Anfrage noch die aktuelle ist
                // (Nutzer koennte weiter getippt oder zur erweiterten Suche
                // gewechselt haben).
                if (isFinishing() || isDestroyed()) return;
                if (lastSearchWasAdvanced || !query.equals(lastQuery)) return;
                results.removeAllViews();
                if (!storage) results.addView(storagePermissionHint(d));
                if (!pim) results.addView(pimPermissionHint(d));
                if (!notif) results.addView(notifPermissionHint(d));
                if (files.isEmpty() && contacts.isEmpty() && events.isEmpty() && notifs.isEmpty()) {
                    TextView none = new TextView(this);
                    none.setText("Keine Treffer.");
                    none.setTextColor(Color.parseColor("#8899AA"));
                    none.setTextSize(13 * fs);
                    results.addView(none);
                    return;
                }
                // Die zuletzt geoeffnete Datei hervorheben und ins Blickfeld
                // scrollen (Mathias' Wunsch) - addFileCards klappt ihre Karte auf.
                if (files.size() >= SEARCH_LIMIT) {
                    TextView many = new TextView(this);
                    many.setText("Sehr viele Treffer – die ersten " + SEARCH_LIMIT
                            + " werden gezeigt. Zum Eingrenzen: Suchbegriff ergänzen,"
                            + " „In Ordner suchen…“ oder die erweiterte Suche nutzen.");
                    many.setTextColor(Color.parseColor("#E0A030"));
                    many.setTextSize(12 * fs);
                    many.setPadding(0, 0, 0, 6 * d);
                    results.addView(many);
                }
                View[] highlightHolder = new View[1];
                addFileCards(results, files, d, highlightHolder);
                if (!contacts.isEmpty()) results.addView(card("Kontakte", contacts.size(), d, contactRows(contacts, d), "contacts"));
                if (!events.isEmpty()) results.addView(card("Termine", events.size(), d, eventRows(events, d), "events"));
                if (!notifs.isEmpty()) results.addView(card("Nachrichten", notifs.size(), d, notifRows(notifs, d), "notifs"));
                if (highlightHolder[0] != null) scrollToRow(highlightHolder[0]);
            });
        });
    }

    /** Die uebergebene Zeile ins Blickfeld scrollen - Summe der getTop()-
     *  Werte bis hoch zu `root` (direktes Kind von `scroll`), wie
     *  EdgeTabs SettingsTab die Scroll-Position nach einem Neuaufbau
     *  wiederherstellt (scroll.post, da vor dem Layout-Durchlauf getTop()
     *  noch 0 waere). */
    private void scrollToRow(View row) {
        scroll.post(() -> {
            int y = 0;
            View v = row;
            while (v != null && v != root) {
                y += v.getTop();
                Object p = v.getParent();
                if (!(p instanceof View)) break;
                v = (View) p;
            }
            scroll.smoothScrollTo(0, Math.max(0, y - dp(60)));
        });
    }

    // ---------- Benachrichtigungen ----------

    private List<View> notifRows(List<SearchStore.NotifHit> hits, int d) {
        List<View> out = new ArrayList<>();
        for (SearchStore.NotifHit h : hits) out.add(notifRow(h, d));
        return out;
    }

    private View notifRow(SearchStore.NotifHit h, int d) {
        LinearLayout row = resultRow(d);
        row.addView(rowIcon(R.drawable.ic_file, colorFor(h.pkg), d));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText((h.title == null || h.title.isEmpty()) ? "(ohne Titel)" : h.title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(14 * fs);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);

        if (h.snippet != null && !h.snippet.isEmpty()) col.addView(smallText(h.snippet, "#B0B0B5"));
        String meta = (h.appLabel == null ? h.pkg : h.appLabel) + " · "
                + DateUtils.getRelativeTimeSpanString(h.posted, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        col.addView(smallText(meta, "#6E6E73"));
        row.addView(col);

        row.setOnClickListener(v -> {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(h.pkg);
                if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
            } catch (Exception ignored) {}
        });
        return row;
    }

    // ---------- Karten-Rahmen (BB-Muster: Kopf + Zeilen + "Mehr anzeigen") ----------

    private View card(String title, int count, int d, List<View> rows, String key) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E1E20"));
        bg.setCornerRadius(12 * d);
        bg.setStroke((int) (1 * d), Color.parseColor("#3A3A3C"));
        box.setBackground(bg);
        box.setPadding(0, 0, 0, 6 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10 * d;
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable headBg = new GradientDrawable();
        headBg.setColor(Color.parseColor("#2E9BE6"));
        headBg.setCornerRadii(new float[]{12*d,12*d,12*d,12*d,0,0,0,0});
        head.setBackground(headBg);
        head.setPadding(14 * d, 8 * d, 14 * d, 8 * d);
        TextView t = new TextView(this);
        t.setText(title + " (" + count + ")");
        t.setTextColor(Color.WHITE);
        t.setTextSize(13 * fs);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);
        box.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        box.addView(body);
        fillCardBody(body, rows, key, d);
        return box;
    }

    private void fillCardBody(LinearLayout body, List<View> rows, String key, int d) {
        body.removeAllViews();
        boolean expanded = EXPANDED.contains(key);
        int shown = 0;
        for (View row : rows) {
            if (!expanded && shown >= PAGE) break;
            if (row.getParent() instanceof LinearLayout) ((LinearLayout) row.getParent()).removeView(row);
            body.addView(row);
            shown++;
        }
        if (rows.size() > PAGE) {
            TextView more = new TextView(this);
            more.setText(expanded ? "Weniger anzeigen ▲" : "Mehr anzeigen (" + (rows.size() - PAGE) + ") ▼");
            more.setTextColor(Color.parseColor("#2E9BE6"));
            more.setTextSize(12 * fs);
            more.setPadding(14 * d, 8 * d, 14 * d, 4 * d);
            more.setOnClickListener(v -> {
                if (expanded) EXPANDED.remove(key); else EXPANDED.add(key);
                fillCardBody(body, rows, key, d);
            });
            body.addView(more);
        }
    }

    // ---------- Dateien ----------

    private List<View> fileRows(List<SearchStore.FileHit> hits, int d, View[] highlightHolder) {
        List<View> out = new ArrayList<>();
        for (SearchStore.FileHit h : hits) out.add(fileRow(h, d, highlightHolder));
        return out;
    }

    private View fileRow(SearchStore.FileHit h, int d, View[] highlightHolder) {
        LinearLayout row = resultRow(d);
        boolean isLastOpened = h.path.equals(lastOpenedPath);
        if (isLastOpened) {
            GradientDrawable hbg = new GradientDrawable();
            hbg.setColor(Color.parseColor("#222E9BE6"));
            hbg.setStroke((int) (1 * d), Color.parseColor("#2E9BE6"));
            hbg.setCornerRadius(8 * d);
            row.setBackground(hbg);
            if (highlightHolder != null) highlightHolder[0] = row;
        }
        row.addView(rowThumbOrIcon(h, d));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        // Buch-/Dokumenttitel statt Dateiname, falls aus Metadaten bekannt -
        // "Der Herr der Ringe.epub" liest sich schlechter als "Der Herr der
        // Ringe".
        name.setText(h.title != null && !h.title.isEmpty() ? h.title : h.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(14 * fs);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nameRow.addView(name);
        if (isLastOpened) {
            TextView badge = new TextView(this);
            badge.setText(" zuletzt geöffnet ");
            badge.setTextColor(Color.parseColor("#2E9BE6"));
            badge.setTextSize(10 * fs);
            nameRow.addView(badge);
        }
        col.addView(nameRow);

        if (h.author != null && !h.author.isEmpty() || h.series != null && !h.series.isEmpty()) {
            StringBuilder meta = new StringBuilder();
            if (h.author != null && !h.author.isEmpty()) meta.append(h.author);
            if (h.series != null && !h.series.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(h.series);
                if (h.seriesIndex > 0) meta.append(" #").append(
                        h.seriesIndex == Math.floor(h.seriesIndex) ? String.valueOf((int) h.seriesIndex) : String.valueOf(h.seriesIndex));
            }
            col.addView(smallText(meta.toString(), "#2E9BE6"));
        }

        if (h.drm) {
            col.addView(smallText("Kopiergeschützt – nur Name durchsucht", "#C9A227"));
        } else if (h.snippet != null && !h.snippet.isEmpty()) {
            col.addView(smallText(h.snippet, "#B0B0B5"));
        }
        col.addView(smallText(h.path, "#6E6E73"));
        row.addView(col);

        row.setOnClickListener(v -> openFile(h.path));
        return row;
    }

    private void openFile(String path) {
        try {
            lastOpenedPath = path;
            rememberSearch();
            java.io.File f = new java.io.File(path);
            Uri uri = LocalFileProvider.uriForFile(this, f);
            String mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extOf(f.getName()));
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime != null ? mime : "*/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Stabile, unterscheidbare Farbe je Schluessel (z.B. Dateiendung) - jetzt
     *  aus der gemeinsamen Bibliothek (de.herbers.common.ColorUtil), damit
     *  EdgeTab/Sucher dieselbe Farbwahl teilen. */
    private static int colorFor(String key) {
        return de.herbers.common.ColorUtil.colorFor(key);
    }

    // ---------- Kontakte ----------

    private static final class ContactHit { String lookupKey, name; }

    private List<ContactHit> queryContacts(String q) {
        List<ContactHit> out = new ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return out;
        Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q));
        try (Cursor c = getContentResolver().query(uri,
                new String[]{ContactsContract.Contacts.LOOKUP_KEY,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY}, null, null, null)) {
            if (c != null) {
                while (c.moveToNext() && out.size() < 20) {
                    ContactHit h = new ContactHit();
                    h.lookupKey = c.getString(0);
                    h.name = c.getString(1);
                    if (h.name != null) out.add(h);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<View> contactRows(List<ContactHit> hits, int d) {
        List<View> out = new ArrayList<>();
        for (ContactHit h : hits) {
            LinearLayout row = resultRow(d);
            row.addView(rowIcon(R.drawable.ic_contacts, Color.parseColor("#B0B0B0"), d));
            TextView name = new TextView(this);
            name.setText(h.name);
            name.setTextColor(Color.WHITE);
            name.setTextSize(14 * fs);
            row.addView(name);
            String key = h.lookupKey;
            row.setOnClickListener(v -> {
                try {
                    Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key);
                    startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {}
            });
            out.add(row);
        }
        return out;
    }

    // ---------- Termine ----------

    private static final class EventHit { long id, begin; String title; }

    private List<EventHit> queryEvents(String q) {
        List<EventHit> out = new ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) return out;
        String[] proj = {CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DTSTART};
        String sel = CalendarContract.Events.TITLE + " LIKE ? OR " + CalendarContract.Events.DESCRIPTION + " LIKE ?";
        String like = "%" + q + "%";
        try (Cursor c = getContentResolver().query(CalendarContract.Events.CONTENT_URI, proj,
                sel, new String[]{like, like}, CalendarContract.Events.DTSTART + " DESC")) {
            if (c != null) {
                while (c.moveToNext() && out.size() < 20) {
                    EventHit h = new EventHit();
                    h.id = c.getLong(0);
                    h.title = c.getString(1);
                    h.begin = c.isNull(3) ? 0 : c.getLong(3);
                    out.add(h);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<View> eventRows(List<EventHit> hits, int d) {
        List<View> out = new ArrayList<>();
        for (EventHit h : hits) {
            LinearLayout row = resultRow(d);
            row.addView(rowIcon(R.drawable.ic_calendar, Color.parseColor("#2E9BE6"), d));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(this);
            t.setText(h.title == null || h.title.isEmpty() ? "(ohne Titel)" : h.title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(14 * fs);
            col.addView(t);
            if (h.begin > 0) col.addView(smallText(DateUtils.formatDateTime(this, h.begin,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME).toString(), "#2E9BE6"));
            row.addView(col);
            long eventId = h.id, begin = h.begin;
            row.setOnClickListener(v -> {
                try {
                    Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
                    startActivity(new Intent(Intent.ACTION_VIEW).setData(uri)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {}
            });
            out.add(row);
        }
        return out;
    }

    // ---------- gemeinsame Zeilen-Bausteine ----------

    private LinearLayout resultRow(int d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(14 * d, 8 * d, 14 * d, 8 * d);
        row.setClickable(true);
        return row;
    }

    /** Miniaturbild (Titelbild/Bild selbst/PDF-erste-Seite - siehe
     *  Thumbnails.java), falls beim Indizieren eins erzeugt werden konnte,
     *  sonst das bisherige farbige Dateityp-Symbol. Antippen DER MINIATUR
     *  oeffnet die grosse Vorschau (PreviewActivity) - der Rest der Zeile
     *  bleibt beim bisherigen Verhalten (Datei direkt in der zustaendigen
     *  App oeffnen, siehe openFile()), Mathias wollte ausdruecklich "beim
     *  Antippen der [Miniatur]" fuer die Vorschau. */
    private View rowThumbOrIcon(SearchStore.FileHit h, int d) {
        int s = 44 * d;
        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = 12 * d;
        iv.setLayoutParams(lp);
        iv.setClickable(true);
        iv.setOnClickListener(v -> openPreview(h));

        java.io.File thumb = Thumbnails.fileFor(this, h.path);
        android.graphics.Bitmap bmp = thumb.isFile()
                ? android.graphics.BitmapFactory.decodeFile(thumb.getAbsolutePath()) : null;
        if (bmp != null) {
            showThumb(iv, bmp, d);
            return iv;
        }

        // Kein Bild im Cache -> vorerst das farbige Typ-Symbol, aber (einmalig
        // je Sitzung und nur fuer Formate, die ueberhaupt eins hergeben) im
        // Hintergrund nacherzeugen und dann einblenden. So erscheinen
        // Vorschaubilder auch dann, wenn der System-Cache geleert wurde, die
        // Datei erst nach dem letzten Indizierlauf dazukam oder ueber ihren
        // Ordner noch nie ein Lauf lief (Mathias' Meldung "Titelbilder fehlen").
        showIcon(iv, h, d);
        if (Thumbnails.canHaveThumb(h.ext) && THUMB_TRIED.add(h.path)) {
            final String path = h.path;
            final String ext = h.ext;
            final java.lang.ref.WeakReference<ImageView> ref = new java.lang.ref.WeakReference<>(iv);
            final Context app = getApplicationContext();
            THUMB_EXEC.execute(() -> {
                if (!Thumbnails.ensure(app, new java.io.File(path), ext)) return;
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(
                        Thumbnails.fileFor(app, path).getAbsolutePath());
                if (b == null) return;
                runOnUiThread(() -> {
                    ImageView target = ref.get();
                    if (target != null) showThumb(target, b, d);
                });
            });
        }
        return iv;
    }

    private void showThumb(ImageView iv, android.graphics.Bitmap bmp, int d) {
        iv.setImageBitmap(bmp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.clearColorFilter();
        iv.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable clip = new android.graphics.drawable.GradientDrawable();
        clip.setCornerRadius(6 * d);
        iv.setClipToOutline(true);
        iv.setBackground(clip);
    }

    private void showIcon(ImageView iv, SearchStore.FileHit h, int d) {
        iv.setImageResource(R.drawable.ic_file);
        iv.setColorFilter(colorFor(h.ext));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setClipToOutline(false);
        iv.setBackground(null);
        int p = 8 * d;
        iv.setPadding(p, p, p, p);
    }

    private void openPreview(SearchStore.FileHit h) {
        // Auch die Vorschau zaehlt als "zuletzt geoeffnet" (Mathias' Meldung:
        // beim Schliessen der Vorschau sprang die Liste sonst zur zuletzt in
        // einer ANDEREN App geoeffneten Datei statt zur vorangezeigten).
        lastOpenedPath = h.path;
        rememberSearch();
        Intent i = new Intent(this, PreviewActivity.class);
        i.putExtra(PreviewActivity.EXTRA_PATH, h.path);
        i.putExtra(PreviewActivity.EXTRA_EXT, h.ext);
        i.putExtra(PreviewActivity.EXTRA_CONTENT_OK, contentIndexingCoversPath(h.path));
        startActivity(i);
    }

    /** Die aktuelle einfache Suche in den Verlauf aufnehmen, sobald der Nutzer
     *  einen Treffer oeffnet/vorzeigt (ein deutliches Zeichen, dass der Begriff
     *  gemeint war) - so fuellt sich der Verlauf zuverlaessig, ohne jede
     *  Tipp-Pause der 250ms-Entprellung mitzuschreiben. Erweiterte Suchen
     *  bleiben aussen vor (der Verlauf ist nur fuer die Freitextsuche). */
    private void rememberSearch() {
        if (!lastSearchWasAdvanced && lastQuery != null && lastQuery.trim().length() >= 2) {
            Settings.addSearchHistory(this, lastQuery);
        }
    }

    /** Ob der Ordner, in dem diese Datei liegt, "Inhalt durchsuchbar machen"
     *  angehakt hat - die Text-Vorschau (TXT/MD/etc.) kann den bereits
     *  extrahierten Text sonst nicht zeigen, siehe PreviewActivity. */
    private boolean contentIndexingCoversPath(String path) {
        for (String folder : Settings.searchFolders(this)) {
            if (path.startsWith(folder) && Settings.contentIndexingEnabled(this, folder)) return true;
        }
        return false;
    }

    private View rowIcon(int res, int tint, int d) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(res);
        icon.setColorFilter(tint);
        int s = 28 * d;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = 12 * d;
        icon.setLayoutParams(lp);
        return icon;
    }

    private TextView smallText(String text, String color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor(color));
        t.setTextSize(11.5f * fs);
        t.setMaxLines(4); // laengere Fundstellen-Schnipsel (Mathias' Wunsch), war 2
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    // ---------- Berechtigungen ----------

    static boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    private boolean hasPimPermission() {
        return checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** Wie EdgeTabs InboxTab: Benachrichtigungszugriff ist kein normaler
     *  Laufzeit-Dialog, sondern eine Ein/Aus-Liste in den System-Einstellungen
     *  - der eigene Eintrag steht als Paketname in dieser Secure-Setting. */
    private boolean hasNotifPermission() {
        String flat = android.provider.Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private View storagePermissionHint(int d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 8 * d, 0, 12 * d);
        TextView t = new TextView(this);
        t.setText("Für die Dateisuche fehlt der Zugriff auf \"Alle Dateien\".");
        t.setTextColor(Color.parseColor("#CCCCCC"));
        t.setTextSize(13 * fs);
        t.setPadding(0, 0, 0, 10 * d);
        box.addView(t);
        Button allow = new Button(this);
        allow.setText("Zugriff erlauben");
        allow.setOnClickListener(v -> openAllFilesSettings());
        box.addView(allow);
        return box;
    }

    /**
     * Drei Stufen, statt bei einer stillen Ausnahme einfach nichts zu tun
     * (Mathias' Meldung: "kann die Berechtigungen nicht öffnen" - vermutlich
     * ein ROM, das den ersten oder gar beide der speziellen Alle-Dateien-
     * Bildschirme nicht kennt). Die dritte Stufe (App-Info) gibt es auf
     * praktisch jedem Android/jeder ROM, von dort aus laesst sich die
     * Berechtigung immer noch manuell erteilen. Schlaegt selbst das fehl,
     * wenigstens eine Meldung statt totaler Stille.
     */
    private void openAllFilesSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        } catch (Exception ignored) {}
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            return;
        } catch (Exception ignored) {}
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            android.widget.Toast.makeText(this,
                    "Bitte unter Berechtigungen \"Alle Dateien\" manuell erlauben.",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        } catch (Exception ignored) {}
        android.widget.Toast.makeText(this,
                "Einstellungen konnten nicht geöffnet werden - bitte manuell: "
                + "System-Einstellungen → Apps → Sucher → Berechtigungen → Alle Dateien.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private View notifPermissionHint(int d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, 12 * d);
        TextView t = new TextView(this);
        t.setText("Nachrichten (Chats/Mails) nur teilweise durchsuchbar - "
                + "nur was als Benachrichtigung durchkommt, keine volle Historie.");
        t.setTextColor(Color.parseColor("#CCCCCC"));
        t.setTextSize(13 * fs);
        t.setPadding(0, 0, 0, 10 * d);
        box.addView(t);
        Button allow = new Button(this);
        allow.setText("Benachrichtigungszugriff erlauben");
        allow.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                android.widget.Toast.makeText(this,
                        "Bitte manuell: System-Einstellungen → Apps → "
                        + "Benachrichtigungszugriff → Sucher.",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        box.addView(allow);
        return box;
    }

    private View pimPermissionHint(int d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, 12 * d);
        TextView t = new TextView(this);
        t.setText("Kontakte/Termine noch nicht durchsuchbar (Berechtigung fehlt).");
        t.setTextColor(Color.parseColor("#CCCCCC"));
        t.setTextSize(13 * fs);
        t.setPadding(0, 0, 0, 10 * d);
        box.addView(t);
        Button allow = new Button(this);
        allow.setText("Kontakte/Termine erlauben");
        allow.setOnClickListener(v -> requestPermissions(new String[]{
                android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.READ_CALENDAR}, REQ_PIM));
        box.addView(allow);
        return box;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PIM) rebuild();
    }

    // Waehrend Sichern/Wiederherstellen laeuft (kann bei einem grossen Index
    // etliche Sekunden dauern - Kopieren/Entpacken der Datenbank-Datei auf
    // dem Hauptthread wuerde die App fuer diese ganze Zeit einfrieren, siehe
    // Backup.java). Blockiert den "Sichern…"/"Wiederherstellen…"-Bereich
    // waehrenddessen gegen Doppel-Ausloesen.
    private static volatile boolean backupRunning = false;

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_BACKUP_EXPORT) {
            Uri uri = data.getData();
            backupRunning = true;
            rebuild();
            Handler main = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                boolean ok = true;
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    Backup.exportZip(this, out);
                } catch (Exception e) {
                    ok = false;
                }
                boolean finalOk = ok;
                main.post(() -> {
                    backupRunning = false;
                    android.widget.Toast.makeText(this,
                            finalOk ? "Gesichert." : "Sicherung fehlgeschlagen.",
                            android.widget.Toast.LENGTH_SHORT).show();
                    rebuild();
                });
            }, "SucherBackupExport").start();
        } else if (req == REQ_BACKUP_IMPORT) {
            Uri uri = data.getData();
            backupRunning = true;
            rebuild();
            Handler main = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                boolean ok;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    ok = in != null && Backup.importZip(this, in);
                } catch (Exception e) {
                    ok = false;
                }
                boolean finalOk = ok;
                main.post(() -> {
                    backupRunning = false;
                    android.widget.Toast.makeText(this,
                            finalOk ? "Wiederhergestellt." : "Datei nicht lesbar oder kein gültiges Sucher-Sicherungsformat.",
                            android.widget.Toast.LENGTH_LONG).show();
                    rebuild();
                });
            }, "SucherBackupImport").start();
        }
    }

    // ---------- Einstellungen (Ordnerauswahl, Comic-Metadaten, Index) ----------

    private void buildSettings(LinearLayout root, int d) {
        TextView intro = new TextView(this);
        intro.setText("Durchsucht Dateinamen UND -inhalte (Text, Office, PDF, "
                + "E-Books, Comic-Metadaten) sowie Kontakte/Termine. Erst muss "
                + "der Zugriff erlaubt und mindestens ein Ordner gewählt werden.");
        intro.setTextColor(Color.GRAY);
        intro.setTextSize(12 * fs);
        intro.setPadding(0, 10 * d, 0, 10 * d);
        root.addView(intro);

        if (!hasStoragePermission()) { root.addView(storagePermissionHint(d)); return; }
        if (!hasPimPermission()) root.addView(pimPermissionHint(d));
        if (!hasNotifPermission()) root.addView(notifPermissionHint(d));
        else buildNotifSourcesToggle(root, d);

        section(root, "Schriftgröße", d);
        buildFontScaleRow(root, d);

        section(root, "Durchsuchte Ordner", d);
        List<String> folders = new ArrayList<>(Settings.searchFolders(this));
        java.util.Collections.sort(folders);
        for (String folder : folders) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(folder);
            t.setTextColor(Color.WHITE);
            t.setTextSize(13 * fs);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);
            TextView del = new TextView(this);
            del.setText("✕");
            del.setTextColor(Color.parseColor("#E06666"));
            del.setTextSize(16 * fs);
            del.setPadding(12 * d, 4 * d, 4 * d, 4 * d);
            del.setOnClickListener(v -> {
                Settings.removeSearchFolder(this, folder);
                IndexJobService.ensureScheduled(this);
                rebuild();
            });
            row.addView(del);
            root.addView(row);

            // Voller Inhalt nur, wenn hier extra angehakt (Standard aus) -
            // Mathias' ~22000 Buecher waeren sonst ein zweistelliger
            // GB-Index; wer nur ein paar hundert Dokumente hat, hakt es
            // einfach an. Name/Titel/Autor/Serie/Datum gelten fuer diesen
            // Ordner immer, unabhaengig davon.
            CheckBox contentCb = new CheckBox(this);
            contentCb.setText("  Inhalt durchsuchbar machen (mehr Speicher, dauert länger)");
            contentCb.setTextColor(Color.parseColor("#B0B0B5"));
            contentCb.setTextSize(11.5f * fs);
            contentCb.setPadding(0, 0, 0, 6 * d);
            contentCb.setChecked(Settings.contentIndexingEnabled(this, folder));
            contentCb.setOnCheckedChangeListener((v, on) -> Settings.setContentIndexingEnabled(this, folder, on));
            root.addView(contentCb);
        }
        if (folders.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Noch keine Ordner gewählt.");
            none.setTextColor(Color.parseColor("#9E9E9E"));
            none.setTextSize(13 * fs);
            root.addView(none);
        }

        if (folderPicking) {
            root.addView(folderBrowser(d));
        } else {
            // Ein-Tipp: den gesamten internen Speicher (/storage/emulated/0)
            // hinzufuegen, ohne sich durch den Ordner-Browser klicken zu
            // muessen - deckt den haeufigsten Fall ("alles durchsuchen") direkt
            // ab und umgeht, dass die oberste Ebene im Browser nicht als "diesen
            // Ordner nehmen" waehlbar war.
            String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (!Settings.searchFolders(this).contains(internal)) {
                Button addInternal = new Button(this);
                addInternal.setText("Internen Speicher durchsuchen");
                addInternal.setOnClickListener(v -> {
                    Settings.addSearchFolder(this, internal);
                    IndexJobService.ensureScheduled(this);
                    folderPicking = false;
                    rebuild();
                });
                root.addView(addInternal);
            }

            Button add = new Button(this);
            add.setText("+ Anderen Ordner hinzufügen");
            add.setOnClickListener(v -> {
                folderPicking = true;
                if (browsePath == null) browsePath = Environment.getExternalStorageDirectory().getAbsolutePath();
                rebuild();
            });
            root.addView(add);
        }

        section(root, "Comic-Metadaten (CBZ)", d);
        CheckBox comicCb = new CheckBox(this);
        comicCb.setText("  ComicInfo.xml mit indizieren (Serie, Titel, Zusammenfassung)");
        comicCb.setTextColor(Color.WHITE);
        comicCb.setTextSize(13 * fs);
        comicCb.setChecked(Settings.searchComicsMeta(this));
        comicCb.setOnCheckedChangeListener((v, on) -> Settings.setSearchComicsMeta(this, on));
        root.addView(comicCb);

        section(root, "Archive durchsuchen (ZIP/7z/TAR)", d);
        CheckBox arcCb = new CheckBox(this);
        arcCb.setText("  Dokumente IN Archiven mitindizieren (teuer)");
        arcCb.setTextColor(Color.WHITE);
        arcCb.setTextSize(13 * fs);
        arcCb.setChecked(Settings.indexArchives(this));
        arcCb.setOnCheckedChangeListener((v, on) -> Settings.setIndexArchives(this, on));
        root.addView(arcCb);
        TextView arcHint = new TextView(this);
        arcHint.setText("An: Der Volltext eines Archivs umfasst auch die Texte der Dokumente darin "
                + "(PDF, Office, E-Books …) – so findest du ein Archiv über seinen Inhalt. "
                + "Unterstützt: ZIP, 7z, TAR (auch .gz/.bz2/.xz) und einzeln komprimierte "
                + "Dateien (z. B. bericht.pdf.gz). RAR nur dem Namen nach (es gibt keinen "
                + "freien, GPL-kompatiblen RAR-Entpacker) – RAR-Inhalte findest du, indem du "
                + "das Archiv einmal als ZIP oder 7z neu packst. Kostet beim Indizieren "
                + "spürbar mehr Zeit, weil "
                + "jedes enthaltene Dokument entpackt und einzeln ausgelesen wird. Nur in Ordnern "
                + "mit „Inhalt durchsuchbar“.");
        arcHint.setTextColor(Color.parseColor("#8899AA"));
        arcHint.setTextSize(11.5f * fs);
        arcHint.setPadding(0, 0, 0, 4 * d);
        root.addView(arcHint);

        section(root, "Vorschaubilder", d);
        CheckBox thumbCb = new CheckBox(this);
        thumbCb.setText("  Dauerhaft speichern (überleben „Cache leeren“ / SD Maid)");
        thumbCb.setTextColor(Color.WHITE);
        thumbCb.setTextSize(13 * fs);
        thumbCb.setChecked(Settings.thumbsPersistent(this));
        thumbCb.setOnCheckedChangeListener((v, on) -> {
            Settings.setThumbsPersistent(this, on);
            // Ablageort der Vorschaubilder (docextract.Thumbnails) sofort umsetzen.
            SucherApp.applyThumbStorage(this);
        });
        root.addView(thumbCb);
        TextView thumbHint = new TextView(this);
        thumbHint.setText("An: Vorschaubilder liegen im app-internen Speicher und bleiben erhalten "
                + "(zählen als App-Daten). Aus: sie liegen im Cache und dürfen bei Speichernot "
                + "geräumt werden (werden dann bei Bedarf neu erzeugt). Nicht in der Datenbank – "
                + "die Sicherung bleibt schlank.");
        thumbHint.setTextColor(Color.parseColor("#8899AA"));
        thumbHint.setTextSize(11.5f * fs);
        thumbHint.setPadding(0, 0, 0, 4 * d);
        root.addView(thumbHint);

        section(root, "Index", d);
        boolean running = SearchIndexer.isRunning();
        SearchStore idxStore = SearchStore.get(this);
        int count = idxStore.indexedCount();
        long last = Settings.searchLastRun(this);
        TextView status = new TextView(this);
        status.setText(last == 0 ? (count + " Dateien indiziert.")
                : count + " Dateien indiziert – zuletzt "
                  + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        status.setTextColor(Color.GRAY);
        status.setTextSize(12 * fs);
        status.setPadding(0, 0, 0, 2 * d);
        root.addView(status);

        // Fortschritt fuer die eigentlich teure Arbeit: "count" oben zaehlt
        // nur bekannte Dateien (bewegt sich kaum, sobald der Baum einmal
        // durchlaufen ist) - wie viel davon schon VOLLTEXT hat, ist die Zahl,
        // die sich waehrend eines (Nachhol-)Laufs wirklich veraendert. Ohne
        // das sah ein aktiver, aber langsamer Lauf wie Stillstand aus
        // (Mathias: "das ändert nichts an den Anzeigen").
        int contentDone = idxStore.contentIndexedCount();
        int contentTotal = idxStore.contentEligibleCount();
        TextView contentStatus = new TextView(this);
        String pct = contentTotal > 0 ? " (" + Math.round(100f * contentDone / contentTotal) + " %)" : "";
        contentStatus.setText("davon " + contentDone + " von " + contentTotal
                + " infrage kommenden Dateien mit Volltext" + pct);
        contentStatus.setTextColor(Color.parseColor("#8899AA"));
        contentStatus.setTextSize(12 * fs);
        contentStatus.setPadding(0, 0, 0, 6 * d);
        root.addView(contentStatus);

        if (running) {
            String ph = SearchIndexer.phase == 1 ? "Phase 1/3: Dateien erfassen (Namen)"
                    : SearchIndexer.phase == 2 ? "Phase 2/3: Inhalt/Titel erfassen"
                    : SearchIndexer.phase == 3 ? "Phase 3/3: Titelbilder" : "Läuft";
            TextView live = new TextView(this);
            live.setText(ph + " – " + SearchIndexer.scanned + " geprüft, "
                    + SearchIndexer.contentIndexed + " mit neuem Volltext, "
                    + SearchIndexer.thumbsMade + " Titelbilder");
            live.setTextColor(Color.parseColor("#2E9BE6"));
            live.setTextSize(12 * fs);
            live.setPadding(0, 0, 0, 2 * d);
            root.addView(live);

            TextView currentFile = new TextView(this);
            currentFile.setText("Gerade dran: " + SearchIndexer.currentPath);
            currentFile.setTextColor(Color.parseColor("#8899AA"));
            currentFile.setTextSize(11 * fs);
            currentFile.setPadding(0, 0, 0, 6 * d);
            root.addView(currentFile);
        }

        Button reindex = new Button(this);
        if (running) {
            // Ein gestoppter Lauf hinterlaesst dank mtime-Abgleich nie einen
            // kaputten Zwischenstand (siehe SearchIndexer) - "Jetzt neu
            // indizieren" nach dem Stopp macht faktisch ein "Fortsetzen":
            // bereits erfasste, unveraenderte Dateien werden uebersprungen.
            reindex.setText("Stoppen (" + SearchIndexer.scanned + " geprüft)");
            reindex.setEnabled(true);
            reindex.setOnClickListener(v -> { SearchIndexer.requestStop(); rebuild(); });
        } else {
            reindex.setText("Jetzt neu indizieren");
            reindex.setEnabled(!folders.isEmpty());
            reindex.setOnClickListener(v -> {
                PdfExtractorHelper.init(getApplicationContext());
                SearchIndexer.start(this, this::rebuild);
                rebuild();
            });
        }
        root.addView(reindex);

        CheckBox autoCb = new CheckBox(this);
        autoCb.setText("  Automatisch alle paar Stunden im Hintergrund aktualisieren");
        autoCb.setTextColor(Color.WHITE);
        autoCb.setTextSize(13 * fs);
        autoCb.setChecked(Settings.autoReindex(this));
        autoCb.setOnCheckedChangeListener((v, on) -> {
            Settings.setAutoReindex(this, on);
            IndexJobService.ensureScheduled(this);
        });
        root.addView(autoCb);

        // Harter Not-Aus: beendet den kompletten Sucher-Prozess sofort. Anders
        // als das kooperative "Stoppen" (das nur ein Flag setzt, das ein in
        // einer Format-Bibliothek festhaengender Indizierer-Thread nie
        // pruefen kann) wirkt das Beenden des Prozesses IMMER - auch bei einem
        // solchen Haenger. Android startet den Prozess danach hoechstens
        // leerlaufend als Benachrichtigungs-Listener neu, nicht den Indizierer.
        Button quit = new Button(this);
        quit.setText("Sucher beenden (Indizierung sofort stoppen)");
        quit.setOnClickListener(v -> {
            SearchIndexer.requestStop();
            finishAffinity();
            Process.killProcess(Process.myPid());
        });
        root.addView(quit);

        section(root, "Sicherung", d);
        TextView backupDesc = new TextView(this);
        backupDesc.setText("Einstellungen UND den kompletten Suchindex (alle bereits erfassten Volltexte) als Datei sichern oder aus einer solchen Datei wiederherstellen (ersetzt dabei den kompletten aktuellen Stand).");
        backupDesc.setTextColor(Color.parseColor("#8899AA"));
        backupDesc.setTextSize(12 * fs);
        backupDesc.setPadding(0, 0, 0, 8 * d);
        root.addView(backupDesc);
        if (backupRunning) {
            TextView backupRunningLabel = new TextView(this);
            backupRunningLabel.setText("Läuft noch … (bei einem großen Index kann das etwas dauern)");
            backupRunningLabel.setTextColor(Color.parseColor("#2E9BE6"));
            backupRunningLabel.setTextSize(12 * fs);
            backupRunningLabel.setPadding(0, 0, 0, 8 * d);
            root.addView(backupRunningLabel);
        }
        LinearLayout backupRow = new LinearLayout(this);
        backupRow.setOrientation(LinearLayout.HORIZONTAL);
        Button exportBtn = new Button(this);
        exportBtn.setText("Sichern…");
        exportBtn.setEnabled(!backupRunning);
        exportBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "sucher-sicherung.zip");
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, DASIS_FOLDER);
            startActivityForResult(i, REQ_BACKUP_EXPORT);
        });
        backupRow.addView(exportBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button importBtn = new Button(this);
        importBtn.setText("Wiederherstellen…");
        importBtn.setEnabled(!backupRunning);
        importBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, DASIS_FOLDER);
            startActivityForResult(i, REQ_BACKUP_IMPORT);
        });
        backupRow.addView(importBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(backupRow);

        buildDiagnosticsSection(root, d);
        buildAboutSection(root, d);
    }

    // ---------- Diagnose (problematische Dateien + Protokoll) ----------

    private void buildDiagnosticsSection(LinearLayout root, int d) {
        java.util.List<String> skipped = new ArrayList<>(Settings.skipContentPaths(this));
        java.util.Collections.sort(skipped);
        if (!skipped.isEmpty()) {
            section(root, "Problematische Dateien", d);
            TextView hint = new TextView(this);
            hint.setText("Diese Datei(en) haben den Indizierer zum Hängen gebracht und werden "
                    + "jetzt nur noch über den Namen erfasst (kein Inhalt/Titelbild). Über ✕ "
                    + "wieder freigeben, falls du es erneut versuchen willst.");
            hint.setTextColor(Color.parseColor("#8899AA"));
            hint.setTextSize(12 * fs);
            hint.setPadding(0, 0, 0, 6 * d);
            root.addView(hint);
            for (String path : skipped) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView t = new TextView(this);
                t.setText(path);
                t.setTextColor(Color.parseColor("#E0B0B0"));
                t.setTextSize(12 * fs);
                t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(t);
                TextView del = new TextView(this);
                del.setText("✕");
                del.setTextColor(Color.parseColor("#E06666"));
                del.setTextSize(16 * fs);
                del.setPadding(12 * d, 4 * d, 4 * d, 4 * d);
                del.setOnClickListener(v -> { Settings.removeSkipContent(this, path); rebuild(); });
                row.addView(del);
                root.addView(row);
            }
        }

        section(root, "Diagnose-Protokoll", d);
        TextView diagHint = new TextView(this);
        diagHint.setText("Was der Indizierer zuletzt getan hat und an welchen Dateien er sich "
                + "verschluckt hat – hilft bei der Fehlersuche.");
        diagHint.setTextColor(Color.parseColor("#8899AA"));
        diagHint.setTextSize(12 * fs);
        diagHint.setPadding(0, 0, 0, 6 * d);
        root.addView(diagHint);
        String diag = DiagLog.read(this);
        if (diag == null || diag.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("(noch leer)");
            none.setTextColor(Color.GRAY);
            none.setTextSize(12 * fs);
            root.addView(none);
        } else {
            TextView log = new TextView(this);
            log.setText(diag);
            log.setTextColor(Color.parseColor("#CCCCCC"));
            log.setTextSize(11 * fs);
            log.setTypeface(android.graphics.Typeface.MONOSPACE);
            log.setTextIsSelectable(true);
            log.setPadding(0, 6 * d, 0, 6 * d);
            root.addView(log);
            Button clear = new Button(this);
            clear.setText("Protokoll löschen");
            clear.setOnClickListener(v -> { DiagLog.clear(this); rebuild(); });
            root.addView(clear);
        }
    }

    // ---------- Über Sucher (Version, Lizenz, Änderungsprotokoll) ----------

    private static boolean changelogOpen = false;

    private void buildAboutSection(LinearLayout root, int d) {
        section(root, "Über Sucher", d);
        String vn;
        try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { vn = "?"; }
        TextView ver = new TextView(this);
        ver.setText("Sucher " + vn);
        ver.setTextColor(Color.parseColor("#2E9BE6"));
        ver.setTextSize(15 * fs);
        ver.setPadding(0, 0, 0, 8 * d);
        root.addView(ver);

        TextView desc = new TextView(this);
        desc.setText("Eigenständige Volltextsuche über Dateien (Text, Office, PDF, "
                + "E-Books, Comic-Metadaten), Kontakte, Termine und mitgeschnittene "
                + "Benachrichtigungen. Ursprünglich als Karte in EdgeTab entstanden.");
        desc.setTextColor(Color.parseColor("#CCCCCC"));
        desc.setTextSize(13 * fs);
        root.addView(desc);

        TextView licTitle = new TextView(this);
        licTitle.setText("Lizenz");
        licTitle.setTextColor(Color.parseColor("#2E9BE6"));
        licTitle.setTextSize(13 * fs);
        licTitle.setPadding(0, 14 * d, 0, 4 * d);
        root.addView(licTitle);

        TextView lic = new TextView(this);
        lic.setText("GNU General Public License v3 (oder später). Copyright (c) 2026 "
                + "Mathias Herbers. Vollständiger Lizenztext: LICENSE im Quellcode-"
                + "Repository.\n\n"
                + "Enthält Drittanbieter-Bibliotheken unter jeweils eigener "
                + "Open-Source-Lizenz (mit der GPLv3 kombinierbar): Apache POI, "
                + "PDFBox-Android, Apache Commons (Collections, Compress, IO, Math), "
                + "Apache Log4j API und SparseBitSet (alle Apache License 2.0), sowie "
                + "curvesapi (BSD-Lizenz). Volle Lizenztexte liegen den jeweiligen "
                + "Bibliotheks-Dateien bei.");
        lic.setTextColor(Color.parseColor("#9E9E9E"));
        lic.setTextSize(12 * fs);
        root.addView(lic);

        TextView clTitle = new TextView(this);
        clTitle.setText("Änderungsprotokoll");
        clTitle.setTextColor(Color.parseColor("#2E9BE6"));
        clTitle.setTextSize(13 * fs);
        clTitle.setPadding(0, 14 * d, 0, 4 * d);
        root.addView(clTitle);

        Button toggle = new Button(this);
        toggle.setText(changelogOpen ? "Änderungsprotokoll ausblenden" : "Änderungsprotokoll anzeigen");
        toggle.setOnClickListener(v -> { changelogOpen = !changelogOpen; rebuild(); });
        root.addView(toggle);

        if (changelogOpen) {
            String md = readAsset("CHANGELOG.md");
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, 8 * d, 0, 0);
            if (md == null) {
                TextView err = new TextView(this);
                err.setText("Änderungsprotokoll konnte nicht geladen werden.");
                err.setTextColor(Color.parseColor("#FFB0B0"));
                err.setTextSize(12 * fs);
                box.addView(err);
            } else {
                renderMarkdown(box, md, d);
            }
            root.addView(box);
        }
    }

    private String readAsset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    /** Sehr schlichter Markdown-Renderer - nur genug, um das eigene, immer
     *  gleich aufgebaute CHANGELOG.md lesbar darzustellen (Überschriften,
     *  Aufzählungspunkte, Absätze). Kein allgemeiner Markdown-Parser. */
    private void renderMarkdown(LinearLayout root, String md, int d) {
        for (String line : md.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            TextView tv = new TextView(this);
            if (t.startsWith("## ")) {
                tv.setText(t.substring(3));
                tv.setTextColor(Color.parseColor("#2E9BE6"));
                tv.setTextSize(14 * fs);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setPadding(0, 14 * d, 0, 4 * d);
            } else if (t.startsWith("# ")) {
                tv.setText(t.substring(2));
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16 * fs);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setPadding(0, 4 * d, 0, 6 * d);
            } else if (t.startsWith("- ")) {
                tv.setText("•  " + t.substring(2));
                tv.setTextColor(Color.parseColor("#CCCCCC"));
                tv.setTextSize(12 * fs);
                tv.setPadding(10 * d, 2 * d, 0, 2 * d);
            } else {
                tv.setText(t);
                tv.setTextColor(Color.parseColor("#9E9E9E"));
                tv.setTextSize(12 * fs);
                tv.setPadding(0, 2 * d, 0, 2 * d);
            }
            root.addView(tv);
        }
    }

    /** Alle Apps, die grundsaetzlich als Benachrichtigungsquelle in Frage
     *  kommen - nicht mehr nur die, die schon mal tatsaechlich etwas
     *  gemeldet haben (das zwang zum Abwarten, teils tagelang, bis ein
     *  seltener Kanal einmal etwas schickt, siehe Mathias). Vereinigung aus
     *  "hat ein Startsymbol" (deckt praktisch jede normale App ab) und
     *  bereits tatsaechlich beobachteten Absendern (deckt die wenigen Faelle
     *  ohne eigenes Startsymbol ab). Sortiert nach Anzeigename. */
    private List<String[]> allNotifyCapableApps() {
        // Startbare Apps (mit Label, ohne die eigene) aus der gemeinsamen
        // Bibliothek de.herbers.common.Apps; danach bereits beobachtete
        // Absender (auch ohne Startsymbol) ergaenzen.
        java.util.TreeMap<String, String[]> sorted = new java.util.TreeMap<>();
        int idx = 0;
        for (String[] row : de.herbers.common.Apps.launchable(this)) {
            sorted.put(row[1].toLowerCase(java.util.Locale.ROOT) + "\u001f" + (idx++), row);
        }
        for (String[] row : SearchStore.get(this).distinctNotifPackages()) {
            String pkg = row[0];
            boolean known = false;
            for (String[] v : sorted.values()) if (v[0].equals(pkg)) { known = true; break; }
            if (known) continue;
            String label = row[1] == null || row[1].isEmpty() ? pkg : row[1];
            sorted.put(label.toLowerCase(java.util.Locale.ROOT) + "\u001f" + (idx++), new String[]{pkg, label});
        }
        return new ArrayList<>(sorted.values());
    }

    /** Nur eine Kopfzeile mit Zusammenfassung + "Bearbeiten"/"Fertig" - die
     *  volle App-Liste (inzwischen praktisch alle installierten Apps, siehe
     *  allNotifyCapableApps) machte die Einstellungen-Seite unuebersichtlich
     *  (Mathias' Meldung), darum jetzt ein aufklappbares Untermenue statt
     *  einer immer sichtbaren Liste. */
    private void buildNotifSourcesToggle(LinearLayout root, int d) {
        section(root, "Benachrichtigungsquellen", d);
        int enabled = Settings.notifSources(this).size();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(enabled == 0 ? "Noch keine Quelle ausgewählt." : enabled + " Quelle(n) ausgewählt.");
        t.setTextColor(Color.GRAY);
        t.setTextSize(12 * fs);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        Button edit = new Button(this);
        edit.setText(notifSourcesOpen ? "Fertig" : "Bearbeiten");
        edit.setOnClickListener(v -> { notifSourcesOpen = !notifSourcesOpen; rebuild(); });
        row.addView(edit);
        root.addView(row);

        if (notifSourcesOpen) buildNotifSourcesSection(root, d);
    }

    /** Welche Apps' mitgeschnittene Benachrichtigungen durchsuchbar sind -
     *  NotificationCapture zeichnet technisch von jeder erlaubten App etwas
     *  auf, aber angezeigt/durchsucht wird nur, was hier ausdruecklich
     *  angehakt ist. */
    private void buildNotifSourcesSection(LinearLayout root, int d) {
        List<String[]> pkgs = allNotifyCapableApps();
        TextView hint = new TextView(this);
        hint.setText("Welche Apps' Benachrichtigungen durchsuchbar sein sollen "
                + "(nur was als Benachrichtigung durchkam, keine volle Historie):");
        hint.setTextColor(Color.GRAY);
        hint.setTextSize(12 * fs);
        hint.setPadding(0, 8 * d, 0, 6 * d);
        root.addView(hint);
        for (String[] row : pkgs) {
            String pkg = row[0];
            String label = row[1];
            CheckBox cb = new CheckBox(this);
            cb.setText("  " + label);
            cb.setTextColor(Color.WHITE);
            cb.setTextSize(13 * fs);
            cb.setChecked(Settings.isNotifSourceEnabled(this, pkg));
            cb.setOnCheckedChangeListener((v, on) -> Settings.setNotifSourceEnabled(this, pkg, on));
            root.addView(cb);
        }
    }

    /** -/+ wie EdgeTabs Registerkarten-Icongroesse - 80..150%, Schritt 10. */
    private void buildFontScaleRow(LinearLayout root, int d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView minus = new TextView(this);
        minus.setText("  −  ");
        minus.setTextColor(Color.parseColor("#2E9BE6"));
        minus.setTextSize(18 * fs);
        minus.setOnClickListener(v -> {
            Settings.setFontScale(this, Settings.fontScalePercent(this) - 10);
            rebuild();
        });
        row.addView(minus);

        TextView label = new TextView(this);
        label.setText("  " + Settings.fontScalePercent(this) + " %  ");
        label.setTextColor(Color.WHITE);
        label.setTextSize(13 * fs);
        row.addView(label);

        TextView plus = new TextView(this);
        plus.setText("  +  ");
        plus.setTextColor(Color.parseColor("#2E9BE6"));
        plus.setTextSize(18 * fs);
        plus.setOnClickListener(v -> {
            Settings.setFontScale(this, Settings.fontScalePercent(this) + 10);
            rebuild();
        });
        row.addView(plus);

        root.addView(row);
    }

    private void section(LinearLayout root, String title, int d) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.parseColor("#2E9BE6"));
        t.setTextSize(13 * fs);
        t.setPadding(0, 14 * d, 0, 4 * d);
        root.addView(t);
    }

    private View folderBrowser(int d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(8 * d, 8 * d, 8 * d, 8 * d);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF141416"));
        bg.setCornerRadius(8 * d);
        box.setBackground(bg);

        TextView path = new TextView(this);
        path.setText(browsePath);
        path.setTextColor(Color.parseColor("#2E9BE6"));
        path.setTextSize(12 * fs);
        box.addView(path);

        CheckBox contentCb = new CheckBox(this);
        contentCb.setText("  Inhalt gleich mit durchsuchbar machen (mehr Speicher, dauert länger)");
        contentCb.setTextColor(Color.parseColor("#B0B0B5"));
        contentCb.setTextSize(11.5f * fs);
        contentCb.setChecked(browseWantContent);
        contentCb.setOnCheckedChangeListener((v, on) -> browseWantContent = on);
        box.addView(contentCb);

        Button choose = new Button(this);
        choose.setText("Diesen Ordner hinzufügen");
        choose.setOnClickListener(v -> {
            Settings.addSearchFolder(this, browsePath);
            Settings.setContentIndexingEnabled(this, browsePath, browseWantContent);
            IndexJobService.ensureScheduled(this);
            folderPicking = false;
            browseWantContent = false;
            rebuild();
        });
        box.addView(choose);

        java.io.File dir = new java.io.File(browsePath);
        java.io.File parent = dir.getParentFile();
        if (parent != null) {
            TextView up = new TextView(this);
            up.setText("⬆ .. (nach oben)");
            up.setTextColor(Color.parseColor("#B0B0B5"));
            up.setTextSize(13 * fs);
            up.setPadding(0, 6 * d, 0, 6 * d);
            up.setOnClickListener(v -> { browsePath = parent.getAbsolutePath(); rebuild(); });
            box.addView(up);
        }

        java.io.File[] children = dir.listFiles(java.io.File::isDirectory);
        if (children != null) {
            java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (java.io.File c : children) {
                if (c.getName().startsWith(".")) continue;
                TextView row = new TextView(this);
                row.setText("📁 " + c.getName());
                row.setTextColor(Color.WHITE);
                row.setTextSize(13 * fs);
                row.setPadding(0, 6 * d, 0, 6 * d);
                row.setOnClickListener(v -> { browsePath = c.getAbsolutePath(); rebuild(); });
                box.addView(row);
            }
        }

        Button cancel = new Button(this);
        cancel.setText("Abbrechen");
        cancel.setOnClickListener(v -> { folderPicking = false; rebuild(); });
        box.addView(cancel);
        return box;
    }
}

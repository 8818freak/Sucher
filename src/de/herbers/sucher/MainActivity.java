package de.herbers.sucher;

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

    private static final int REQ_PIM = 501;

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
        field.setPadding(16 * d, 10 * d, 16 * d, 10 * d);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.topMargin = 10 * d;
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.setLayoutParams(flp);
        root.addView(field);

        // Suchverlauf - nur sichtbar, solange das Feld leer ist (kein
        // rebuild() noetig zum Ein-/Ausblenden waehrend der Eingabe, siehe
        // TextWatcher unten). Aufgenommen wird ein Begriff erst, wenn er ueber
        // die Sucher-Taste der Tastatur bestaetigt wird - jeder Tipp-Pause
        // (250ms-Entprellung) mitzuschreiben wuerde den Verlauf mit
        // Zwischenstaenden ("b", "bu", "buc"...) zumuellen.
        LinearLayout historyBox = buildHistoryBox(field, d);
        root.addView(historyBox);

        root.addView(categoryChips(d));

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

    /** Suchverlauf: eingeklappt sichtbar, solange das Suchfeld leer ist -
     *  antippen fuellt das Feld und stoesst ueber den bestehenden TextWatcher
     *  ganz normal eine Suche an. */
    private LinearLayout buildHistoryBox(EditText field, int d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        List<String> history = Settings.searchHistory(this);
        box.setVisibility(field.getText().length() == 0 && !history.isEmpty() ? View.VISIBLE : View.GONE);
        if (history.isEmpty()) return box;

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 10 * d, 0, 2 * d);
        TextView label = new TextView(this);
        label.setText("Letzte Suchen");
        label.setTextColor(Color.parseColor("#8899AA"));
        label.setTextSize(11 * fs);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label);
        TextView clearAll = new TextView(this);
        clearAll.setText("Verlauf löschen");
        clearAll.setTextColor(Color.parseColor("#E06666"));
        clearAll.setTextSize(11 * fs);
        clearAll.setOnClickListener(v -> { Settings.clearSearchHistory(this); rebuild(); });
        head.addView(clearAll);
        box.addView(head);

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
        return box;
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
        String[][] cats = {{"files", "Dateien"}, {"contacts", "Kontakte"},
                {"events", "Termine"}, {"notifs", "Nachrichten"}};
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
            android.widget.GridLayout grid = new android.widget.GridLayout(this);
            grid.setColumnCount(3);
            for (String ext : SearchStore.get(this).distinctExts()) {
                String key = ext.toLowerCase(java.util.Locale.ROOT);
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
                    if (on) advExts.remove(key); else advExts.add(key);
                    rebuild();
                });
                grid.addView(chip);
            }
            wrap.addView(grid);
        }
        return wrap;
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
        if (!hasStoragePermission()) { results.addView(storagePermissionHint(d)); return; }
        // "bis" schliesst den ganzen Tag ein, nicht nur 00:00 Uhr.
        long createdTo = advCreatedTo > 0 ? advCreatedTo + DateUtils.DAY_IN_MILLIS - 1 : 0;
        long modifiedTo = advModifiedTo > 0 ? advModifiedTo + DateUtils.DAY_IN_MILLIS - 1 : 0;
        List<SearchStore.FileHit> files = SearchStore.get(this).advancedSearch(
                advName, advAuthor, advTitle, advSeries, advExts,
                advCreatedFrom, createdTo, advModifiedFrom, modifiedTo, 100, scopePaths);
        if (files.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Keine Treffer.");
            none.setTextColor(Color.parseColor("#8899AA"));
            none.setTextSize(13 * fs);
            results.addView(none);
            return;
        }
        View[] highlightHolder = new View[1];
        results.addView(card("Dateien", files.size(), d, fileRows(files, d, highlightHolder), "files_adv"));
        results.addView(narrowRow(files, d));
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

    private void runSearch(String q, LinearLayout results, int d) {
        results.removeAllViews();
        if (q == null || q.trim().length() < 2) {
            TextView hint = new TextView(this);
            hint.setText("Mindestens 2 Zeichen eingeben.");
            hint.setTextColor(Color.parseColor("#8899AA"));
            hint.setTextSize(13 * fs);
            results.addView(hint);
            return;
        }
        if (!hasStoragePermission()) results.addView(storagePermissionHint(d));
        if (!hasPimPermission()) results.addView(pimPermissionHint(d));
        if (!hasNotifPermission()) results.addView(notifPermissionHint(d));

        List<SearchStore.FileHit> files = (hasStoragePermission() && !EXCLUDED_CATS.contains("files"))
                ? SearchStore.get(this).search(q, 60, scopePaths) : new ArrayList<>();
        List<ContactHit> contacts = EXCLUDED_CATS.contains("contacts") ? new ArrayList<>() : queryContacts(q);
        List<EventHit> events = EXCLUDED_CATS.contains("events") ? new ArrayList<>() : queryEvents(q);
        List<SearchStore.NotifHit> notifs = new ArrayList<>();
        if (hasNotifPermission() && !EXCLUDED_CATS.contains("notifs")) {
            java.util.Set<String> allowed = Settings.notifSources(this);
            for (SearchStore.NotifHit h : SearchStore.get(this).searchNotifications(q, 200)) {
                if (allowed.contains(h.pkg)) notifs.add(h);
                if (notifs.size() >= 60) break;
            }
        }

        if (files.isEmpty() && contacts.isEmpty() && events.isEmpty() && notifs.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Keine Treffer.");
            none.setTextColor(Color.parseColor("#8899AA"));
            none.setTextSize(13 * fs);
            results.addView(none);
            return;
        }

        // Die zuletzt geoeffnete Datei deutlich erkennbar machen und ins
        // Blickfeld scrollen, wenn man aus einer anderen App zurueckkommt
        // (Mathias' Wunsch) - dafuer muss ihre Karte ggf. aufgeklappt sein,
        // falls sie sonst hinter "Mehr anzeigen" versteckt waere.
        if (lastOpenedPath != null) {
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).path.equals(lastOpenedPath) && i >= PAGE) {
                    EXPANDED.add("files");
                    break;
                }
            }
        }
        View[] highlightHolder = new View[1];

        if (!files.isEmpty()) {
            results.addView(card("Dateien", files.size(), d, fileRows(files, d, highlightHolder), "files"));
            results.addView(narrowRow(files, d));
        }
        if (!contacts.isEmpty()) results.addView(card("Kontakte", contacts.size(), d, contactRows(contacts, d), "contacts"));
        if (!events.isEmpty()) results.addView(card("Termine", events.size(), d, eventRows(events, d), "events"));
        if (!notifs.isEmpty()) results.addView(card("Nachrichten", notifs.size(), d, notifRows(notifs, d), "notifs"));

        if (highlightHolder[0] != null) scrollToRow(highlightHolder[0]);
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

    /** Stabile, unterscheidbare Farbe je Schluessel (z.B. Dateiendung) -
     *  wie BaseTab.colorFor in EdgeTab, hier lokal statt geerbt. */
    private static int colorFor(String key) {
        int hue = Math.floorMod(key == null ? 0 : key.hashCode(), 360);
        return Color.HSVToColor(new float[]{hue, 0.5f, 0.85f});
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
        java.io.File thumb = Thumbnails.fileFor(this, h.path);
        if (thumb.isFile()) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(thumb.getAbsolutePath());
            if (bmp != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bmp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                int s = 44 * d;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
                lp.rightMargin = 12 * d;
                iv.setLayoutParams(lp);
                android.graphics.drawable.GradientDrawable clip = new android.graphics.drawable.GradientDrawable();
                clip.setCornerRadius(6 * d);
                iv.setClipToOutline(true);
                iv.setBackground(clip);
                iv.setClickable(true);
                iv.setOnClickListener(v -> openPreview(h));
                return iv;
            }
        }
        View icon = rowIcon(R.drawable.ic_file, colorFor(h.ext), d);
        icon.setClickable(true);
        icon.setOnClickListener(v -> openPreview(h));
        return icon;
    }

    private void openPreview(SearchStore.FileHit h) {
        Intent i = new Intent(this, PreviewActivity.class);
        i.putExtra(PreviewActivity.EXTRA_PATH, h.path);
        i.putExtra(PreviewActivity.EXTRA_EXT, h.ext);
        i.putExtra(PreviewActivity.EXTRA_CONTENT_OK, contentIndexingCoversPath(h.path));
        startActivity(i);
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
            Button add = new Button(this);
            add.setText("+ Ordner hinzufügen");
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
            TextView live = new TextView(this);
            live.setText("Läuft gerade: " + SearchIndexer.scanned + " geprüft, "
                    + SearchIndexer.contentIndexed + " davon mit neuem Volltext in diesem Durchlauf");
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
        reindex.setText(running ? "Indiziert… (" + SearchIndexer.scanned + ")" : "Jetzt neu indizieren");
        reindex.setEnabled(!running && !folders.isEmpty());
        reindex.setOnClickListener(v -> {
            PdfExtractorHelper.init(getApplicationContext());
            SearchIndexer.start(this, this::rebuild);
            rebuild();
        });
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

        buildAboutSection(root, d);
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
        PackageManager pm = getPackageManager();
        java.util.TreeMap<String, String[]> sorted = new java.util.TreeMap<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        int idx = 0;
        if (apps != null) for (android.content.pm.ResolveInfo ri : apps) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            String label;
            try { label = ri.loadLabel(pm).toString(); } catch (Throwable t) { label = pkg; }
            sorted.put(label.toLowerCase(java.util.Locale.ROOT) + "" + (idx++), new String[]{pkg, label});
        }
        for (String[] row : SearchStore.get(this).distinctNotifPackages()) {
            String pkg = row[0];
            boolean known = false;
            for (String[] v : sorted.values()) if (v[0].equals(pkg)) { known = true; break; }
            if (known) continue;
            String label = row[1] == null || row[1].isEmpty() ? pkg : row[1];
            sorted.put(label.toLowerCase(java.util.Locale.ROOT) + "" + (idx++), new String[]{pkg, label});
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

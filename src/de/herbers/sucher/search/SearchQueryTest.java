package de.herbers.sucher.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Eigenstaendiger Testlaeufer fuer den verbindlichen Testkorpus (Spez.
 * Abschnitt 14). Laeuft ohne Android mit `javac`/`java`.
 * Standalone test runner for the binding test corpus (spec section 14). Runs
 * without Android via `javac`/`java`.
 */
public final class SearchQueryTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        corpusA();
        corpusB();
        corpusC();
        integrationChecks();
        System.out.println();
        System.out.println("Ergebnis: " + passed + " ok, " + failed + " fehlgeschlagen.");
        if (failed > 0) System.exit(1);
    }

    // --- Optionen-Helfer ---------------------------------------------------

    private static SearchQuery.Options opt(boolean caseSensitive, boolean crossPara, boolean limitRange) {
        SearchQuery.Options o = new SearchQuery.Options();
        o.caseSensitive = caseSensitive;
        o.crossParagraph = crossPara;
        o.limitStarRange = limitRange;
        return o;
    }
    private static SearchQuery.Options std()        { return opt(false, false, true); }
    private static SearchQuery.Options caseOn()      { return opt(true, false, true); }
    private static SearchQuery.Options crossParaOn() { return opt(false, true, true); }

    // --- Testkorpus A: Dateiinhalt ----------------------------------------

    private static void corpusA() {
        System.out.println("== Testkorpus A: Dateiinhalt ==");
        Map<String, String> c = new LinkedHashMap<>();
        c.put("a", "rot");
        c.put("b", "Abendbrot");
        c.put("c", "Rotlicht");
        c.put("d", "rotes Licht");
        c.put("e", "Rotlichter");
        c.put("f", "rot gelb");
        c.put("g", "rot gelb blau");
        c.put("h", "gelb");
        c.put("i", "Schrott");
        c.put("j", "rot licht");
        c.put("k", "Abendrot licht");
        c.put("l", "rot, gelb");
        c.put("m", "rot und gelb");
        c.put("n", "gelb rot");
        c.put("o", "rot\ngelb");        // ein Absatz (einzelner Umbruch)
        c.put("p", "rot\n\ngelb");      // zwei Absaetze (Leerzeile)
        c.put("q", "rote Lichter");

        check(c, false, "rot", std(), "a f g j l m n o p");
        check(c, false, "*rot", std(), "a b f g j k l m n o p");
        check(c, false, "rot*", std(), "a c d e f g j l m n o p q");
        check(c, false, "*rot*", std(), "a b c d e f g i j k l m n o p q"); // alle ausser h
        check(c, false, "*Rot*", caseOn(), "c e");
        check(c, false, "\"rot*licht\"", std(), "c d j");
        check(c, false, "\"rot*licht*\"", std(), "c d e j q");
        check(c, false, "\"*rot*licht*\"", std(), "c d e j k q");
        check(c, false, "-\"rot*licht\"", std(), "a b e f g h i k l m n o p q");
        check(c, false, "rot* -licht", std(), "a c e f g l m n o p q");
        check(c, false, "-*rot gelb", std(), "h");
        check(c, false, "rot gelb", std(), "f g l o");
        check(c, false, "rot gelb", crossParaOn(), "f g l o p");
        check(c, false, "rot gelb -blau", std(), "f l o");
        check(c, false, "rot gelb *blau", std(), "g");
        check(c, false, "rot *licht", std(), "j");
        check(c, false, "\"rot\" \"gelb\"", std(), "f g l m n o p");
    }

    // --- Testkorpus B: Absatz und Reichweite ------------------------------

    private static void corpusB() {
        System.out.println("== Testkorpus B: Absatz und Reichweite ==");
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 300; i++) filler.append("x ");   // 600 Zeichen, keine Leerzeile
        Map<String, String> c = new LinkedHashMap<>();
        c.put("r", "rot" + filler + "licht");   // 600 Zeichen zwischen rot und licht
        c.put("s", "rot\n\nlicht");
        c.put("t", "rot\nlicht");

        check(c, false, "\"rot*licht\"", opt(false, false, true), "t");   // Absatz aus, Reichweite an
        check(c, false, "\"rot*licht\"", opt(false, false, false), "r t"); // Absatz aus, Reichweite aus
        check(c, false, "\"rot*licht\"", opt(false, true, true), "t");    // Absatz an, Reichweite an
        check(c, false, "\"rot licht\"", opt(false, false, true), "t");   // Absatz aus
        check(c, false, "\"rot licht\"", opt(false, true, true), "s t");  // Absatz an
    }

    // --- Testkorpus C: Namen ----------------------------------------------

    private static void corpusC() {
        System.out.println("== Testkorpus C: Namen ==");
        Map<String, String> c = new LinkedHashMap<>();
        for (String name : new String[]{
                "rot.txt", "Rot.txt", "Abendbrot.txt", "rot_gelb.txt",
                "rot-blau.txt", "Rotlicht.txt", "gelb.txt"}) {
            c.put(name, name);
        }
        check(c, true, "rot", std(), "rot.txt Rot.txt rot_gelb.txt rot-blau.txt");
        check(c, true, "rot", caseOn(), "rot.txt rot_gelb.txt rot-blau.txt");
        check(c, true, "rot gelb", std(), "rot_gelb.txt");
        check(c, true, "rot-blau", std(), "rot-blau.txt");
        check(c, true, "-rot", std(), "Abendbrot.txt Rotlicht.txt gelb.txt");
        check(c, true, "*rot*", std(), "rot.txt Rot.txt Abendbrot.txt rot_gelb.txt rot-blau.txt Rotlicht.txt");
    }

    // --- Integration: Kandidaten-Vorfilter + Schnipsel ---------------------

    private static void integrationChecks() {
        System.out.println("== Integration: ftsMatch / likeFragment / snippet ==");
        chk("rot gelb",      "ftsMatch", SearchQuery.parse("rot gelb").ftsMatch(), "rot* gelb*");
        chk("*rot",          "ftsMatch", SearchQuery.parse("*rot").ftsMatch(), null);
        chk("\"rot*licht\"", "ftsMatch", SearchQuery.parse("\"rot*licht\"").ftsMatch(), "rot* licht*");
        chk("*rot*",         "ftsMatch", SearchQuery.parse("*rot*").ftsMatch(), null);
        chk("*rot*licht*",   "likeFragment", SearchQuery.parse("*rot*licht*").likeFragment(), "licht");
        chk("rot gelb",      "likeFragment", SearchQuery.parse("rot gelb").likeFragment(), "gelb");
        chk("-nur",          "likeFragment", SearchQuery.parse("-nur").likeFragment(), null);
        String sn = SearchQuery.parse("licht").snippet("Hier ist ein rotes Licht im Raum.", 8);
        if (sn != null && sn.toLowerCase().contains("licht")) {
            passed++; System.out.println("  OK   snippet('licht') -> " + sn);
        } else {
            failed++; System.out.println("  FAIL snippet('licht') -> " + sn + "  (erwartet Ausschnitt mit 'licht')");
        }
    }

    private static void chk(String in, String what, String got, String exp) {
        boolean ok = (exp == null) ? (got == null) : exp.equals(got);
        if (ok) { passed++; System.out.println("  OK   " + what + "('" + in + "') -> " + got); }
        else { failed++; System.out.println("  FAIL " + what + "('" + in + "') -> " + got + "  (erwartet " + exp + ")"); }
    }

    // --- Pruef-Kern --------------------------------------------------------

    private static void check(Map<String, String> corpus, boolean isName,
                              String input, SearchQuery.Options o, String expected) {
        SearchQuery q = SearchQuery.parse(input);
        Set<String> got = new TreeSet<>();
        for (Map.Entry<String, String> e : corpus.entrySet()) {
            if (q.matches(e.getValue(), isName, o)) got.add(e.getKey());
        }
        Set<String> exp = new TreeSet<>();
        for (String s : expected.trim().split("\\s+")) if (!s.isEmpty()) exp.add(s);

        boolean ok = got.equals(exp);
        if (ok) {
            passed++;
            System.out.println("  OK   " + pad(input) + " -> " + join(got));
        } else {
            failed++;
            List<String> missing = new ArrayList<>(exp); missing.removeAll(got);
            List<String> extra = new ArrayList<>(got); extra.removeAll(exp);
            System.out.println("  FAIL " + pad(input));
            System.out.println("         erwartet: " + join(exp));
            System.out.println("         erhalten: " + join(got));
            if (!missing.isEmpty()) System.out.println("         fehlt:    " + String.join(" ", missing));
            if (!extra.isEmpty())   System.out.println("         zu viel:  " + String.join(" ", extra));
        }
    }

    private static String pad(String s) {
        String t = "'" + s + "'";
        while (t.length() < 22) t += " ";
        return t;
    }

    private static String join(Set<String> s) {
        return s.isEmpty() ? "(leer)" : String.join(" ", new LinkedHashSet<>(s));
    }
}

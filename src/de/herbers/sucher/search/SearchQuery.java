package de.herbers.sucher.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser und Matcher fuer die Suchsyntax (Martins Spezifikation v2).
 * Parser and matcher for the search syntax (Martin's specification v2).
 *
 * Reines Java ohne Android-Abhaengigkeiten, damit es gegen den verbindlichen
 * Testkorpus (Spez. Abschnitt 14) als eigenstaendiges Programm laufen kann und
 * zugleich in der App (SearchStore) nutzbar ist.
 * Pure Java with no Android dependencies, so it can run against the binding
 * test corpus (spec section 14) as a standalone program and still be used by
 * the app (SearchStore).
 *
 * Kernregeln / core rules:
 *  - Leerzeichen zwischen einfachen Woertern = feste Phrase (kein UND). Space
 *    between simple words = fixed phrase (not AND).
 *  - Mehrere Begriffe sind UND-verknuepft; kein ODER. Several terms are ANDed;
 *    no OR.
 *  - Wort ohne Stern = ganzes Wort (keine Teilzeichenfolge). Word without a
 *    star = whole word (not a substring).
 *  - `*` ohne Anfuehrungszeichen bleibt im Wort; in "..." ueberspringt er
 *    Wortgrenzen (und, begrenzt, Zeichen bis Absatzende). A `*` outside quotes
 *    stays inside a word; inside "..." it crosses word boundaries (and, when
 *    limited, characters up to the paragraph end).
 *  - Fuehrendes `-` schliesst einen Begriff aus. A leading `-` excludes a term.
 */
public final class SearchQuery {

    /** Laufzeit-Optionen aus dem Suchdialog. Runtime options from the dialog. */
    public static final class Options {
        public boolean caseSensitive = false;    // "Gross-/Kleinschreibung beachten"
        public boolean crossParagraph = false;    // "Ueber Absatz hinweg suchen"
        public boolean limitStarRange = true;     // "Reichweite des Sterns begrenzen"
        public int starRange = 500;                // Spez. O8: feste, aenderbare Konstante
    }

    private final List<Term> positives;
    private final List<Term> negatives;

    private SearchQuery(List<Term> positives, List<Term> negatives) {
        this.positives = positives;
        this.negatives = negatives;
    }

    public boolean isEmpty() {
        return positives.isEmpty() && negatives.isEmpty();
    }

    public boolean hasPositives() {
        return !positives.isEmpty();
    }

    // ---------------------------------------------------------------- matching

    /**
     * Prueft, ob der gegebene Text die Suche erfuellt. isName=true fuer Namen
     * (Ordner/Datei) ohne Absaetze; false fuer Dateiinhalt.
     * Tests whether the given text satisfies the query. isName=true for names
     * (folder/file) without paragraphs; false for file content.
     */
    public boolean matches(String rawText, boolean isName, Options o) {
        if (isEmpty()) return false;
        Ctx ctx = new Ctx(rawText, isName, o);
        for (Term t : positives) if (!t.matches(ctx)) return false;
        for (Term t : negatives) if (t.matches(ctx)) return false;
        return true;
    }

    // --------------------------------------------- Integration (SearchStore)

    /**
     * FTS4-MATCH-Vorfilter aus praefix-sicheren positiven Literalen; liefert ein
     * SUPERSET der echten Treffer (der Matcher filtert danach exakt). null, wenn
     * kein positiver Begriff praefix-sicher ist -> Aufrufer muss scannen.
     * Liefert ein FTS4-MATCH superset of the real hits; null -> caller must scan.
     */
    public String ftsMatch() {
        // ALLE verankerbaren Praefix-Tokens UND-verknuepfen (nicht nur das erste
        // je Begriff) -> engerer Kandidaten-Vorfilter, weniger Body-Scans. Jedes
        // Wort einer Phrase, das vorn verankert ist, muss vorkommen; kurze (<2)
        // Tokens weglassen (als FTS-Praefix wertlos). Bleibt ein Superset.
        java.util.LinkedHashSet<String> toks = new java.util.LinkedHashSet<>();
        for (Term t : positives) {
            if (t instanceof WordPhrase) {
                for (WordPattern p : ((WordPhrase) t).patterns)
                    if (!p.leadingStar && p.frags.get(0).length() >= 2) toks.add(p.frags.get(0));
            } else if (t instanceof QuotedPhrase) {
                for (List<String> seg : ((QuotedPhrase) t).segments)
                    for (String w : seg) if (w.length() >= 2) toks.add(w);
            } else {
                String tok = t.ftsPrefixToken();
                if (tok != null && tok.length() >= 2) toks.add(tok);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String tk : toks) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(tk).append('*');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * true, wenn der FTS-Vorfilter NICHT schon exakt ist und der Matcher den
     * vollen Text pruefen muss: Ausschluesse, Phrasen ueber mehrere Woerter,
     * Anfangs-/Innensterne, Nicht-Wort-Phrasen ("..."). false -> eine reine
     * FTS-Tokensuche liefert bereits das exakte Ergebnis (Schnellpfad, der die
     * kompletten Dateitexte NICHT laden muss).
     * true if the FTS prefilter is not already exact and the full text must be
     * scanned; false -> a plain FTS token search is already exact (fast path).
     */
    public boolean needsBodyScan() {
        if (positives.isEmpty()) return true;              // reine Ausschluss-Suche
        for (Term t : positives) if (t.ftsExpr() == null) return true;
        for (Term t : negatives) if (t.ftsExpr() == null) return true;
        return false;
    }

    /**
     * Exakte FTS4-MATCH-Anfrage aus den POSITIVEN Begriffen fuer den Schnellpfad
     * (nur gueltig, wenn needsBodyScan()==false): Einzelwort -> Token (tok* bei
     * Endstern), Wortfolge/Phrase -> "w1 w2 ...". Mehrere Begriffe implizit UND.
     * Ausschluesse NICHT enthalten (FTS-NOT ist auf Android unsicher) -> die
     * werden ueber ftsExcludeExprs() separat abgezogen.
     * Exact FTS4 MATCH query from the positive terms; excludes handled separately.
     */
    public String ftsExact() {
        StringBuilder sb = new StringBuilder();
        for (Term t : positives) {
            String e = t.ftsExpr();
            if (e == null) return null;
            if (sb.length() > 0) sb.append(' ');
            sb.append(e);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** FTS-Ausdruecke der Ausschluss-Begriffe (je ein eigener MATCH); ein
     *  Dokument wird ausgeschlossen, sobald es einen davon trifft. Leer, wenn
     *  keine Ausschluesse. Fuer den Schnellpfad (statt FTS-NOT). */
    public java.util.List<String> ftsExcludeExprs() {
        java.util.List<String> out = new ArrayList<>();
        for (Term t : negatives) {
            String e = t.ftsExpr();
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Literal-Fragment fuer einen LIKE-'%frag%'-Vorfilter (Namen); null = keins. */
    public String likeFragment() {
        String best = null;
        for (Term t : positives) {
            String f = t.longestLiteral();
            if (f != null && (best == null || f.length() > best.length())) best = f;
        }
        return best == null ? null : best.toLowerCase(java.util.Locale.ROOT);
    }

    /** Lesbarer Ausschnitt um die erste Fundstelle eines positiven Literals; null = keiner. */
    public String snippet(String rawText, int radius) {
        if (rawText == null) return null;
        String lit = null;
        for (Term t : positives) {
            String f = t.longestLiteral();
            if (f != null && (lit == null || f.length() > lit.length())) lit = f;
        }
        if (lit == null) return null;
        String norm = normalizeNewlines(rawText);
        String low = norm.toLowerCase(java.util.Locale.ROOT);
        int at = low.indexOf(lit.toLowerCase(java.util.Locale.ROOT));
        if (at < 0) return null;
        int from = Math.max(0, at - radius);
        int to = Math.min(norm.length(), at + lit.length() + radius);
        // ALLE positiven Suchwort-Literale im Fenster markieren (nicht nur das
        // laengste) -> bei Phrasen werden beide/alle Woerter hervorgehoben. Maske
        // der Treffer-Positionen, dann Steuerzeichen (U+0002/U+0003) an den Kanten.
        boolean[] hit = new boolean[to - from];
        for (Term t : positives) {
            String f = t.longestLiteral();
            if (f == null) continue;
            String lf = f.toLowerCase(java.util.Locale.ROOT);
            int i = low.indexOf(lf, from);
            while (i >= 0 && i < to) {
                int s0 = Math.max(from, i), e0 = Math.min(to, i + lf.length());
                for (int k = s0; k < e0; k++) hit[k - from] = true;
                i = low.indexOf(lf, i + 1);
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (int k = from; k < to; k++) {
            boolean h = hit[k - from];
            if (h && !in) { sb.append('\u0002'); in = true; }
            else if (!h && in) { sb.append('\u0003'); in = false; }
            sb.append(norm.charAt(k));
        }
        if (in) sb.append('\u0003');
        String s = sb.toString().replace('\n', ' ').replace('\r', ' ').trim();
        return (from > 0 ? "…" : "") + s + (to < norm.length() ? "…" : "");
    }

    // ----------------------------------------------------------------- parsing

    public static SearchQuery parse(String input) {
        List<Term> positives = new ArrayList<>();
        List<Term> negatives = new ArrayList<>();
        if (input == null) return new SearchQuery(positives, negatives);

        List<Raw> raws = tokenize(input);

        // Laufende Phrase aus einfachen (positiven, unquoted, sternlosen)
        // Begriffen. Running phrase of simple (positive, unquoted, star-free)
        // terms.
        List<WordPattern> run = new ArrayList<>();
        for (Raw r : raws) {
            boolean simple = !r.excluded && !r.quoted && !r.body.contains("*");
            if (simple) {
                List<WordPattern> pats = parseWordPatterns(r.body);
                if (pats.isEmpty()) continue;         // z.B. "-" / "---" -> ignorieren
                run.addAll(pats);
                continue;
            }
            // Nicht-einfacher Begriff beendet die laufende Phrase.
            flushRun(run, positives);
            if (r.quoted) {
                QuotedPhrase qp = QuotedPhrase.parse(r.body);
                if (qp == null) continue;             // "" / "*" -> ignorieren
                (r.excluded ? negatives : positives).add(qp);
            } else {
                List<WordPattern> pats = parseWordPatterns(r.body);
                if (pats.isEmpty()) continue;         // "*" allein -> ignorieren
                (r.excluded ? negatives : positives).add(new WordPhrase(pats));
            }
        }
        flushRun(run, positives);
        return new SearchQuery(positives, negatives);
    }

    private static void flushRun(List<WordPattern> run, List<Term> positives) {
        if (!run.isEmpty()) {
            positives.add(new WordPhrase(new ArrayList<>(run)));
            run.clear();
        }
    }

    /** Ein roher Begriff: ausgeschlossen?, in Anfuehrungszeichen?, Rumpf. */
    private static final class Raw {
        final boolean excluded;
        final boolean quoted;
        final String body;
        Raw(boolean excluded, boolean quoted, String body) {
            this.excluded = excluded; this.quoted = quoted; this.body = body;
        }
    }

    private static List<Raw> tokenize(String s) {
        List<Raw> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n) break;
            boolean excluded = false;
            if (s.charAt(i) == '-') { excluded = true; i++; }
            if (i < n && s.charAt(i) == '"') {
                i++;
                int start = i;
                while (i < n && s.charAt(i) != '"') i++;
                String body = s.substring(start, i);
                if (i < n) i++;                        // schliessendes " schlucken
                out.add(new Raw(excluded, true, body));
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(s.charAt(i))) i++;
                out.add(new Raw(excluded, false, s.substring(start, i)));
            }
        }
        return out;
    }

    /**
     * Zerlegt einen unquoted Begriffsrumpf in Wort-Muster. Trennzeichen (alles
     * ausser Buchstaben/Ziffern/`*`) trennen die Muster; ein Muster darf `*`
     * enthalten (innerhalb des Wortes).
     * Splits an unquoted term body into word patterns. Separators (anything
     * other than letters/digits/`*`) split the patterns; a pattern may contain
     * `*` (inside the word).
     */
    private static List<WordPattern> parseWordPatterns(String body) {
        List<WordPattern> out = new ArrayList<>();
        int i = 0, n = body.length();
        while (i < n) {
            char c = body.charAt(i);
            if (isWord(c) || c == '*') {
                int start = i;
                while (i < n && (isWord(body.charAt(i)) || body.charAt(i) == '*')) i++;
                WordPattern wp = WordPattern.parse(body.substring(start, i));
                if (wp != null) out.add(wp);
            } else {
                i++;
            }
        }
        return out;
    }

    static boolean isWord(char c) {
        return Character.isLetterOrDigit(c);
    }

    // --------------------------------------------------------------- the model

    /** Begriff: Phrase (unquoted) oder "..."-Phrase. */
    private interface Term {
        boolean matches(Ctx ctx);
        /** Praefix-sicheres FTS-Token (ohne Stern) fuer den Kandidaten-Vorfilter,
         *  oder null, wenn der Begriff nicht praefix-sicher ist. */
        String ftsPrefixToken();
        /** Laengstes Literal-Fragment des Begriffs (fuer LIKE/Schnipsel), oder null. */
        String longestLiteral();
        /** EXAKTER FTS4-MATCH-Ausdruck fuer diesen Begriff (ganzes Wort -> Token,
         *  Wort mit Endstern -> tok*, Wortfolge/Phrase -> "w1 w2 ..."), sodass das
         *  FTS-Ergebnis GENAU den Treffern entspricht - oder null, wenn FTS den
         *  Begriff nicht exakt ausdruecken kann (Anfangs-/Innenstern, Stern-Luecken,
         *  freie Phrasenraender -> dann Body-Scan noetig). Nutzt nur Phrase +
         *  Praefix + implizites UND (auf Android zuverlaessig), kein NOT/OR. */
        String ftsExpr();
    }

    /**
     * Ein Muster fuer EIN Wort, mit Sternen innerhalb des Wortes.
     * A pattern for a SINGLE word, with stars inside the word.
     * Beispiele: rot, *rot, rot*, *rot*, ro*t.
     */
    static final class WordPattern {
        final List<String> frags;      // Literal-Fragmente (roh, ungefaltet)
        final boolean leadingStar;
        final boolean trailingStar;

        private WordPattern(List<String> frags, boolean leadingStar, boolean trailingStar) {
            this.frags = frags; this.leadingStar = leadingStar; this.trailingStar = trailingStar;
        }

        /** run besteht aus Buchstaben/Ziffern und `*`. null, wenn kein Literal. */
        static WordPattern parse(String run) {
            boolean lead = run.startsWith("*");
            boolean trail = run.endsWith("*");
            List<String> frags = new ArrayList<>();
            for (String part : run.split("\\*+")) {
                if (!part.isEmpty()) frags.add(part);
            }
            if (frags.isEmpty()) return null;          // nur Sterne -> kein Muster
            return new WordPattern(frags, lead, trail);
        }

        /** word ist bereits gefaltet (gleiche Faltung wie der Text); die
         *  Fragmente werden hier mit derselben Faltung verglichen, sonst
         *  scheitert jede Suche mit Grossbuchstaben (Bugfix 2026-10-06). */
        boolean matchesWord(String word, Ctx ctx) {
            int n = frags.size();
            int pos = 0;
            for (int i = 0; i < n; i++) {
                String f = fold(frags.get(i), ctx);
                boolean first = (i == 0), last = (i == n - 1);
                if (first && !leadingStar) {
                    if (!word.startsWith(f)) return false;
                    pos = f.length();
                    if (last && !trailingStar && pos != word.length()) return false;
                } else if (last && !trailingStar) {
                    int start = word.length() - f.length();
                    if (start < pos) return false;
                    if (!word.regionMatches(start, f, 0, f.length())) return false;
                    pos = word.length();
                } else {
                    int at = word.indexOf(f, pos);
                    if (at < 0) return false;
                    pos = at + f.length();
                }
            }
            return true;
        }
    }

    /**
     * Unquoted-Phrase: Folge von Wort-Mustern, die auf aufeinanderfolgende
     * Woerter passen muessen. Ein einzelnes Muster = der Begriff kommt irgendwo
     * als ganzes (ggf. Stern-)Wort vor.
     */
    static final class WordPhrase implements Term {
        final List<WordPattern> patterns;
        WordPhrase(List<WordPattern> patterns) { this.patterns = patterns; }

        public String ftsPrefixToken() {
            WordPattern p = patterns.get(0);
            return p.leadingStar ? null : p.frags.get(0);
        }

        public String longestLiteral() {
            String best = null;
            for (WordPattern p : patterns)
                for (String f : p.frags)
                    if (best == null || f.length() > best.length()) best = f;
            return best;
        }

        public String ftsExpr() {
            int k = patterns.size();
            for (int i = 0; i < k; i++) {
                WordPattern p = patterns.get(i);
                if (p.leadingStar || p.frags.size() != 1) return null;  // Anfangs-/Innenstern
                if (p.trailingStar && k != 1) return null;              // Endstern nur Einzelwort
            }
            if (k == 1) {
                WordPattern p = patterns.get(0);
                return p.trailingStar ? p.frags.get(0) + "*" : p.frags.get(0);
            }
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < k; i++) {
                if (i > 0) sb.append(' ');
                sb.append(patterns.get(i).frags.get(0));
            }
            return sb.append('"').toString();
        }

        public boolean matches(Ctx ctx) {
            int k = patterns.size();
            List<int[]> words = ctx.words;
            if (k == 1) {
                WordPattern p = patterns.get(0);
                for (int[] w : words) {
                    if (p.matchesWord(ctx.text.substring(w[0], w[1]), ctx)) return true;
                }
                return false;
            }
            outer:
            for (int j = 0; j + k <= words.size(); j++) {
                for (int m = 0; m < k; m++) {
                    int[] w = words.get(j + m);
                    if (!patterns.get(m).matchesWord(ctx.text.substring(w[0], w[1]), ctx)) continue outer;
                    if (m > 0) {
                        int gapFrom = words.get(j + m - 1)[1];
                        int gapTo = w[0];
                        if (ctx.separatorBlocked(gapFrom, gapTo)) continue outer;
                    }
                }
                return true;
            }
            return false;
        }
    }

    /**
     * "..."-Phrase: Segmente (feste Wortfolgen) mit Stern-Luecken dazwischen,
     * plus optionaler fuehrender/abschliessender Stern (freie Raender).
     */
    static final class QuotedPhrase implements Term {
        final List<List<String>> segments;    // je Segment eine Liste fester Woerter
        final boolean leadingStar;
        final boolean trailingStar;

        private QuotedPhrase(List<List<String>> segments, boolean leadingStar, boolean trailingStar) {
            this.segments = segments; this.leadingStar = leadingStar; this.trailingStar = trailingStar;
        }

        public String ftsPrefixToken() {
            return leadingStar ? null : segments.get(0).get(0);
        }

        public String longestLiteral() {
            String best = null;
            for (List<String> seg : segments)
                for (String w : seg)
                    if (best == null || w.length() > best.length()) best = w;
            return best;
        }

        public String ftsExpr() {
            // Nur eine schlichte Phrase ohne freie Raender und ohne Stern-Luecken
            // ist in FTS exakt abbildbar; alles andere -> Body-Scan.
            if (leadingStar || trailingStar || segments.size() != 1) return null;
            StringBuilder sb = new StringBuilder("\"");
            List<String> seg = segments.get(0);
            for (int i = 0; i < seg.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(seg.get(i));
            }
            return sb.append('"').toString();
        }

        static QuotedPhrase parse(String body) {
            List<List<String>> segments = new ArrayList<>();
            List<String> current = new ArrayList<>();
            boolean leadingStar = false, trailingStar = false, sawWord = false;
            int i = 0, n = body.length();
            while (i < n) {
                char c = body.charAt(i);
                if (c == '*') {
                    while (i < n && body.charAt(i) == '*') i++;
                    if (!sawWord) {
                        leadingStar = true;
                    } else {
                        if (!current.isEmpty()) { segments.add(current); current = new ArrayList<>(); }
                        trailingStar = true;           // vorlaeufig; faellt weg, wenn ein Wort folgt
                    }
                } else if (isWord(c)) {
                    int s = i;
                    while (i < n && isWord(body.charAt(i))) i++;
                    current.add(body.substring(s, i));
                    sawWord = true;
                    trailingStar = false;
                } else {
                    i++;                               // Trenner innerhalb des Segments
                }
            }
            if (!current.isEmpty()) segments.add(current);
            if (segments.isEmpty()) return null;       // keine Woerter -> ignorieren
            return new QuotedPhrase(segments, leadingStar, trailingStar);
        }

        public boolean matches(Ctx ctx) {
            String first = fold(segments.get(0).get(0), ctx);
            int from = 0;
            while (true) {
                int idx = ctx.text.indexOf(first, from);
                if (idx < 0) return false;
                if (leadingStar || ctx.isWordStart(idx)) {
                    if (matchFrom(ctx, 0, idx)) return true;
                }
                from = idx + 1;
            }
        }

        /** Versucht, Segment segIdx exakt ab Position pos zu matchen und weiter. */
        private boolean matchFrom(Ctx ctx, int segIdx, int pos) {
            boolean leftAnchored = (segIdx == 0) && !leadingStar;
            boolean lastSeg = (segIdx == segments.size() - 1);
            boolean rightAnchored = lastSeg && !trailingStar;
            int end = matchSegmentAt(ctx, segments.get(segIdx), pos, leftAnchored, rightAnchored);
            if (end < 0) return false;
            if (lastSeg) return true;

            // Stern-Luecke zum naechsten Segment: ueberspringt Wortgrenzen, aber
            // nie eine Absatzgrenze; begrenzt auf starRange, wenn aktiv.
            String next = fold(segments.get(segIdx + 1).get(0), ctx);
            int maxEnd = (!ctx.isName && ctx.o.limitStarRange)
                    ? Math.min(ctx.len, end + ctx.o.starRange) : ctx.len;
            int from = end;
            while (true) {
                int idx = ctx.text.indexOf(next, from);
                if (idx < 0 || idx > maxEnd) return false;
                if (!ctx.isName && ctx.starGapCrossesParagraph(end, idx)) return false; // Stern endet am Absatz
                if (matchFrom(ctx, segIdx + 1, idx)) return true;
                from = idx + 1;
            }
        }

        /** Matcht ein Segment (feste Wortfolge) mit erstem Wort genau bei pos. */
        private int matchSegmentAt(Ctx ctx, List<String> rawWords, int pos,
                                   boolean leftAnchored, boolean rightAnchored) {
            int m = rawWords.size();
            String w0 = fold(rawWords.get(0), ctx);
            if (!ctx.text.startsWith(w0, pos)) return -1;
            if (leftAnchored && !ctx.isWordStart(pos)) return -1;
            int cur = pos + w0.length();
            if (m == 1) {
                if (rightAnchored && !ctx.isWordEnd(cur)) return -1;
                return cur;
            }
            if (!ctx.isWordEnd(cur)) return -1;        // erstes Wort ganz
            for (int k = 1; k < m; k++) {
                int sepStart = cur;
                int q = cur;
                while (q < ctx.len && !isWord(ctx.text.charAt(q))) q++;
                if (q >= ctx.len) return -1;
                if (ctx.separatorBlocked(sepStart, q)) return -1;
                String wk = fold(rawWords.get(k), ctx);
                if (!ctx.text.startsWith(wk, q)) return -1;
                int e = q + wk.length();
                boolean last = (k == m - 1);
                if (last) {
                    if (rightAnchored && !ctx.isWordEnd(e)) return -1;
                } else if (!ctx.isWordEnd(e)) {
                    return -1;
                }
                cur = e;
            }
            return cur;
        }
    }

    private static String fold(String needle, Ctx ctx) {
        return ctx.o.caseSensitive ? needle : foldCase(needle);
    }

    // ----------------------------------------------------------- Match-Kontext

    /**
     * Haelt den (normalisierten, ggf. gefalteten) Text samt Wort- und
     * Absatzinformationen fuer einen Suchvorgang.
     */
    static final class Ctx {
        final String text;        // Zeilenenden normalisiert, bei Bedarf gefaltet
        final int len;
        final boolean isName;
        final Options o;
        final List<int[]> words;  // {start, end} je Wort

        Ctx(String raw, boolean isName, Options o) {
            String t = normalizeNewlines(raw);
            if (!o.caseSensitive) t = foldCase(t);
            this.text = t;
            this.len = t.length();
            this.isName = isName;
            this.o = o;
            this.words = indexWords(t);
        }

        boolean isWordStart(int p) {
            return p < len && isWord(text.charAt(p)) && (p == 0 || !isWord(text.charAt(p - 1)));
        }

        boolean isWordEnd(int p) {
            return p > 0 && isWord(text.charAt(p - 1)) && (p == len || !isWord(text.charAt(p)));
        }

        /** Trenner zwischen festen Phrasenwoertern: blockiert, wenn er eine
         *  Absatzgrenze enthaelt und "Ueber Absatz" aus ist (nur Inhalt). */
        boolean separatorBlocked(int from, int to) {
            if (isName || o.crossParagraph) return false;
            return containsBlankLine(from, to);
        }

        /** Stern-Luecke: ueberschreitet sie eine Absatzgrenze? (Stern nie.) */
        boolean starGapCrossesParagraph(int from, int to) {
            return containsBlankLine(from, to);
        }

        private boolean containsBlankLine(int from, int to) {
            for (int i = from; i < to; i++) {
                if (text.charAt(i) == '\n') {
                    int j = i + 1;
                    while (j < to && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) j++;
                    if (j < to && text.charAt(j) == '\n') return true;
                }
            }
            return false;
        }
    }

    private static List<int[]> indexWords(String t) {
        List<int[]> out = new ArrayList<>();
        int i = 0, n = t.length();
        while (i < n) {
            if (isWord(t.charAt(i))) {
                int s = i;
                while (i < n && isWord(t.charAt(i))) i++;
                out.add(new int[]{s, i});
            } else {
                i++;
            }
        }
        return out;
    }

    private static String normalizeNewlines(String s) {
        if (s == null) return "";
        // \r\n und \r auf \n vereinheitlichen (laengenveraendernd, aber intern
        // konsistent). Normalise \r\n and \r to \n.
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Laengenerhaltende Kleinschreibung (BMP) fuer stabile Positionen. */
    private static String foldCase(String s) {
        char[] a = s.toCharArray();
        for (int i = 0; i < a.length; i++) a[i] = Character.toLowerCase(a[i]);
        return new String(a);
    }
}

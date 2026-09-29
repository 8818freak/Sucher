# Changelog — Sucher

Alle nennenswerten Änderungen, neueste zuerst. Sucher wurde am 2026-09-24
als eigenständige App aus EdgeTabs kurzlebiger "Suche"-Karte (EdgeTab 0.60)
herausgelöst.

## 0.44
- Verbessert: Das Diagnose-Protokoll steht jetzt ganz unten in den
  Einstellungen, zeigt die neuesten Einträge zuerst, und alle Schaltflächen
  (Anzeigen-Schalter, „Protokoll löschen“) stehen darüber. Neuer Schalter
  „Protokoll anzeigen“ blendet das Protokoll bei Bedarf ganz aus.

## 0.43
- Neu: OpenOffice-/LibreOffice-Dokumente (ODT/ODS/ODP) werden jetzt gelesen –
  Titel/Autor und der Volltext, genau wie bei den Word-/Excel-Formaten.
- Neu: Archive durchsuchbar (neuer Schalter in den Einstellungen, Standard AUS,
  weil teuer). Ist er an, umfasst der Volltext eines Archivs auch die Texte der
  Dokumente darin – so findest du ein Archiv über seinen Inhalt. Unterstützt:
  ZIP, 7z, TAR (auch .gz/.bz2/.xz) und einzeln komprimierte Dateien (z. B.
  bericht.pdf.gz). RAR wird nur dem Namen nach gefunden – es gibt keinen freien,
  GPL-kompatiblen RAR-Entpacker; RAR-Inhalte findest du, indem du das Archiv
  einmal als ZIP oder 7z neu packst. Nur in Ordnern mit „Inhalt
  durchsuchbar“; verschachtelte Archive werden nicht rekursiv ausgepackt.
- Verbessert: Die Endungs-Vorschläge der erweiterten Suche kennen jetzt auch
  Musikformate (ogg, opus, wav, aac, wma …), Videoformate und die Archivtypen;
  neue Kategorie „Archive“.

## 0.40
- Neu: In der MOBI-Vorschau wird jetzt das Titelbild (Cover) ganz am Anfang
  angezeigt, falls das Buch eines enthält – vor dem Text.

## 0.39
- Behoben (kritisch): Nach dem 0.37-Umbau lief die Suche in einem einzelnen
  Hintergrund-Thread ohne Trefferbegrenzung. Eine sehr häufige Suche (z. B.
  „sex") band diesen Thread dauerhaft und blockierte danach *jede* weitere
  Suche – es kamen gar keine Ergebnisse mehr. Die Suche ist jetzt auf die
  ersten 500 Treffer begrenzt (mit Hinweis, wenn mehr vorhanden sind) und
  veraltete Anfragen werden früh verworfen.
- Verbessert: Die Vorschlagsliste der Dateiendungen (erweiterte Suche) ist
  jetzt nach Kategorien gruppiert (E-Books, Dokumente, Tabellen, Präsentationen,
  Comics, Bilder, Audio, Video, Web & Daten), innerhalb jeder Kategorie
  alphabetisch, jeweils mit kurzer Klartext-Erklärung.

## 0.38
- Behoben: In MOBI-Texten konnte an den internen Record-Grenzen (etwa alle
  4 KB) ein Wort verstümmelt werden mit ein paar Müllzeichen (z. B. „Neigung" →
  „Neig�ung"). Jeder Textabschnitt wird jetzt korrekt auf seine deklarierte
  Größe begrenzt.

## 0.37
- Behoben: Die App konnte beim Tippen einer häufigen Suche (z. B. „sex" mit
  zehntausenden Volltext-Treffern) einfrieren/„reagiert nicht" melden (ANR). Die
  Suche läuft jetzt im Hintergrund; die Oberfläche bleibt bedienbar, während
  „Suche läuft …" angezeigt wird. Veraltete Anfragen (beim Weitertippen) werden
  verworfen.

## 0.36
- Behoben: MOBI-Texte hatten falsche Sonderzeichen (Mojibake: „ü" wurde zu
  „Ã¼", „©" zu „Â©") – im Volltext-Index und in der Vorschau. Der Text wird
  jetzt mit dem im MOBI deklarierten Zeichensatz dekodiert (UTF-8 bzw.
  Windows-1252) statt fest als Latin-1.
- Behoben: MOBI-Titelbilder erschienen nie. Die Cover-Erkennung verließ sich auf
  ein MOBI-Kopf-Feld, das in vielen Dateien 0/unzuverlässig ist; jetzt wird der
  erste eingebettete Bild-Record gesucht (mit Fallback). MOBIs, die ein Cover
  enthalten, bekommen nun ein Titelbild (Dateien ohne eingebettetes Cover
  natürlich weiterhin nicht).
- Neu: Suche nach Dateiendung erweitert. In der erweiterten Suche unter
  „Dateiart" gibt es jetzt (a) eine freie Eingabe für beliebige Endungen (auch
  solche, die noch nicht im Index sind) und (b) eine Vorschlagsliste mit
  Klartext-Erklärung (epub = E-Book, txt = Textdokument, cbz = Comic-Archiv …).
  Mehrere Endungen lassen sich gleichzeitig auswählen (per UND mit den übrigen
  Suchfeldern kombiniert). Die bisherige Liste der tatsächlich vorhandenen
  Endungen bleibt als Überblick erhalten.

## 0.35
- Intern: Die Format-Extraktoren (PDF/EPUB/MOBI/AZW3/CBZ/altes Office → Text,
  Titel/Autor/Serie, Titelbilder) kommen jetzt aus der gemeinsamen Bibliothek
  herbers-android-docextract (Git-Submodul, de.herbers.docextract) statt aus
  eigenen Kopien – eine gepflegte Quelle, andere können sie ebenfalls nutzen.
  Der „Vorschaubilder dauerhaft speichern"-Schalter bleibt unverändert (der
  Ablageort wird der Bibliothek jetzt gesetzt). Keine sichtbare Änderung.

## 0.34
- Intern: Das Diagnose-Protokoll und die neue Stapel-Erfassung bei Hängern
  kommen jetzt aus der gemeinsamen Bibliothek herbers-android-common
  (de.herbers.common.DiagLog / Diagnostics) statt aus eigenem Code – dieselbe
  Diagnose wie künftig in EdgeTab und ActiveFrames.
- Neu: Unerwartete Abstürze werden mit vollem Stack ins Diagnose-Protokoll
  geschrieben (Absturz-Logger), damit sich auch seltene Fehler nachvollziehen
  lassen. Keine sichtbare Änderung im normalen Betrieb.

## 0.33
- Behoben: Der Indizierer konnte dauerhaft hängenbleiben und markierte dabei
  eine zufällige, unschuldige Datei als „problematisch" (was nichts half – beim
  nächsten Lauf hing es wieder). Ursache: Beim Aufräumen verwaister Einträge
  galten unveränderte Dateien fälschlich als gelöscht, was ein extrem teures
  Volltext-Massenlöschen auslöste (nur der Not-Abbruch nach 5 Minuten bewahrte
  den Index vorm Leeren). Unveränderte Dateien werden jetzt korrekt als „in
  diesem Lauf gesehen" markiert; das Aufräumen betrifft nur noch tatsächlich
  entfernte Dateien und läuft in Sekunden. Eine evtl. zuvor fälschlich als
  „problematisch" gelistete Datei kannst du über das ✕ wieder freigeben.
- Neu (Diagnose): Bleibt der Indizierer ohne Fortschritt stehen, hält das
  Diagnose-Protokoll jetzt den genauen Stapel (Stack) der hängenden Stelle
  fest – so ist die Ursache künftig sofort erkennbar.

## 0.32
- Intern: Die Aufzählung wählbarer Benachrichtigungsquellen (alle startbaren
  Apps) kommt jetzt aus der gemeinsamen Bibliothek herbers-android-common
  (de.herbers.common.Apps) statt aus eigenem Code – dieselbe Logik wie in
  EdgeTab. Keine sichtbare Änderung.

## 0.31
- Intern: Das Mitschneiden der Benachrichtigungen (Titel/Text-Auslesen inkl.
  BigText, Gruppen-/Leer-Filter, App-Name) nutzt jetzt den gemeinsamen Kern der
  Bibliothek herbers-android-common (de.herbers.common.Notifications) statt
  eigener Kopien – dieselbe, gepflegte Logik wie in EdgeTab. Keine sichtbare
  Änderung.

## 0.30
- Wiederherstellen der Einstellungen war defekt und funktioniert jetzt: Die
  Sicherung nutzt die gemeinsame Bibliothek herbers-android-common (Git-Submodul,
  de.herbers.common.SettingsBackup). Der alte Import erwartete ein anderes
  Feld-Layout als der Export erzeugte und stellte darum nichts wieder her.
  Auch colorFor kommt jetzt aus der gemeinsamen Bibliothek.

## 0.29
- Große Medien-Ordner (Fotos, Musik, Videos) werden jetzt zuverlässig
  mitindiziert. Vorher blieb der Indizierlauf im riesigen Bücher-Ordner hängen
  (teure Inhalts- und Titelbild-Erfassung je Datei) und erreichte Ordner wie
  „photos" NIE – eine Suche darin fand 0 Treffer, obwohl tausende passende
  Dateien existierten. Ursachen und Lösung:
  - Zweiphasige Indizierung: Phase 1 erfasst blitzschnell nur Metadaten
    (Name/Typ/Größe/Datum) für ALLE Dateien – so ist jede Datei sofort per
    Namenssuche auffindbar, auch die in großen Medien-Ordnern. Phase 2 holt
    danach Inhalt/Titel/Autor in Tranchen, Phase 3 die Titelbilder in Tranchen
    (Titelbilder entstehen außerdem bei Bedarf beim Anzeigen).
  - Ein teurer FTS-Komplettscan beim Schreiben jeder neuen Datei (Löschen einer
    evtl. alten Volltext-Zeile durchsuchte die komplette Volltext-Tabelle –
    Gigabytes) wird nur noch ausgeführt, wenn die Datei wirklich schon Volltext
    hatte. Das beschleunigt die Erfassung neuer Dateien um Größenordnungen.
  - Hintergrund-Indizierung wurde vom System teils nach Sekunden gestoppt und
    kam nie über die Bücher hinaus; durch die schnelle Phase 1 macht nun selbst
    ein kurzes Zeitfenster großen Fortschritt. Für einen sofortigen
    Vollabgleich einmal „Jetzt neu indizieren" ausführen.
  - Die Fortschrittsanzeige zeigt jetzt die aktuelle Phase.
- Ersetzte Dateien (gleicher Name) werden zuverlässig neu erfasst: Änderungen
  werden an der Änderungszeit ODER der Dateigröße erkannt (manche Ersetzungen
  behalten die alte Änderungszeit).
- Keine künstliche Trefferbegrenzung mehr (vorher 60 je Zweig – bei großen
  Sammlungen wurde vieles abgeschnitten). Die Ergebniskarte zeigt die volle
  Anzahl im Kopf und lädt die Zeilen schrittweise per „Mehr anzeigen" (in
  50er-Schritten, ohne Deckel) – so bleibt auch eine sehr große Trefferliste
  flüssig.
- Bild-Vorschauen erscheinen zügiger: Titelbilder werden beim Anzeigen jetzt
  mit mehreren Threads parallel erzeugt (vorher nur einer, dadurch blieben in
  Bildordnern lange nur die Datei-Symbole stehen).
- Neuer Schalter „Vorschaubilder dauerhaft speichern": An (Standard) legt sie im
  app-internen Speicher ab – sie überleben ein „Cache leeren" (System und
  Werkzeuge wie SD Maid) und werden nicht ständig neu erzeugt. Aus legt sie wie
  bisher im Cache ab (darf bei Speichernot geräumt werden). In beiden Fällen
  liegen sie NICHT in der Datenbank – die Sicherung bleibt schlank.

## 0.28
- Neu: In einem bestimmten Ordner (samt Unterordnern) suchen – Knopf
  „📁 In Ordner suchen…" über einen kleinen Ordner-Browser; ein blaues Banner
  zeigt die Einschränkung und hebt sie auf Wunsch wieder auf. Gilt für einfache
  UND erweiterte Suche.
- Neue Kategorien Bilder, Videos und Musik als eigene Ergebnis-Karten (mit
  eigenen Filter-Chips) neben „Dateien" (Dokumente), Kontakten, Terminen und
  Nachrichten. Medien sind ohnehin im Index (über den Namen) – die Aufteilung
  passiert bei der Anzeige.
- Titel-/Vorschaubilder werden jetzt auch beim Anzeigen bei Bedarf nacherzeugt,
  nicht mehr nur während eines Indizierlaufs – sie erscheinen also auch, wenn
  der System-Cache geleert wurde, eine Datei erst nach dem letzten Lauf dazukam
  oder über ihren Ordner noch nie ein Lauf lief.
- Beim Schließen einer Vorschau springt die Liste zur zuletzt vorangezeigten
  Datei (vorher irrtümlich zur zuletzt in einer anderen App geöffneten).
- ✕-Knopf im Suchfeld zum schnellen Leeren des Suchtexts.
- „Neue Suche" setzt die ganze Suche zurück (Text, Eingrenzungen, erweiterte
  Felder, Kategorie-Filter).
- Suchverlauf wieder da – als Aufklappmenü mit drehendem Pfeil, „Verlauf
  löschen" im aufgeklappten Bereich; er füllt sich jetzt zuverlässig (beim
  Bestätigen mit der Sucher-Taste und beim Öffnen/Vorzeigen eines Treffers),
  statt fast immer leer zu bleiben.

## 0.27
- Nicht auflistbarer Wurzelordner wird automatisch auf den zugänglichen
  internen Speicher abgebildet: „/storage/emulated" (bzw. „/storage") kann eine
  App gar nicht auflisten – gemeint ist praktisch immer „/storage/emulated/0".
  Eine bestehende (im mageren Ordner-Picker gewählte) Einstellung funktioniert
  damit von selbst, ohne erneut einen Ordner wählen zu müssen; der vorhandene
  Index wird per mtime wiederverwendet (kein komplettes Neu-Indizieren).
- Neuer Ein-Tipp-Knopf „Internen Speicher durchsuchen" fügt den gesamten
  internen Speicher (/storage/emulated/0) direkt hinzu – ohne sich durch den
  Ordner-Browser klicken zu müssen (dessen oberste Ebene sich vorher nicht als
  „diesen Ordner nehmen" wählen ließ).

## 0.26
- Wärme/Dauerlast behoben: Ist ein eingestellter Wurzelordner nicht auflistbar
  (z. B. „/storage/emulated" selbst – von einer App nicht lesbar; zugänglich ist
  erst „/storage/emulated/0"), wird er jetzt übersprungen und der Index NICHT
  angetastet. Vorher lief in dem Fall die Verwaisten-Bereinigung über ALLE
  Einträge dieses Ordners – zeilenweise, minutenlang, das Telefon wurde warm,
  und der nächste Lauf musste alles neu indizieren. Ein deutlicher Hinweis
  landet im Diagnose-Protokoll.
- Verwaisten-Bereinigung (pruneStale) läuft jetzt in EINER Transaktion statt
  tausender einzelner Lösch-Commits – um Größenordnungen schneller und kühler.

## 0.25
- Diagnose-Zeilen werden zusätzlich nach logcat gespiegelt (Tag „SucherDiag")
  und vor jeder Inhalts-/Metadaten-Extraktion eine Breadcrumb mit Datei, Typ
  und Größe ausgegeben – so bleibt bei einem harten Prozess-Ende die zuletzt
  begonnene Datei nachvollziehbar (per Kabel live mitlesbar).

## 0.24
- Diagnose-Protokoll zeigt jetzt Pfade: Bisher stand ein Datei-Pfad nur bei
  einem Einzeldatei-Fehler oder einem erkannten Datei-Hänger im Log - bei einem
  Ordner-Amoklauf (Pfad-Alias-Ring) blieb er leer, weil dabei nie eine Datei
  erreicht wird. Neu wird der aktuell durchlaufene ORDNER mitgeführt und
  gemeldet, und alle 30 s ein Herzschlag-Eintrag geschrieben (geprüfte Dateien,
  besuchte Ordner, aktueller Ordner/aktuelle Datei). So zeigt der Log beim
  nächsten Warmwerden genau, wo der Lauf steckt - und ob die Ordnerzahl bei
  stehender Dateizahl hochläuft (typisches Zeichen eines Alias-Rings).
- Der Fehler-Abbruch nennt jetzt die Fehlerstelle und den zuletzt bearbeiteten
  Ordner/die Datei; ein Systemabbruch (Job-Zeitfenster) wird protokolliert.

## 0.23
- Neu: Diagnose-Protokoll (Einstellungen, unter der Sicherung). Hält fest, was
  der Indizierer tut und an welchen Dateien er sich verschluckt - ohne Kabel/
  Logcat einsehbar, mit Knopf zum Löschen.
- Neu: Liste "Problematische Dateien" - Dateien, die einen Indizierer-Hänger
  ausgelöst haben (und deshalb nur noch über den Namen erfasst werden), werden
  jetzt angezeigt und lassen sich einzeln wieder freigeben (erneut versuchen).
- Übersprungene Dateien (Fehler beim Verarbeiten) und erkannte Hänger werden
  ins Diagnose-Protokoll geschrieben.

## 0.22
- Behebt den in mehreren Läufen aufgetretenen Dauer-Hänger (Indizierer lief
  über Tage mit hoher CPU-Last, Telefon wurde warm, weder der Stoppen-Knopf
  noch das erzwungene Beenden über die Systemeinstellungen hielten ihn
  zuverlässig an): Ein Endlos-Loop in einer Format-Bibliothek reagiert nicht
  auf ein kooperatives Stopp-Flag. Neu:
  - Selbstheilung: Ein Wächter beendet den Prozess automatisch, wenn der
    Indizierer 5 Minuten lang keinen Fortschritt mehr macht, und merkt sich
    vorher die auslösende Datei. Künftige Läufe überspringen diese Datei
    komplett (nur noch ihr Datei-Eintrag über die Namenssuche, kein Inhalt/
    Titelbild) - derselbe Hänger wiederholt sich so nicht endlos.
  - Neuer Knopf "Sucher beenden" (Einstellungen, bei der Indizierung): beendet
    den Prozess sofort und hart - wirkt auch dann, wenn ein Thread festhängt,
    anders als das kooperative Stoppen.
  - Zweite Notbremse gegen einen Pfad-Alias-Ring: Ein Lauf bricht ab, wenn er
    unplausibel viele Ordner besucht (greift auch dort, wo die bisherige
    Tiefen-Grenze nicht anschlägt, weil die Rekursion in die Breite läuft).

## 0.21
- Neu: Sichern/Wiederherstellen (Einstellungen → Sicherung) - sichert
  Einstellungen UND den kompletten Suchindex (alle bereits erfassten
  Volltexte) als eine Datei, statt einer Neuinstallation die stundenlange
  Ersterfassung wieder aufzubuerden. Laeuft im Hintergrund (blockiert die
  Bedienung nicht), Datenbank-Datei wird unkomprimiert gespeichert (bei
  einem grossen Index sonst mehrere Minuten CPU-Last allein fuers
  Komprimieren, bei kaum kleinerem Ergebnis).
- Nachtrag zu 0.20: die dort vermutete Pfad-Alias-Rekursion war (zumindest
  in einem beobachteten Fall) NICHT die Ursache eines weiterhin
  auftretenden Haengers (keine Symlinks im durchsuchten Baum gefunden,
  Verschachtelungs-Grenze griff nicht). Wahrscheinlicher: die
  "Zugriff auf alle Dateien"-Berechtigung war zwischenzeitlich in einen
  inkonsistenten Systemzustand geraten (AppOps zeigte "allow", die
  Paketverwaltung "granted=false") - ohne diese Berechtigung faellt
  Dateizugriff auf Androids deutlich langsameren FUSE-Kompatibilitaetspfad
  zurueck, was bei einer sehr grossen Ordnerstruktur wie einem
  Haengenbleiben aussehen kann. Zur Absicherung zusaetzlich: eine
  Diagnose-Protokollierung (Logcat-Tag "EdgeTabSearchDiag"), falls es
  erneut auftritt.

## 0.20
- Fix: ein Indizierlauf konnte auf manchen Geräten/ROMs unbegrenzt in einer
  Ordner-Rekursion feststecken (vermutlich ein Pfad-Alias, den
  getCanonicalPath() nicht auf denselben String abbildet, wie
  /storage/emulated/0 vs. /storage/self/primary) - dauerhaft hohe CPU-Last
  und Akkuverbrauch, ohne je eine Datei fertig zu bearbeiten. Eine harte
  Verschachtelungs-Grenze bricht so einen Lauf jetzt ab.
- Neu: "Jetzt neu indizieren" wird waehrend eines laufenden Durchlaufs zu
  "Stoppen" - bricht ihn kooperativ ab, ohne die App beenden zu muessen.
  Ein erneuter Start danach setzt dank des Aenderungszeit-Abgleichs faktisch
  dort fort, wo abgebrochen wurde.

## 0.19a
- Nur Änderungsprotokoll-Text bereinigt (keine Personenerwähnung mehr bei
  gemeldeten Fehlern/Wünschen), keine funktionale Änderung.

## 0.19
- Sichtbarer Indizier-Fortschritt: die Einstellungen zeigen
  jetzt zusätzlich, wie viele der infrage kommenden Dateien schon Volltext
  haben, mit Prozentanzeige - die bisherige Gesamtzahl allein bewegt sich
  kaum, sobald der Ordnerbaum einmal komplett bekannt ist, und sah darum wie
  Stillstand aus. Während eines laufenden Durchlaufs aktualisiert sich die
  Anzeige jetzt außerdem von selbst jede Sekunde (vorher nur bei eigener
  Aktion), inklusive des Pfads der gerade verarbeiteten Datei.
- Echten Geschwindigkeits-Bug gefunden und behoben, dank dieser neuen
  Anzeige: Formate, die grundsätzlich nie Volltext bekommen können (Fotos,
  Musik, Videos, APKs - die Mehrheit der Dateien auf jedem Gerät), wurden
  durch den in 0.16 eingeführten Schnell-Überspringen-Fix versehentlich bei
  JEDEM Lauf komplett neu verarbeitet statt übersprungen. Erklärt sowohl die
  große Diskrepanz zwischen von einem Dateimanager gemeldeter Gesamtzahl und
  Sucher's Zähler, als auch einen Großteil der Verlangsamung.

## 0.18
- Massives Indizier-Problem behoben: der Hintergrund-Abgleich (IndexJobService)
  stoppte den laufenden Scan bei `onStopJob()` nicht wirklich - der Thread lief
  unbeaufsichtigt weiter, während das System den Job nach Ablauf seines
  Zeitfensters wiederholt zwangsbeenden musste (24 Timeouts laut
  `dumpsys jobscheduler` bei einer rund 50.000-Dateien-Bibliothek nach acht
  Stunden mit unter 3.000 erfassten Dateien). Jeder Zwangsabbruch löste sofort
  einen Neustartversuch aus, der ins selbe Problem lief und die App zunehmend
  drosselte. Der Scan reagiert jetzt kooperativ auf das Stopp-Signal und meldet
  sich zügig als fertig; ein unvollständig durchlaufener Ordner wird dabei
  nicht mehr fälschlich auf "aufgeräumt" gesetzt (sonst wären noch nicht
  wieder erreichte Dateien als gelöscht behandelt worden).
- Tipp bei großen Bibliotheken: der Knopf „Jetzt neu indizieren" in den
  Einstellungen läuft, solange die App offen ist, ohne das Zeitfenster-Limit
  des Hintergrund-Jobs - für einen einmaligen großen Nachholbedarf (z. B. nach
  Einschalten der Volltext-Indizierung für einen bestehenden Ordner) meist
  deutlich schneller als abzuwarten, bis der Hintergrund-Job in kleinen
  Häppchen durchkommt.

## 0.17
- Neu: Suchergebnisse eingrenzen. Unter einer Dateien-Trefferliste erscheint
  „🔎 Nur in diesen N Treffern weitersuchen" - antippen beschränkt jede
  weitere Suche (einfach oder erweitert, auch mit völlig anderen
  Suchbegriffen/Feldern) auf genau diese Treffermenge, bis man sie über
  „Aufheben" wieder verwirft. Lässt sich mehrfach hintereinander anwenden,
  um sich schrittweise zum gesuchten Ergebnis vorzuarbeiten.

## 0.16
- Echten Indizier-Bug behoben: Wurde „Inhalt durchsuchbar machen" für einen
  Ordner nachträglich eingeschaltet, blieb die Volltextsuche darin
  trotzdem leer, solange sich keine einzige Datei mehr änderte (bei einer
  stabilen Sammlung: nie) - der Ändern-Zeit-Vergleich übersprang bereits
  bekannte Dateien komplett, auch wenn ihr Inhalt noch nie erfasst wurde.
  Prüft jetzt zusätzlich, ob der Inhalt schon vorliegt.
- Zwei verwandte Pfad-Bugs behoben: ein Ordner wie „/sd/books" erfasste
  durch einen fehlenden Pfadtrenner beim Vergleich auch Geschwister wie
  „/sd/books2/…" mit; ein Ordnername mit Unterstrich (z. B. „meine_bücher")
  wurde als SQL-Platzhalter fehlinterpretiert und traf dadurch auch
  „meineXbücher".

## 0.15
- Zweites ANR behoben: EPUB/MOBI-Vorschau entpackte/dekomprimierte das
  ganze Buch bisher synchron in `onCreate` - bei groesseren oder reich
  bebilderten Buechern (beobachtet mit einem 45-Kapitel-Buch) konnte das
  den Hauptthread lange genug blockieren fuer ein "App reagiert nicht".
  Läuft jetzt in einem Hintergrund-Thread; die eigentliche Ansicht (inkl.
  WebView) wird danach auf dem Hauptthread gebaut, mit "Wird geladen…"
  als kurzem Platzhalter dazwischen.

## 0.14
- Absturz-nahes Einfrieren behoben ("reagiert nicht"/ANR): eine eintreffende
  Benachrichtigung wurde direkt auf dem Hauptthread in die Datenbank
  geschrieben. Lief nebenbei gerade ein (Neu-)Indizierungslauf, konnte das
  den Hauptthread minutenlang blockieren, weil beide auf dieselbe
  SQLite-Verbindung warteten - Folge waren "App reagiert nicht"-Meldungen.
  Schreibt jetzt in einem eigenen Hintergrund-Thread; zusätzlich WAL-Modus
  aktiviert, damit Suche und Indizierung sich generell weniger blockieren.

## 0.13
- Neue Seite „Über Sucher" in den Einstellungen: Versionsnummer, Lizenztext
  und ein aufklappbares Änderungsprotokoll (dieses Dokument, direkt in der
  App).
- Build-Fehler behoben: PDFBox-Androids Schriftart-/Glyphen-Daten
  (`assets/com/tom_roush/...`) wurden bisher nicht in die APK gepackt (fehlendes
  `-A assets` beim Bauen) - PDF-Vorschauen liefen dadurch seit der ersten
  Version ohne korrekte Schriftmetriken, ohne dass es abstürzte.
- Quelloffene Veröffentlichung auf GitHub vorbereitet, Lizenz: GNU General
  Public License v3, mit Drittanbieter-Hinweisen für die gebündelten
  Bibliotheken (Apache 2.0 / BSD-3-Clause, beide mit GPLv3 kombinierbar);
  Build-Anleitung korrigiert.

## 0.12
- Absturz behoben: ein interner Buch-Link (z. B. ein Eintrag im
  Inhaltsverzeichnis eines EPUB/MOBI) ließ die App abstürzen
  (`FileUriExposedException`, weil die Vorschau-WebView ohne eigenen
  `WebViewClient` lief und `file://`-Links an das System weiterreichte).

## 0.11
- Suchverlauf: zeigt sich, solange das Suchfeld leer ist; ein Begriff wird
  aufgenommen, sobald er über die Sucher-Taste der Tastatur bestätigt wird
  (einzeln oder komplett löschbar).
- Die beiden Ausklapp-Pfeile ("Erweiterte Suche", "Dateiart") deutlich
  vergrößert.

## 0.10
- Vorschau schließen stellt jetzt die zuletzt gezeigte Suche (einfach oder
  erweitert) wieder her, statt sie zu leeren.
- "Dateiart" ist ein einklappbarer Abschnitt mit drehendem Pfeil und
  mehrzeiligem Raster statt einer wischbaren Einzelzeile.
- Scroll-Position bleibt über jeden Neuaufbau der Ansicht hinweg erhalten
  (betraf vorher jede Auswahl/Einstellung in der App).

## 0.9 — "Quick Look"
- Miniaturbilder/Cover in der Trefferliste (Bilder, PDF-erste-Seite,
  EPUB/MOBI-Cover aus OPF bzw. EXTH-Header, Comic-Titelbild, Office-
  Thumbnail wo vorhanden).
- Antippen öffnet eine große Vorschau mit echtem Durchblättern bei Bildern/
  PDF/Comics (eigener Wisch-Pager, kein AndroidX verfügbar) sowie EPUB und
  klassischem MOBI6 (über Androids eingebauten WebView-Renderer).
- Dateiart-Filter der erweiterten Suche ist jetzt mehrfachauswählbar.
- Bekannte Grenzen: CBR ohne Cover/Blättern (kein RAR-Support vendort),
  Office-Dateien nur mit statischem Vorschaubild falls vorhanden, AZW3/KF8
  nur als reiner Text statt echtem Buchlayout (Skeleton/Flow-Rekonstruktion
  wäre ein eigenes Projekt).

## 0.8
- Erweiterte, kombinierbare Suche: Dateiname, Autor, Titel, Buchserie,
  Dateiart, Erstellt-/Geändert-Datumsbereich.
- Echte Buch-Metadaten (Titel/Autor/Serie) aus EPUB (OPF + Calibre-Serie),
  FB2 (natives `<sequence>`), PDF (`PDDocumentInformation`), DOCX/XLSX/PPTX
  (`docProps/core.xml`), legacy DOC/XLS/PPT (POI `SummaryInformation`),
  MOBI/AZW3 (EXTH-Header).
- Inhalt-Indizierung (der teure Volltext, nicht die Metadaten) ist jetzt
  **pro Ordner** an-/abwählbar, Standard aus - bei sehr großen Bibliotheken
  (mehrstelliger GB-Index sonst) lässt sich gezielt auswählen, wo sich
  Volltextsuche lohnt.

## 0.7
- Sechs Detailkorrekturen: längere Fundstellen-Schnipsel (40 statt 12
  Wörter), Kategorie-Filter-Leiste über den Ergebnissen (Dateien/Kontakte/
  Termine/Nachrichten einzeln ausschließbar), Schriftgröße 80-150 %
  einstellbar, Benachrichtigungsquellen hinter "Bearbeiten" versteckt statt
  immer die volle App-Liste zu zeigen, Suchtext+Ergebnisse bleiben beim
  Rückkehren aus einer anderen App erhalten, zuletzt geöffnete Datei blau
  markiert und automatisch ins Blickfeld gescrollt.

## 0.6
- Manifest fehlte der `<queries>`-Block - ohne den sind seit Android 11
  praktisch alle fremden Apps für Paketabfragen unsichtbar; behoben.

## 0.5
- Ordner-Indizierung schützt jetzt vor Endlosschleifen/Dopplungen durch
  Systemverknüpfungen wie `/storage/self` (kanonische Pfad-Prüfung).

## 0.4
- Automatischer Hintergrund-Abgleich alle paar Stunden über Androids
  `JobScheduler` (kein Dauer-Dienst wie bei EdgeTab - batterieschonender),
  abschaltbar in den Einstellungen. Deckt nebenbei "abgebrochenen Lauf
  fortsetzen" ab (mtime-Vergleich macht jeden Anlauf günstig).

## 0.3
- Neuer Abschnitt "Benachrichtigungsquellen": Häkchen pro App, nur
  angehakte Quellen durchsuchbar (wie EdgeTabs Posteingang-Quellen).

## 0.2
- Benachrichtigungen können mitgeschnitten werden (eigener
  `NotificationListenerService`) - neue "Nachrichten"-Trefferkarte, deckt
  Chats/Mails teilweise ab (nur was als Systembenachrichtigung durchkam,
  keine volle Chat-Historie).
- "Zugriff erlauben"-Knopf hat jetzt drei Rückfallebenen statt stillschweigend
  nichts zu tun, wenn ein ROM den bevorzugten Berechtigungsbildschirm nicht
  kennt.

## 0.1 — Erstveröffentlichung
- Aus EdgeTabs "Suche"-Karte herausgelöst: eigenständige App, eigenes
  Icon, eigener Erstinstall-Berechtigungsfluss (Alle-Dateien-Zugriff,
  Kontakte, Kalender).
- Volltextsuche über Dateien (TXT/MD, Office DOCX/XLSX/PPTX + legacy DOC/
  XLS/PPT via Apache POI, PDF via PDFBox-Android, EPUB/FB2, MOBI/AZW3),
  plus Kontakte und Termine, in einem Suchfeld.

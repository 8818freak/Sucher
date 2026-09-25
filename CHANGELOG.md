# Changelog — Sucher

Alle nennenswerten Änderungen, neueste zuerst. Sucher wurde am 2026-09-24
als eigenständige App aus EdgeTabs kurzlebiger "Suche"-Karte (EdgeTab 0.60)
herausgelöst.

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

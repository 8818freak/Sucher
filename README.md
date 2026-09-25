# Sucher

A standalone Android device-search app: full-text search across files
(TXT/MD, Office DOCX/XLSX/PPTX and legacy DOC/XLS/PPT, PDF, EPUB/FB2,
MOBI/AZW3), plus contacts and calendar events, in one search field.

Split off from [EdgeTab](../../EdgeTab/) on 2026-09-24, where it started as
a tab before turning out to deserve its own app (different permission
profile, different usage pattern — open it directly, search, done, no
always-on background service).

## Format support

| Format | Method | Notes |
|---|---|---|
| TXT/MD/CSV/JSON/XML/etc. | direct read | |
| DOCX/XLSX/PPTX | ZIP+XML via Android's built-in `XmlPullParser` | no Apache POI needed |
| EPUB/FB2 | same | |
| PDF | PDFBox-Android | encrypted PDFs: filename only, not decrypted |
| DOC/XLS/PPT (legacy) | Apache POI (`poi`+`poi-scratchpad` only, no `poi-ooxml`) | |
| MOBI/AZW3/AZW/PRC | hand-rolled PalmDOC/MOBI6 reader | HUFF/CDIC-compressed books: filename only; DRM books: filename only, by design |
| CBZ | filename + `ComicInfo.xml` metadata if present | |
| CBR | filename only | no RAR reader vendored, not worth it just for metadata |

Every file gets a metadata row regardless of format, so name/type/date
search covers everything, not just the table above.

## Building

No Gradle — same raw Android SDK command-line build as EdgeTab. You need:

- Android SDK (`platforms;android-34`, `build-tools;34.0.0`, `platform-tools`)
- A JDK (11+)
- A signing keystore (this project reuses EdgeTab's own — see its README
  for how to generate one if you don't have it)

```sh
SDK=/path/to/android/sdk
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
KEYSTORE=/path/to/keystore.p12

rm -rf build && mkdir -p build/gen build/obj
"$BT/aapt2" compile --dir res -o build/res.zip
# -A assets is required: PDFBox-Android's PDFBoxResourceLoader reads its
# font/glyph data from assets/com/tom_roush/... at runtime, and the in-app
# changelog (assets/CHANGELOG.md) is loaded the same way. Earlier builds
# accidentally omitted this flag - PDF rendering still worked (wrapped in
# try/catch) but silently without proper font metrics.
"$BT/aapt2" link -o build/base.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --java build/gen -R build/res.zip -A assets --auto-add-overlay \
  --min-sdk-version 29 --target-sdk-version 34
javac --release 11 -d build/obj -classpath "$AJAR:$(echo libs/*.jar | tr ' ' ':')" \
  $(find src build/gen -name '*.java')
# module-info.class (JPMS) and META-INF/versions/ (multi-release jar classes,
# e.g. log4j-api ships a Java 9 variant of Base64Util) both confuse d8 if left
# in - the former isn't a real class, the latter causes "defined multiple
# times" duplicate-class errors since d8 sees both variants unversioned.
(cd build/obj && for j in ../../libs/*.jar; do jar xf "$j"; done && find . -name 'module-info.class' -delete && rm -rf META-INF/versions)
"$BT/d8" --min-api 29 --lib "$AJAR" --output build/ $(find build/obj -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build && zip -qj unsigned.apk classes*.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-type PKCS12 --out build/Sucher.apk build/aligned.apk
```

## Why it exists

A companion to EdgeTab for the same reason EdgeTab exists: the standard
Android search experience doesn't reach into file contents, and no single
free app covers Office+PDF+e-book+comic-metadata search without turning
into a 19MB ad-supported bundle. MIT-licensed groundwork, same author.

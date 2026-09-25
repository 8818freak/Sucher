package de.herbers.sucher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Schlanker eigener Ersatz fuer androidx.core.content.FileProvider - dafuer
 * extra die AndroidX-Bibliothek zu vendoren waere fuer diesen einen Zweck
 * (Dateien aus der Suche-Karte an die zustaendige App weiterreichen, wie
 * ueberall in EdgeTab "an die echte App delegieren" statt selbst anzuzeigen)
 * unverhaeltnismaessig - die Berechtigung (MANAGE_EXTERNAL_STORAGE) erlaubt
 * ohnehin schon vollen Lesezugriff, eine Pfad-Freigabeliste wie beim echten
 * FileProvider ist hier darum nicht noetig.
 *
 * URI-Form: content://<pkg>.fileprovider/file?path=<url-kodierter Pfad>
 */
public class LocalFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        int m = "w".equals(mode) ? ParcelFileDescriptor.MODE_WRITE_ONLY : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                         String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        File f = fileFor(uri);
        String ext = extOf(f.getName());
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    static Uri uriForFile(android.content.Context ctx, File f) {
        return new Uri.Builder()
                .scheme("content")
                .authority(ctx.getPackageName() + ".fileprovider")
                .path("/file")
                .appendQueryParameter("path", f.getAbsolutePath())
                .build();
    }

    private File fileFor(Uri uri) {
        String path = uri.getQueryParameter("path");
        if (path == null) throw new SecurityException("Kein Pfad in der URI");
        return new File(path);
    }

    private String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(java.util.Locale.ROOT);
    }
}

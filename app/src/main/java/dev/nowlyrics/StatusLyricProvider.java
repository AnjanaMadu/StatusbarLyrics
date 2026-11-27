package dev.nowlyrics;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class StatusLyricProvider extends ContentProvider {
    static final String URI = "content://dev.nowlyrics.statuslyric/current";
    private static volatile String current = "";

    static void publish(Context context, String text) {
        String effective = Prefs.isStatusBarActive(context) ? text : "";
        if (current.equals(effective)) return;
        current = effective;
        notifyChanged(context);
    }

    static void notifyChanged(Context context) {
        context.getContentResolver().notifyChange(Uri.parse(URI), null, 0);
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String order) {
        if (!URI.equals(uri.toString())) return null;
        Context context = getContext();
        String value = (context == null || Prefs.isStatusBarActive(context)) ? current : "";
        MatrixCursor cursor = new MatrixCursor(new String[]{"lyric"}, 1);
        cursor.addRow(new Object[]{value});
        return cursor;
    }

    @Override public String getType(Uri uri) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}

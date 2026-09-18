package io.github.colorduo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Intentionally exported read-only endpoint: no personal data, no external writes. */
public final class EffectSettingsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        if (!EffectSettings.URI.equals(uri)) throw new IllegalArgumentException("Unknown settings URI");
        MatrixCursor cursor=new MatrixCursor(new String[]{"mode"});
        cursor.addRow(new Object[]{EffectSettings.readLocal(getContext())});
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.colorduo.effect"; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException("Read only"); }
}

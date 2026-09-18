package io.github.colorduo;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/** App-private writes; the launcher only reads the public, non-sensitive effect number. */
public final class EffectSettings {
    public static final Uri URI=Uri.parse("content://io.github.colorduo.settings/effect");
    private static final String PREFS="effect_settings", KEY="mode";
    private EffectSettings() {}
    public static int readLocal(Context context) {
        return EffectMode.normalize(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getInt(KEY,EffectMode.FROST));
    }
    public static void save(Context context,int mode) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putInt(KEY,EffectMode.normalize(mode)).apply();
        context.getContentResolver().notifyChange(URI,null);
    }
    public static final class Watcher {
        private static final ExecutorService READER=Executors.newSingleThreadExecutor(r -> {
            Thread thread=new Thread(r,"ColorDuo-Settings"); thread.setDaemon(true); return thread;
        });
        private final Context context;
        private final Handler main=new Handler(Looper.getMainLooper());
        private final IntConsumer callback;
        private boolean closed;
        private int generation;
        private final ContentObserver observer=new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { refresh(); }
        };
        public Watcher(Context context,IntConsumer callback) {
            this.context=context.getApplicationContext(); this.callback=callback;
            this.context.getContentResolver().registerContentObserver(URI,false,observer);
            refresh();
        }
        public void refresh() {
            if (closed) return;
            int request=++generation;
            READER.execute(() -> {
                try (Cursor cursor=context.getContentResolver().query(URI,new String[]{KEY},null,null,null)) {
                    if (cursor==null || !cursor.moveToFirst()) return;
                    int mode=EffectMode.normalize(cursor.getInt(0));
                    main.post(() -> {
                        if (!closed && request==generation) callback.accept(mode);
                    });
                } catch (Exception error) { Log.w("ColorDuoSettings","Cannot read saved effect",error); }
            });
        }
        public void close() {
            closed=true; generation++;
            context.getContentResolver().unregisterContentObserver(observer);
        }
    }
}

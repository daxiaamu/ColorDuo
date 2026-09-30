package io.github.colorduo;

import android.graphics.Canvas;
import android.graphics.BlendMode;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.graphics.drawable.GradientDrawable;
import java.lang.reflect.Field;
import java.util.*;

/** Ordinary materials are recorded with foreground content, then use the same depth shader. */
final class FolderBackdrop {

    private record Entry(View view,Drawable material) {}
    private FolderBackdrop() {}
    private static ArrayList<Entry> collectMaterials(View page) {
        ArrayList<Entry> entries=new ArrayList<>();
        collect(page,entries,Collections.newSetFromMap(new IdentityHashMap<>()));

        return entries;
    }
    private static void collect(View view,ArrayList<Entry> entries,Set<Drawable> seen) {
        if(view.getVisibility()!=View.VISIBLE) return;
        add(view,view.getBackground(),entries,seen);
        if(view instanceof ImageView image) add(view,image.getDrawable(),entries,seen);
        if(view instanceof ViewGroup group)
            for(int i=0;i<group.getChildCount();i++) collect(group.getChildAt(i),entries,seen);
    }
    private static void add(View host,Drawable drawable,ArrayList<Entry> entries,Set<Drawable> seen) {
        if(drawable==null || !seen.add(drawable)) return;
        if(drawable instanceof LayerDrawable layers) {
            for(int i=0;i<layers.getNumberOfLayers();i++) add(host,layers.getDrawable(i),entries,seen);
            return;
        }
        if(!LauncherReflection.hasNamedSuperclass(drawable.getClass(),"com.android.launcher3.uioverrides.states.blurdrawable.BlurTransitionDrawable")) return;
        try {
            for(Class<?> c=drawable.getClass();c!=null;c=c.getSuperclass()) {
                Field field;
                try {field=c.getDeclaredField("mDefaultDrawable");} catch(NoSuchFieldException absent) {continue;}
                field.setAccessible(true);
                if(field.get(drawable) instanceof Drawable flat) {
                    if(flat.getAlpha()!=0 || !(flat instanceof GradientDrawable gradient) || gradient.getColor()==null) return;
                    GradientDrawable ordinary=new GradientDrawable();
                    ordinary.setColor(gradient.getColor());
                    float radius=gradient.getCornerRadius();
                    try {
                        Field corner=flat.getClass().getDeclaredField("mStrokeRadius");
                        corner.setAccessible(true);
                        radius=corner.getFloat(flat);
                    } catch(ReflectiveOperationException ignored) { /* Use the framework corner radius. */ }
                    ordinary.setCornerRadius(radius);
                    ordinary.setBounds(drawable.getBounds().isEmpty()
                            ? new android.graphics.Rect(0,0,host.getWidth(),host.getHeight())
                            : drawable.getBounds());
                    entries.add(new Entry(host,ordinary));
                }
                return;
            }
        } catch(ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w("ColorDuoFolder","Ordinary material unavailable",error);
        }
    }
    static void recordBehind(View page,Canvas canvas) {
        ArrayList<Entry> entries=collectMaterials(page);
        if(entries.isEmpty()) return;
        // Native backdrop commands must precede this ordinary underlay.
        // Only cached source recording uses the layer, never animation frames.
        Paint under=new Paint();
        under.setBlendMode(BlendMode.DST_OVER);
        int layer=canvas.saveLayer(null,under);
        try {
            for(Entry entry:entries) {
                View view=entry.view;
                if(!view.isAttachedToWindow()) continue;
                int save=canvas.save();
                try {
                    if(transform(page,view,canvas)) entry.material.draw(canvas);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        } finally {
            canvas.restoreToCount(layer);
        }
    }
    private static boolean transform(View page,View view,Canvas canvas) {
        if(view==page) return true;
        if(view.getVisibility()!=View.VISIBLE || view.getAlpha()!=1f) return false;
        if(!(view.getParent() instanceof View parent) || !transform(page,parent,canvas)) return false;
        canvas.translate(view.getLeft()-parent.getScrollX(),view.getTop()-parent.getScrollY());
        canvas.concat(view.getMatrix());
        return true;
    }

}
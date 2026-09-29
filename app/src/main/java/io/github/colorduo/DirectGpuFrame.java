package io.github.colorduo;

import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.RenderNode;
import java.lang.reflect.Method;

/** Optional non-SDK capability, resolved only inside the LSPosed launcher process. */
final class DirectGpuFrame {
    private static Method snapshot;
    // LSPosed exempts its injected process from hidden API restrictions. The app preview
    // never calls this method. A missing/blocked API leaves the public backend available.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    static boolean resolveForLauncher() {
        try {
            Method method=HardwareRenderer.class.getDeclaredMethod("createHardwareBitmap",RenderNode.class,int.class,int.class);
            if(method.getReturnType()!=Bitmap.class || !java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                throw new NoSuchMethodException("Unexpected hardware snapshot signature");
            method.setAccessible(true); snapshot=method;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            android.util.Log.w("ColorDuoBackend","Hardware snapshot unavailable; use public backend",error);
            return false;
        }
    }
    static Bitmap draw(RenderNode node,int width,int height) throws Exception {
        return (Bitmap)snapshot.invoke(null,node,width,height);
    }
    private DirectGpuFrame() {}
}

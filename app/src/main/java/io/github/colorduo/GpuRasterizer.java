package io.github.colorduo;

import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Shared by both effects: probe real output, remember success, fall back with cooldown. */
final class GpuRasterizer implements AutoCloseable {
    private static final int SNAPSHOT=0, SURFACE=1;
    private static final String[] NAMES={"platform-snapshot","image-reader"};
    private static final RenderBackendSelector SELECTOR=new RenderBackendSelector(false,true);
    private static final Object LOCK=new Object();
    private static final boolean[] verified=new boolean[2];
    private static final Handler CALLBACKS=new Handler(Looper.getMainLooper());
    private static int reported=-1;
    private HardwareRenderer renderer;

    static void enableForLauncher() {
        synchronized(LOCK) { SELECTOR.setAvailable(SNAPSHOT,DirectGpuFrame.resolveForLauncher()); }
    }
    Bitmap draw(RenderNode node,int width,int height) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new IllegalStateException("GPU capture on UI thread");
        // Serializes the two effects' workers during live switching; never takes a UI draw lock.
        synchronized(LOCK) {
            Exception failures=new IllegalStateException("No working GPU backend (retry is bounded)");
            for(int backend:SELECTOR.candidates(SystemClock.uptimeMillis())) {
                try {
                    if(!verified[backend]) { probe(backend); verified[backend]=true; }
                    Bitmap bitmap=render(backend,node,width,height);
                    SELECTOR.success(backend);
                    if(reported!=backend) { reported=backend; Log.i("ColorDuoBackend","Selected "+NAMES[backend]+"; output/alpha probe passed"); }
                    return bitmap;
                } catch(Exception | LinkageError error) {
                    verified[backend]=false;
                    SELECTOR.failure(backend,SystemClock.uptimeMillis());
                    failures.addSuppressed(error);
                    Log.w("ColorDuoBackend",NAMES[backend]+" failed; trying another backend",error);
                }
            }
            throw failures;
        }
    }
    private void probe(int backend) throws Exception {
        RenderNode node=new RenderNode("ColorDuo-CapabilityProbe");
        Bitmap gpu=null,cpu=null;
        try {
            node.setPosition(0,0,4,4);
            RecordingCanvas canvas=node.beginRecording(4,4);
            canvas.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);
            Paint paint=new Paint(); paint.setColor(Color.rgb(32,96,160));
            canvas.drawRect(0,0,2,4,paint); node.endRecording();
            gpu=render(backend,node,4,4);
            cpu=gpu.copy(Bitmap.Config.ARGB_8888,false);
            if(cpu==null) throw new IllegalStateException("GPU probe readback failed");
            int color=cpu.getPixel(0,0);
            if(Color.alpha(color)<250 || Math.abs(Color.red(color)-32)>8
                    || Math.abs(Color.green(color)-96)>8 || Math.abs(Color.blue(color)-160)>8
                    || Color.alpha(cpu.getPixel(3,0))>2)
                throw new IllegalStateException("GPU probe has incorrect color or transparency");
        } finally {
            if(cpu!=null) cpu.recycle(); if(gpu!=null) gpu.recycle(); node.discardDisplayList();
        }
    }
    private Bitmap render(int backend,RenderNode node,int width,int height) throws Exception {
        Bitmap bitmap=backend==SNAPSHOT?DirectGpuFrame.draw(node,width,height):surface(node,width,height);
        if(bitmap==null) throw new IllegalStateException("GPU returned no bitmap");
        if(bitmap.getWidth()!=width || bitmap.getHeight()!=height || bitmap.getConfig()!=Bitmap.Config.HARDWARE) {
            bitmap.recycle(); throw new IllegalStateException("GPU returned incompatible bitmap");
        }
        bitmap.setHasAlpha(true);
        return bitmap;
    }
    private Bitmap surface(RenderNode node,int width,int height) throws Exception {
        if(renderer==null) { renderer=new HardwareRenderer(); renderer.setOpaque(false); }
        try(ImageReader reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)) {
            CountDownLatch available=new CountDownLatch(1);
            reader.setOnImageAvailableListener(ignored -> available.countDown(),CALLBACKS);
            try {
                renderer.setSurface(reader.getSurface()); renderer.setContentRoot(node);
                int result=renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
                if((result & (HardwareRenderer.SYNC_CONTEXT_IS_STOPPED | HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND))!=0)
                    throw new IllegalStateException("GPU submission failed: "+result);
                Image image=reader.acquireNextImage();
                if(image==null) { available.await(500,TimeUnit.MILLISECONDS); image=reader.acquireNextImage(); }
                if(image==null) throw new IllegalStateException("GPU buffer missing: sync="+result);
                try(Image acquired=image; HardwareBuffer buffer=acquired.getHardwareBuffer()) {
                    if(buffer==null) throw new IllegalStateException("GPU hardware buffer missing");
                    return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB));
                }
            } finally { reader.setOnImageAvailableListener(null,null); renderer.setSurface(null); }
        }
    }
    @Override public void close() { if(renderer!=null) { renderer.destroy(); renderer=null; } }
}

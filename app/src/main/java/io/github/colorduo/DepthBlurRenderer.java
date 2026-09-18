package io.github.colorduo;

import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** GPU Gaussian pyramid prepared off the UI thread; frames sample two neighboring mip levels.
 * Architecture inspired by Mac-Duo (Makito); Android implementation uses public HWUI APIs.
 */
public final class DepthBlurRenderer {
    public static final String ENGINE = "two-sampler-gpu-pyramid-draw";
    private static final int LEVELS = 7;
    private static final String PROGRAM = """
            uniform shader content;
            uniform shader mip1; uniform shader mip2; uniform shader mip3;
            uniform shader mip4; uniform shader mip5; uniform shader mip6; uniform shader mip7;
            uniform float pageWidth;
            uniform float spanDp;
            uniform float farRight;
            uniform float density;
            uniform float3 depths;
            half4 main(float2 p) {
                float x = clamp(p.x / pageWidth, 0.0, 1.0);
                float depth = spanDp * mix(1.0-x, x, farRight);
                float t = clamp((depth-depths.x)/(depths.y-depths.x), 0.0, 1.0);
                float radius = depths.z * t*t*(3.0-2.0*t) * density;
                // Each level halves the image and applies a 1px Gaussian. Its equivalent
                // source-space sigma approaches 1.1547 * 2^level. Keep the original 26dp strength.
                float lod = clamp(log2(max(radius/1.1547, 1.0)), 0.0, 7.0);
                if (lod < 1.0) return mix(content.eval(p), mip1.eval(p), half(lod));
                if (lod < 2.0) return mix(mip1.eval(p), mip2.eval(p), half(lod-1.0));
                if (lod < 3.0) return mix(mip2.eval(p), mip3.eval(p), half(lod-2.0));
                if (lod < 4.0) return mix(mip3.eval(p), mip4.eval(p), half(lod-3.0));
                if (lod < 5.0) return mix(mip4.eval(p), mip5.eval(p), half(lod-4.0));
                if (lod < 6.0) return mix(mip5.eval(p), mip6.eval(p), half(lod-5.0));
                return mix(mip6.eval(p), mip7.eval(p), half(lod-6.0));
            }
            """;
    // Draw only the geometric interval belonging to each LOD pair. Every fragment uses
    // exactly two samplers, with the same continuous radius/LOD curve as PROGRAM.
    private static final String STRIP_PROGRAM = """
            uniform shader low; uniform shader high;
            uniform float pageWidth; uniform float spanDp; uniform float farRight;
            uniform float density; uniform float3 depths; uniform float baseLod;
            half4 main(float2 p) {
                float x=clamp(p.x/pageWidth,0.0,1.0);
                float depth=spanDp*mix(1.0-x,x,farRight);
                float t=clamp((depth-depths.x)/(depths.y-depths.x),0.0,1.0);
                float radius=depths.z*t*t*(3.0-2.0*t)*density;
                float lod=log2(max(radius/1.1547,1.0));
                return mix(low.eval(p),high.eval(p),half(clamp(lod-baseLod,0.0,1.0)));
            }
            """;
    private static final ExecutorService GPU = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ColorDuo-Pyramid"); thread.setDaemon(true); return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, Pyramid> pages = new WeakHashMap<>();
    private final float[] matrix = new float[9];
    private Runnable onReady;
    private boolean recording;
    private volatile boolean stopped;

    private static final class Pyramid {
        final int width, height, padding, textureWidth, textureHeight;
        final long created = SystemClock.uptimeMillis();
        long lastUsed = created;
        volatile boolean cancelled;
        RuntimeShader shader;
        RuntimeShader[] strips;
        float[] boundaryDepths;
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Bitmap[] images;
        boolean pending = true;
        Pyramid(int w, int h, int pad) { width=w; height=h; padding=pad; textureWidth=w+2*pad; textureHeight=h+2*pad; }
    }

    public void setOnReady(Runnable callback) { onReady = callback; }

    /** UI-thread recording only. GPU rendering/waits never execute on the launcher UI thread. */
    public void prepare(View page, boolean refresh) {
        if (stopped || page == null || page.getWidth() <= 0 || page.getHeight() <= 0) return;
        Pyramid old = pages.get(page);
        if (old != null && old.width == page.getWidth() && old.height == page.getHeight()
                && (!refresh || old.pending || SystemClock.uptimeMillis()-old.created < 1000)) return;
        if (old != null) old.cancelled = true;
        // Keep only a few page pyramids, never one cache per desktop indefinitely.
        if (pages.size() >= 4) {
            View oldest = null;
            long time = Long.MAX_VALUE;
            for (java.util.Map.Entry<View,Pyramid> entry : pages.entrySet()) {
                if (entry.getValue().lastUsed < time) { oldest=entry.getKey(); time=entry.getValue().lastUsed; }
            }
            if (oldest != null) { Pyramid stale=pages.remove(oldest); stale.cancelled=true; }
        }
        Pyramid pyramid = new Pyramid(page.getWidth(), page.getHeight(), (int)Math.ceil(3f*DepthModel.MAX_RADIUS_DP*page.getResources().getDisplayMetrics().density));
        pages.put(page, pyramid);
        WeakReference<View> target = new WeakReference<>(page);
        main.post(() -> record(target, pyramid));
    }

    private void record(WeakReference<View> target, Pyramid pyramid) {
        View page = target.get();
        if (stopped || pyramid.cancelled || page == null || !page.isAttachedToWindow()) return;
        RenderNode source = new RenderNode("ColorDuo-Source");
        try {
            source.setPosition(0, 0, pyramid.textureWidth, pyramid.textureHeight);
            RecordingCanvas canvas = source.beginRecording(pyramid.textureWidth, pyramid.textureHeight);
            canvas.translate(pyramid.padding,pyramid.padding);
            recording=true;
            try { page.draw(canvas); } finally { recording=false; source.endRecording(); }
            GPU.execute(() -> build(target, pyramid, source));
        } catch (Throwable error) {
            source.discardDisplayList();
            fail(pyramid, error);
        }
    }

    private void build(WeakReference<View> target, Pyramid pyramid, RenderNode source) {
        long started = SystemClock.uptimeMillis();
        HardwareRenderer renderer = null;
        Bitmap[] bitmaps = new Bitmap[LEVELS+1];
        Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
        try {
            renderer = new HardwareRenderer();
            renderer.setOpaque(false);
            int w = pyramid.textureWidth, h = pyramid.textureHeight;
            Bitmap previous = null;
            for (int level=0; level<=LEVELS; level++) {
                if (pyramid.cancelled || stopped) return;
                int outW=level==0?w:Math.max(1,(w+1)/2), outH=level==0?h:Math.max(1,(h+1)/2);
                RenderNode node = new RenderNode("ColorDuo-Mip" + (level+1));
                ImageReader reader = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
                try {
                    node.setPosition(0,0,outW,outH);
                    if (level>0) node.setRenderEffect(RenderEffect.createBlurEffect(1f,1f,Shader.TileMode.DECAL));
                    RecordingCanvas canvas=node.beginRecording(outW,outH);
                    canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR);
                    canvas.scale((float)outW/w,(float)outH/h);
                    if (previous==null) canvas.drawRenderNode(source);
                    else canvas.drawBitmap(previous,0f,0f,filter);
                    node.endRecording();
                    renderer.setSurface(reader.getSurface()); renderer.setContentRoot(node);
                    renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
                    try (Image image=reader.acquireNextImage()) {
                        if (image==null) throw new IllegalStateException("GPU mip not produced");
                        try (HardwareBuffer buffer=image.getHardwareBuffer()) {
                            if (buffer==null) throw new IllegalStateException("GPU buffer missing");
                            previous=Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                            if (previous==null) throw new IllegalStateException("GPU bitmap wrap failed");
                            // wrapHardwareBuffer may mark RGBA output opaque: preserve premultiplied alpha.
                            previous.setHasAlpha(true);
                            bitmaps[level]=previous;
                        }
                    }
                } finally {
                    renderer.setSurface(null);
                    reader.close(); node.discardDisplayList();
                }
                w=outW; h=outH;
            }
            // Shader compilation and bitmap binding are also kept off the main thread.
            RuntimeShader shader=new RuntimeShader(PROGRAM);
            BitmapShader[] samplers=new BitmapShader[LEVELS+1];
            for (int i=0;i<=LEVELS;i++) {
                Bitmap bitmap=bitmaps[i];
                BitmapShader sampler=new BitmapShader(bitmap,Shader.TileMode.DECAL,Shader.TileMode.DECAL);
                sampler.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                Matrix transform=new Matrix();
                transform.setScale((float)pyramid.textureWidth/bitmap.getWidth(),(float)pyramid.textureHeight/bitmap.getHeight());
                transform.postTranslate(-pyramid.padding,-pyramid.padding);
                sampler.setLocalMatrix(transform);
                shader.setInputShader(i==0?"content":"mip"+i,sampler);
                samplers[i]=sampler;
            }
            View targetPage=target.get();
            if (targetPage==null) return;
            float density=targetPage.getResources().getDisplayMetrics().density;
            RuntimeShader[] strips=new RuntimeShader[LEVELS];
            float[] boundaryDepths=new float[LEVELS+1];
            for (int i=0;i<LEVELS;i++) {
                RuntimeShader strip=new RuntimeShader(STRIP_PROGRAM);
                strip.setInputShader("low",samplers[i]);
                strip.setInputShader("high",samplers[i+1]);
                strip.setFloatUniform("pageWidth",pyramid.width);
                strip.setFloatUniform("density",density);
                strip.setFloatUniform("depths",DepthModel.FOCUS_DEPTH_DP,DepthModel.FULL_BLUR_DEPTH_DP,DepthModel.MAX_RADIUS_DP);
                strip.setFloatUniform("baseLod",i);
                strips[i]=strip;
                boundaryDepths[i]=DepthModel.depthForRadius(1.1547f*(1<<i)/density);
            }
            main.post(() -> {
                View page=target.get();
                if (stopped || pyramid.cancelled || page==null || pages.get(page)!=pyramid) return;
                pyramid.images=bitmaps; pyramid.shader=shader; pyramid.strips=strips;
                pyramid.boundaryDepths=boundaryDepths; pyramid.pending=false;
                Log.i("ColorDuoPyramid", "ready " + pyramid.width + "x" + pyramid.height
                        + " in " + (SystemClock.uptimeMillis()-started) + "ms (worker)");
                page.postInvalidateOnAnimation();
                if (onReady!=null) onReady.run();
            });
        } catch (Throwable error) { main.post(() -> fail(pyramid,error)); }
        finally { source.discardDisplayList(); if (renderer!=null) renderer.destroy(); }
    }

    private void fail(Pyramid pyramid, Throwable error) {
        pyramid.pending=false; pyramid.cancelled=true;
        Log.e("ColorDuoPyramid", "GPU pyramid failed; keep native page",error);
    }

    public boolean isRecording() { return recording; }

    public boolean canDraw(View page) {
        if (stopped || recording) return false;
        float density=page.getResources().getDisplayMetrics().density;
        if (DepthModel.spanDp(page.getWidth(),density,page.getRotationY())<=DepthModel.FOCUS_DEPTH_DP) return false;
        prepare(page,false);
        Pyramid pyramid=pages.get(page);
        return pyramid!=null && !pyramid.pending && !pyramid.cancelled && pyramid.shader!=null;
    }

    private Pyramid configure(View page) {
        if (!canDraw(page)) return null;
        Pyramid pyramid=pages.get(page);
        float density=page.getResources().getDisplayMetrics().density;
        float span=DepthModel.spanDp(page.getWidth(),density,page.getRotationY());
        page.getMatrix().getValues(matrix);
        float perspective=matrix[Matrix.MPERSP_0];
        if (!Float.isFinite(perspective) || perspective==0f) return null;
        pyramid.lastUsed=SystemClock.uptimeMillis();
        RuntimeShader shader=pyramid.shader;
        shader.setFloatUniform("pageWidth",page.getWidth());
        shader.setFloatUniform("spanDp",span);
        shader.setFloatUniform("farRight",perspective>0?1f:0f);
        shader.setFloatUniform("density",density);
        shader.setFloatUniform("depths",DepthModel.FOCUS_DEPTH_DP,DepthModel.FULL_BLUR_DEPTH_DP,DepthModel.MAX_RADIUS_DP);
        return pyramid;
    }

    /** Draw disjoint LOD regions, two samplers per fragment, no intermediate layer. */
    public boolean drawPage(View page, Canvas canvas) {
        if (!canvas.isHardwareAccelerated()) return false;
        Pyramid pyramid=configure(page);
        if (pyramid==null) return false;
        float span=DepthModel.spanDp(page.getWidth(),page.getResources().getDisplayMetrics().density,page.getRotationY());
        boolean farRight=matrix[Matrix.MPERSP_0]>0;
        float width=page.getWidth(), padding=pyramid.padding;
        for (int i=0;i<LEVELS;i++) {
            float near=i==0 ? -padding : DepthModel.lodBoundary(width,padding,pyramid.boundaryDepths[i],span);
            float far=i==LEVELS-1 ? width+padding : DepthModel.lodBoundary(width,padding,pyramid.boundaryDepths[i+1],span);
            if (far<=near) continue;
            RuntimeShader strip=pyramid.strips[i];
            strip.setFloatUniform("spanDp",span);
            strip.setFloatUniform("farRight",farRight?1f:0f);
            pyramid.paint.setShader(strip);
            canvas.drawRect(farRight?near:width-far,-padding,farRight?far:width-near,page.getHeight()+padding,pyramid.paint);
        }
        return true;
    }

    /** The standalone preview can still use a shader effect; launcher bypasses this path. */
    public RenderEffect forPage(View page) {
        Pyramid pyramid=configure(page);
        return pyramid==null ? null : RenderEffect.createShaderEffect(pyramid.shader);
    }
    public void release() {
        stopped=true;
        for (Pyramid pyramid:pages.values()) pyramid.cancelled=true;
        pages.clear(); onReady=null;
    }
}

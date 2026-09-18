package io.github.colorduo;

import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.view.View;

interface PageRenderer {
    void setOnReady(Runnable callback);
    void prepare(View page, boolean refresh);
    boolean isRecording();
    boolean canDraw(View page);
    boolean drawPage(View page, Canvas canvas);
    RenderEffect forPage(View page);
    void release();
    static PageRenderer create(int mode) {
        return EffectMode.normalize(mode)==EffectMode.GAUSSIAN
                ? new GaussianDepthBlurRenderer() : new DepthBlurRenderer();
    }
}

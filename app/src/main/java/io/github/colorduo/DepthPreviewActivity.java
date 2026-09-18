package io.github.colorduo;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Uses the same renderer as the launcher; also provides a safe shader smoke-test surface. */
public final class DepthPreviewActivity extends Activity {
    private PageRenderer renderer;
    private View card;
    private TextView caption;
    private float angle = 16.7f;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        renderer=PageRenderer.create(EffectSettings.readLocal(this));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int dp = (int) getResources().getDisplayMetrics().density;
        root.setPadding(16*dp, 48*dp, 16*dp, 28*dp);
        root.setBackgroundColor(Color.rgb(235, 240, 242));
        caption = new TextView(this);
        caption.setTextSize(18);
        root.addView(caption);
        card = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float d = getResources().getDisplayMetrics().density;
                float cell = getWidth() / 4f;
                for (int row = 0; row < 6; row++) {
                    for (int col = 0; col < 4; col++) {
                        float x = cell * (col + .5f);
                        float y = (row + .5f) * getHeight() / 6f;
                        paint.setColor(new int[]{0xffec694c,0xff389b9d,0xff5377bd,0xffa16bba}[col]);
                        canvas.drawRoundRect(x-23*d,y-30*d,x+23*d,y+16*d,11*d,11*d,paint);
                        paint.setColor(Color.WHITE); paint.setStrokeWidth(2*d);
                        for (int line = -2; line <= 2; line++) canvas.drawLine(x-13*d,y+line*5*d-7*d,x+13*d,y+line*5*d-7*d,paint);
                        paint.setColor(0xff20343b); paint.setTextSize(12*d); paint.setTextAlign(Paint.Align.CENTER);
                        canvas.drawText("Aa 123",x,y+34*d,paint);
                    }
                }
            }
        };
        root.addView(card, new LinearLayout.LayoutParams(-1, 0, 1));
        SeekBar control = new SeekBar(this);
        control.setMax(334); control.setProgress(334);
        root.addView(control);
        control.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                angle = (progress - 167) / 10f; refresh();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        renderer.setOnReady(this::refresh);
        setContentView(root);
        card.post(this::refresh);
    }
    @Override protected void onDestroy() {
        card.setRenderEffect(null); renderer.release(); super.onDestroy();
    }
    private void refresh() {
        try {
            card.setCameraDistance(8f * card.getWidth());
            card.setRotationY(angle);
            card.setRenderEffect(renderer.forPage(card));
            caption.setText(getString(R.string.depth_preview_caption, angle));
            Log.i("ColorDuoPreview", "Depth render applied; angle=" + angle);
        } catch (Throwable error) {
            card.setRenderEffect(null);
            caption.setText(R.string.depth_preview_error);
            Log.e("ColorDuoPreview", "Depth render failed", error);
        }
    }
}

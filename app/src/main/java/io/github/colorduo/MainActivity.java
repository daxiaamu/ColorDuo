package io.github.colorduo;
import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(28 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding * 2, padding, padding);
        TextView title = new TextView(this);
        title.setText(R.string.app_name); title.setTextSize(32);
        root.addView(title);
        TextView body = new TextView(this);
        body.setTextSize(17); body.setLineSpacing(12, 1);
        body.setText(R.string.instructions);
        root.addView(body);
        android.widget.Button preview = new android.widget.Button(this);
        preview.setText(R.string.depth_preview_button);
        preview.setOnClickListener(v -> startActivity(new android.content.Intent(this, DepthPreviewActivity.class)));
        root.addView(preview);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root); setContentView(scroll);
    }
}

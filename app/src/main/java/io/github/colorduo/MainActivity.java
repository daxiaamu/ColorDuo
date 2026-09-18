package io.github.colorduo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private RadioGroup choices;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding=Math.round(24*getResources().getDisplayMetrics().density);
        root.setPadding(padding,padding*2,padding,padding);
        TextView title=new TextView(this);
        title.setText(R.string.app_name); title.setTextSize(30); root.addView(title);
        TextView hint=new TextView(this);
        hint.setText(R.string.effect_hint); hint.setTextSize(16);
        hint.setPadding(0,padding/2,0,padding/2); root.addView(hint);
        choices=new RadioGroup(this);
        RadioButton gaussian=new RadioButton(this);
        gaussian.setId(R.id.effect_gaussian); gaussian.setText(R.string.effect_gaussian);
        gaussian.setTextSize(17); gaussian.setPadding(0,padding/2,0,padding/2);
        choices.addView(gaussian);
        RadioButton frost=new RadioButton(this);
        frost.setId(R.id.effect_frost); frost.setText(R.string.effect_frost);
        frost.setTextSize(17); frost.setPadding(0,padding/2,0,padding/2);
        choices.addView(frost);
        choices.check(EffectSettings.readLocal(this)==EffectMode.GAUSSIAN?R.id.effect_gaussian:R.id.effect_frost);
        root.addView(choices);
        TextView status=new TextView(this);
        status.setText(R.string.effect_switch_hint); status.setTextSize(14);
        status.setPadding(0,padding/2,0,padding/2); root.addView(status);
        choices.setOnCheckedChangeListener((group,id) -> {
            int mode=id==R.id.effect_gaussian?EffectMode.GAUSSIAN:EffectMode.FROST;
            EffectSettings.save(this,mode);
            status.setText(getString(R.string.effect_saved,mode));
        });
        Button preview=new Button(this);
        preview.setText(R.string.depth_preview_button);
        preview.setOnClickListener(v -> startActivity(new android.content.Intent(this,DepthPreviewActivity.class)));
        root.addView(preview);
        TextView body=new TextView(this);
        body.setTextSize(16); body.setLineSpacing(8,1); body.setText(R.string.instructions);
        body.setPadding(0,padding,0,0); root.addView(body);
        ScrollView scroll=new ScrollView(this); scroll.addView(root); setContentView(scroll);
    }
    @Override protected void onResume() {
        super.onResume();
        choices.check(EffectSettings.readLocal(this)==EffectMode.GAUSSIAN?R.id.effect_gaussian:R.id.effect_frost);
    }
}

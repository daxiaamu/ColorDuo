package io.github.colorduo.update;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.view.*;
import android.widget.*;
import java.time.ZoneId;
import java.time.format.*;
import java.util.Locale;

public final class UpdateUi implements UpdateManager.Listener {
    private final Activity activity; private final UpdateManager manager;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final TextView entry,dot; private final CheckBox beta;
    private final FrameLayout slot; private final TextView check; private final Ring checkRing;
    private AlertDialog dialog; private TextView details,primaryText; private Button skip,ignore;
    private Ring downloadRing; private LinearLayout primary; private String dialogIdentity="";
    private boolean resumed;
    private String displayedNotes="";
    private final Runnable automatic;
    public UpdateUi(Activity a,LinearLayout root) {
        activity=a;manager=UpdateManager.get(a);automatic=()->{if(resumed)manager.automatic();};
        LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);
        entry=new TextView(a);entry.setText(activity.getString(io.github.colorduo.R.string.update_check_version,manager.currentName));entry.setTextSize(16);
        row.addView(entry,new LinearLayout.LayoutParams(0,dp(56),1));entry.setGravity(Gravity.CENTER_VERTICAL);
        dot=new TextView(a);dot.setText("●");dot.setTextColor(Color.rgb(210,35,45));dot.setContentDescription("有可用更新");row.addView(dot);
        slot=new FrameLayout(a);row.addView(slot,new LinearLayout.LayoutParams(dp(64),dp(48)));
        check=new TextView(a);check.setText("检查");check.setGravity(Gravity.CENTER);check.setTextColor(Color.rgb(30,90,170));slot.addView(check,new FrameLayout.LayoutParams(-1,-1));
        checkRing=new Ring(a);FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER);slot.addView(checkRing,rp);
        View.OnClickListener click=v->manager.check(true);slot.setOnClickListener(click);entry.setOnClickListener(click);
        root.addView(row);
        beta=new CheckBox(a);beta.setText("接收预发布版本");beta.setChecked(manager.beta());beta.setOnCheckedChangeListener((b,on)->{if(on!=manager.beta())manager.setBeta(on);});root.addView(beta);
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    public void resume(){resumed=true;manager.attach(this);if(manager.awaitingPermission){manager.awaitingPermission=false;if(activity.getPackageManager().canRequestPackageInstalls())manager.downloadOrInstall();else{manager.downloadError="未允许安装应用，授权后可继续安装。";changed();}}handler.postDelayed(automatic,2000);}
    public void pause(){resumed=false;handler.removeCallbacks(automatic);manager.detach(this);}
    public void destroy(){pause();if(dialog!=null)dialog.dismiss();}
    @Override public void changed() {
        if(!resumed||activity.isFinishing()||activity.isDestroyed())return;
        check.setVisibility(manager.checking?View.INVISIBLE:View.VISIBLE);checkRing.setVisibility(manager.checking?View.VISIBLE:View.INVISIBLE);
        slot.setEnabled(!manager.checking);dot.setVisibility(manager.dot?View.VISIBLE:View.INVISIBLE);
        beta.setEnabled(!manager.checking&&!manager.working&&!manager.required());beta.setChecked(manager.beta());
        String message=manager.consumeMessage();if(!message.isEmpty())Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
        if(manager.offer&&manager.known!=null) {
            String key=manager.known.code+":"+manager.known.sha;
            if(dialog==null||!dialog.isShowing()||!key.equals(dialogIdentity)){if(dialog!=null)dialog.dismiss();showDialog(key);}
            updateDialog();
        } else if(dialog!=null&&dialog.isShowing())dialog.dismiss();
        if(manager.installRequested){manager.installRequested=false;launchInstaller();}
    }
    private void showDialog(String key) {
        dialogIdentity=key;displayedNotes="";
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(8),dp(20),dp(12));
        ScrollView scroll=new ScrollView(activity);details=new TextView(activity);details.setTextSize(15);details.setTextIsSelectable(true);details.setMovementMethod(LinkMovementMethod.getInstance());details.setPadding(0,0,0,dp(12));scroll.addView(details);
        int available=activity.getResources().getDisplayMetrics().heightPixels;
        body.addView(scroll,new LinearLayout.LayoutParams(-1,Math.max(dp(80),(int)(available*.48f))));
        LinearLayout actions=new LinearLayout(activity);actions.setGravity(Gravity.CENTER_VERTICAL);
        skip=new Button(activity);skip.setText("跳过此版本");skip.setTextSize(12);skip.setPadding(0,0,0,0);actions.addView(skip,new LinearLayout.LayoutParams(0,dp(52),1));
        ignore=new Button(activity);ignore.setText("忽略");ignore.setTextSize(12);ignore.setPadding(0,0,0,0);actions.addView(ignore,new LinearLayout.LayoutParams(0,dp(52),.65f));
        primary=new LinearLayout(activity);primary.setGravity(Gravity.CENTER);primary.setPadding(dp(4),0,dp(4),0);primary.setBackgroundColor(Color.rgb(224,236,252));primary.setFocusable(true);
        downloadRing=new Ring(activity);primary.addView(downloadRing,new LinearLayout.LayoutParams(dp(20),dp(20)));
        primaryText=new TextView(activity);primaryText.setTextSize(12);primaryText.setGravity(Gravity.CENTER);primaryText.setPadding(dp(8),0,0,0);primary.addView(primaryText);
        actions.addView(primary,new LinearLayout.LayoutParams(dp(136),dp(52)));body.addView(actions);
        skip.setOnClickListener(v->manager.dismiss(true));ignore.setOnClickListener(v->manager.dismiss(false));primary.setOnClickListener(v->manager.downloadOrInstall());
        dialog=new AlertDialog.Builder(activity).setTitle(manager.required()?"需要更新":"发现新版本").setView(body).create();
        dialog.setOnCancelListener(d->manager.dismiss(false));dialog.show();
    }
    private void updateDialog() {
        UpdateManifest m=manager.known;String date="";
        java.time.Instant time=UpdateManifest.time(m.published);
        if(time!=null)date="\n发布时间："+DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(time);
        String text="## "+m.name+date+"\n\n"+m.notes+"\n\n"+manager.downloadError;
        if(!text.equals(displayedNotes)){displayedNotes=text;details.setText(UpdateMarkdown.render(text));}
        boolean required=manager.required();dialog.setCancelable(!required&&!manager.working);dialog.setCanceledOnTouchOutside(false);
        skip.setVisibility(required?View.GONE:View.VISIBLE);ignore.setVisibility(required?View.GONE:View.VISIBLE);skip.setEnabled(!manager.working);ignore.setEnabled(!manager.working);
        primary.setEnabled(!manager.working);primary.setContentDescription(manager.working?manager.phase:"下载安装");downloadRing.setVisibility(manager.working?View.VISIBLE:View.GONE);downloadRing.value=manager.progress;downloadRing.invalidate();
        primaryText.setText(manager.working?(manager.progress<0?manager.phase:manager.progress+"%"):manager.downloadError.isEmpty()?"下载安装":"重试安装");
    }
    private void launchInstaller() {
        try {
            if(!activity.getPackageManager().canRequestPackageInstalls()) {
                manager.awaitingPermission=true;
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));
                return;
            }
            Uri uri=manager.installUri();Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("APK",uri));activity.startActivity(intent);
        } catch(Exception e){manager.awaitingPermission=false;manager.downloadError="无法打开系统安装器，请重试。";updateDialog();}
    }
    private static final class Ring extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);int value=-1;
        Ring(Context c){super(c);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*c.getResources().getDisplayMetrics().density);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(Color.rgb(30,90,170));setContentDescription("更新进度");}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float pad=paint.getStrokeWidth();float start=value<0?(SystemClock.uptimeMillis()%1200)*.3f:-90;float sweep=value<0?260:360*value/100f;c.drawArc(pad,pad,getWidth()-pad,getHeight()-pad,start,sweep,false,paint);if(value<0&&isShown())postInvalidateDelayed(32);}
    }
}

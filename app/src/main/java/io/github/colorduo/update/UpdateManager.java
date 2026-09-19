package io.github.colorduo.update;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.*;
import java.lang.ref.WeakReference;
import io.github.colorduo.BuildConfig;

/** Process-wide session; retains work across Activity recreation, never references launcher hooks. */
public final class UpdateManager {
    public interface Listener { void changed(); }
    @android.annotation.SuppressLint("StaticFieldLeak") // Stores application context only.
    private static UpdateManager instance;
    public static synchronized UpdateManager get(Context c) { if(instance==null)instance=new UpdateManager(c.getApplicationContext());return instance; }
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private WeakReference<Listener> listener=new WeakReference<>(null);
    public final long currentCode; public final String currentName;
    public UpdateManifest known;
    public boolean checking,working,offer,dot,awaitingPermission,installRequested;
    public int progress=-1;
    public String phase="",message="",downloadError="";
    private boolean autoStarted;
    private final CheckSession session=new CheckSession();
    private UpdateManager(Context c) {
        context=c; prefs=c.getSharedPreferences("updates",Context.MODE_PRIVATE);
        try {PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),0);currentCode=p.getLongVersionCode();currentName=p.versionName;}catch(Exception e){throw new IllegalStateException(e);}
        for(String channel:beta()?new String[]{"stable","beta"}:new String[]{"stable"}) {
            try { UpdateManifest m=new UpdateManifest(prefs.getString(channel+".manifest",""),channel); if(m.code>currentCode&&(known==null||m.code>known.code))known=m; }catch(Exception ignored){}
        }
        dot=known!=null; offer=required();
    }
    public boolean beta(){return prefs.getBoolean("beta",BuildConfig.UPDATE_BETA);}
    public void setBeta(boolean value) {if(checking||working||required())return;prefs.edit().putBoolean("beta",value).apply();known=null;offer=false;dot=false;check(true);}
    public boolean required(){return known!=null&&known.code>currentCode&&currentCode<=Math.max(prefs.getLong("stable.forced",0),beta()?prefs.getLong("beta.forced",0):0);}
    public void attach(Listener l){listener=new WeakReference<>(l);l.changed();}
    public void detach(Listener l){if(listener.get()==l)listener.clear();}
    private void notifyUi(){Listener l=listener.get();if(l!=null)l.changed();}
    public void automatic(){if(autoStarted)return;autoStarted=true;check(false);}
    public void check(boolean manual) {
        if(working){message="正在处理安装包";notifyUi();return;}
        if(!session.begin(manual)){notifyUi();return;}
        checking=true;message="";notifyUi();
        boolean includeBeta=beta();
        worker.execute(()->{
            UpdateManifest best=null; Exception error=null;
            for(String channel:includeBeta?new String[]{"stable","beta"}:new String[]{"stable"}) {
                try {UpdateManifest m=UpdateNetwork.check(channel,prefs);if(best==null||m.code>best.code)best=m;}
                catch(Exception e){error=e;}
            }
            // A failed channel must not silently clear a previously known forced boundary.
            final UpdateManifest result=best; final Exception failure=error;
            main.post(()->{
                checking=false;boolean visible=session.finish();
                if(failure!=null){
                    if(result!=null&&result.code>currentCode&&(known==null||result.code>known.code))known=result;
                    message=visible?"检查未完成，请检查网络后重试":"";dot=known!=null;if(required())offer=true;
                }
                else if(result!=null) {
                    known=result.code>currentCode?result:null;dot=known!=null;
                    if(known!=null){offer=required()||visible||prefs.getLong("skipped",0)!=known.code;downloadError="";}
                    else {offer=false;message=visible?"已是当前渠道最新版本":"";}
                }
                notifyUi();
            });
        });
    }
    public void dismiss(boolean skip){if(required()||working)return;if(skip&&known!=null)prefs.edit().putLong("skipped",known.code).apply();offer=false;notifyUi();}
    public String consumeMessage(){String s=message;message="";return s;}
    File file(UpdateManifest m){return new File(new File(context.getCacheDir(),"updates"),m.code+"-"+m.sha+".apk");}
    public void downloadOrInstall() {
        if(known==null||working)return;
        final UpdateManifest target=known;
        working=true;installRequested=false;progress=-1;phase="校验安装包";downloadError="";notifyUi();
        worker.execute(()->{
            try {
                File apk=file(target);
                if(apk.exists())try{verify(apk,target);}catch(Exception e){if(!apk.delete())throw new IOException("无法移除失效安装包");}
                if(!apk.exists())download(target,apk);
                verify(apk,target); // Every attempt, including returning from permission, rehashes current manifest bytes.
                main.post(()->{working=false;progress=100;phase="安装";installRequested=true;notifyUi();});
            } catch(Exception e) {main.post(()->{working=false;phase="重试下载";downloadError="下载或校验失败，请重试。";notifyUi();});}
        });
    }
    private void download(UpdateManifest m,File apk) throws Exception {
        File dir=apk.getParentFile();if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建下载目录");
        File temp=new File(dir,"update.download"); Exception last=null;
        for(String url:m.urls) {
            HttpURLConnection c=null;
            try {
                c=UpdateNetwork.open(url);long length=c.getContentLengthLong();if(length>0&&length!=m.size)throw new IOException("文件长度不符");
                long total=0,lastUi=0,deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
                try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(temp)) {
                    byte[] buf=new byte[65536];int n;
                    while((n=in.read(buf))!=-1) {
                        if((total+=n)>m.size||System.nanoTime()>deadline)throw new IOException("下载超限");out.write(buf,0,n);
                        long now=SystemClock.elapsedRealtime();if(now-lastUi>=300){lastUi=now;final int p=length>0?(int)Math.min(99,total*100/m.size):-1;main.post(()->{progress=p;phase="下载中";notifyUi();});}
                    }
                    out.getFD().sync();
                }
                if(total!=m.size)throw new IOException("下载不完整");
                verify(temp,m);
                if(!temp.renameTo(apk))throw new IOException("无法保存安装包");
                return;
            }catch(Exception e){last=e;if(temp.exists()&&!temp.delete())throw new IOException("无法清理临时文件",e);}
            finally{if(c!=null)c.disconnect();}
        }
        throw new IOException("所有下载源均失败",last);
    }
    private void verify(File f,UpdateManifest m) throws Exception {
        if(f.length()!=m.size||!UpdateNetwork.hash(f).equals(m.sha))throw new IOException("SHA-256 不匹配");
        PackageManager pm=context.getPackageManager();
        PackageInfo archive=pm.getPackageArchiveInfo(f.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null||!context.getPackageName().equals(archive.packageName)||archive.getLongVersionCode()!=m.code||archive.signingInfo==null||installed.signingInfo==null)throw new IOException("APK 身份不匹配");
        Set<String> a=signers(archive.signingInfo),b=signers(installed.signingInfo);
        if(a.isEmpty()||!a.equals(b))throw new IOException("APK 签名不匹配");
    }
    private static Set<String> signers(SigningInfo info) throws Exception {Set<String> out=new HashSet<>();for(Signature s:info.getApkContentsSigners())out.add(UpdateNetwork.hash(s.toByteArray()));return out;}
    public android.net.Uri installUri(){return android.net.Uri.parse("content://"+context.getPackageName()+".updates/"+file(known).getName());}
}

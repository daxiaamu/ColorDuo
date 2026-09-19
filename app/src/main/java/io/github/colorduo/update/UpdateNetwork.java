package io.github.colorduo.update;

import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

final class UpdateNetwork {
    static final String REPO="daxiaamu/ColorDuo";
    static List<String> sources(String path) {
        return Arrays.asList("https://api.github.com/repos/"+REPO+"/contents/"+path+"?ref=main",
            "https://raw.githubusercontent.com/"+REPO+"/main/"+path,
            "https://cdn.jsdelivr.net/gh/"+REPO+"@main/"+path,
            "https://fastly.jsdelivr.net/gh/"+REPO+"@main/"+path,
            "https://gcore.jsdelivr.net/gh/"+REPO+"@main/"+path,
            "https://testingcf.jsdelivr.net/gh/"+REPO+"@main/"+path,
            "https://cdn.statically.io/gh/"+REPO+"/main/"+path);
    }
    static final class Pointer {
        final long revision; final String path,sha,expires,digest; final UpdatePolicy.Vote vote;
        Pointer(String text,String channel,String source,boolean authority) throws Exception {
            JSONObject o=new JSONObject(text);
            if(UpdateManifest.number(o,"schemaVersion")!=1||!o.getString("channel").equals(channel)) throw new IOException("更新指针无效");
            revision=UpdateManifest.number(o,"policyRevision"); path=o.getString("manifestPath"); sha=o.getString("manifestSha256"); expires=o.getString("expiresAt");
            if(revision<1||!sha.matches("[0-9a-f]{64}")||!path.equals("updates/manifests/"+channel+"-"+revision+"-"+sha+".json")) throw new IOException("非不可变清单");
            Instant expiry=UpdateManifest.time(expires);
            if(expiry==null||!expiry.isAfter(Instant.now())) throw new IOException("更新指针已过期");
            digest=hash((channel+"\n"+revision+"\n"+path+"\n"+sha+"\n"+expiry).getBytes(StandardCharsets.UTF_8));
            vote=new UpdatePolicy.Vote(source,digest,revision,authority);
        }
    }
    static UpdateManifest check(String channel,SharedPreferences prefs) throws Exception {
        List<String> urls=sources("updates/"+channel+"/latest.json");
        ExecutorService pool=Executors.newFixedThreadPool(urls.size());
        CompletionService<Pointer> completed=new ExecutorCompletionService<>(pool);
        List<Pointer> pointers=new ArrayList<>();
        for(int i=0;i<urls.size();i++) { final int index=i; completed.submit(()->new Pointer(new String(read(urls.get(index),1048576),StandardCharsets.UTF_8),channel,new URI(urls.get(index)).getHost(),index==0)); }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(24);
        try {
            for(int i=0;i<urls.size();i++) {
                Future<Pointer> f=completed.poll(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if(f==null) break;
                try { Pointer p=f.get(); pointers.add(p); if(p.vote.authority) deadline=Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(800)); } catch(ExecutionException ignored) { }
            }
        } finally { pool.shutdownNow(); }
        List<UpdatePolicy.Vote> votes=new ArrayList<>(); for(Pointer p:pointers) votes.add(p.vote);
        UpdatePolicy.Vote choice=UpdatePolicy.select(votes,prefs.getLong(channel+".revision",0),prefs.getString(channel+".digest",""));
        Pointer selected=null; for(Pointer p:pointers) if(p.vote==choice) selected=p;
        if(selected==null) throw new IOException("没有有效更新清单");
        Exception last=null;
        for(String url:sources(selected.path)) {
            try {
                byte[] bytes=read(url,1048576);
                if(!hash(bytes).equals(selected.sha)) throw new IOException("更新清单校验失败");
                UpdateManifest m=new UpdateManifest(new String(bytes,StandardCharsets.UTF_8),channel);
                if(m.revision!=selected.revision||m.code<prefs.getLong(channel+".code",0)||m.forced<prefs.getLong(channel+".forced",0)) throw new IOException("更新版本或强制边界回退");
                if(!prefs.edit().putLong(channel+".revision",m.revision).putString(channel+".digest",selected.digest).putLong(channel+".code",m.code).putLong(channel+".forced",m.forced).putString(channel+".manifest",m.json).commit()) throw new IOException("无法保存更新校验状态");
                return m;
            } catch(Exception e) { last=e; }
        }
        throw new IOException("更新清单获取失败",last);
    }
    static HttpURLConnection open(String address) throws Exception {
        String url=address;
        for(int i=0;i<6;i++) {
            UpdateManifest.https(url);
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(7000); c.setReadTimeout(10000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent","ColorDuo-Updater/1"); c.setRequestProperty("Cache-Control","no-cache");
            c.setRequestProperty("Accept","application/vnd.github.raw+json");
            int code=c.getResponseCode();
            if(code>=300&&code<400) { String location=c.getHeaderField("Location"); c.disconnect(); if(location==null) throw new IOException("无效重定向"); url=new URL(new URL(url),location).toString(); continue; }
            if(code<200||code>=300) {c.disconnect(); throw new IOException("HTTP "+code);}
            return c;
        }
        throw new IOException("重定向过多");
    }
    static byte[] read(String address,int limit) throws Exception {
        String url=address+(address.contains("?")?"&":"?")+"_="+System.currentTimeMillis();
        HttpURLConnection c=open(url);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if(c.getContentLengthLong()>limit) throw new IOException("响应过大");
            byte[] buffer=new byte[8192]; int n;
            while((n=in.read(buffer))!=-1) { if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline||out.size()+n>limit) throw new IOException("响应超限"); out.write(buffer,0,n); }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }
    static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String hash(File file) throws Exception { MessageDigest d=MessageDigest.getInstance("SHA-256"); try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536]; int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest()); }
    static String hex(byte[] bytes) {StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
}

package io.github.colorduo.update;

import org.json.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

public final class UpdateManifest {
    public final long code, revision, forced, size;
    public final String name, channel, sha, notes, published;
    public final List<String> urls;
    public final String json;
    public UpdateManifest(String text, String expectedChannel) throws Exception {
        JSONObject o=new JSONObject(text);
        if (number(o,"schemaVersion")!=1) throw new IllegalArgumentException("不支持的更新格式");
        channel=o.getString("channel");
        if (!channel.equals(expectedChannel) || !(channel.equals("stable")||channel.equals("beta"))) throw new IllegalArgumentException("更新渠道不匹配");
        code=number(o,"versionCode"); revision=number(o,"policyRevision"); forced=number(o,"maxForcedVersionCode"); size=number(o,"size");
        if(code<=0||revision<=0||forced<0||forced>=code||size<=0||size>134217728L) throw new IllegalArgumentException("更新数值无效");
        name=o.getString("versionName"); sha=o.getString("sha256");
        if(name.isEmpty()||name.length()>80||!sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("更新校验信息无效");
        notes=o.optString("changelog", ""); published=o.optString("publishedAt", "");
        JSONArray a=o.getJSONArray("urls");
        if(a.length()<6||a.length()>20) throw new IllegalArgumentException("下载源数量不符合要求");
        LinkedHashSet<String> unique=new LinkedHashSet<>(); Set<String> hosts=new HashSet<>();
        for(int i=0;i<a.length();i++) { String u=a.getString(i); URI uri=https(u); unique.add(u); if(!uri.getHost().equalsIgnoreCase("github.com")) hosts.add(uri.getHost().toLowerCase(Locale.ROOT)); }
        if(unique.size()!=a.length()||hosts.size()<5) throw new IllegalArgumentException("至少需要五个不同 CDN 主机");
        urls=Collections.unmodifiableList(new ArrayList<>(unique)); json=text;
    }
    public boolean required(long current) { return current<=forced && code>current; }
    public static URI https(String s) {
        URI u=URI.create(s);
        if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null) throw new IllegalArgumentException("更新地址必须为 HTTPS");
        return u;
    }
    public static long number(JSONObject o,String key) throws Exception {
        Object v=o.get(key);
        if(!(v instanceof Integer)&&!(v instanceof Long)) throw new IllegalArgumentException("非整数: "+key);
        return ((Number)v).longValue();
    }
    public static Instant time(String value) { try { return Instant.parse(value); } catch(Exception e) { try{return java.time.OffsetDateTime.parse(value).toInstant();}catch(Exception ignored){return null;} } }
}

package io.github.colorduo.update;

import java.util.*;

/** Deterministic trust selection, independent from UI/network timing. */
public final class UpdatePolicy {
    public static final class Vote {
        public final String source,digest; public final long revision; public final boolean authority;
        public Vote(String source,String digest,long revision,boolean authority){this.source=source;this.digest=digest;this.revision=revision;this.authority=authority;}
    }
    public static Vote select(List<Vote> votes,long accepted,String acceptedDigest) {
        Map<Long,String> digests=new HashMap<>();
        for(Vote v:votes) {
            String prev=digests.putIfAbsent(v.revision,v.digest);
            if(prev!=null&&!prev.equals(v.digest)) throw new IllegalArgumentException("同一修订存在冲突，请稍后重试");
            if(v.revision==accepted&&!acceptedDigest.isEmpty()&&!acceptedDigest.equals(v.digest)) throw new IllegalArgumentException("已接受修订的内容发生变化");
        }
        for(Vote v:votes) if(v.authority) { if(v.revision<accepted) throw new IllegalArgumentException("权威源版本回退"); return v; }
        Vote best=null;
        for(Vote v:votes) if(v.revision>=accepted) {
            Set<String> domains=new HashSet<>();
            for(Vote peer:votes) if(peer.revision==v.revision&&peer.digest.equals(v.digest)) domains.add(domain(peer.source));
            if(domains.size()>=2&&(best==null||v.revision>best.revision)) best=v;
        }
        if(best==null) throw new IllegalArgumentException("无法取得可信更新信息，请稍后重试");
        return best;
    }
    static String domain(String host) { if(host.endsWith(".jsdelivr.net")) return "jsdelivr.net"; return host; }
}

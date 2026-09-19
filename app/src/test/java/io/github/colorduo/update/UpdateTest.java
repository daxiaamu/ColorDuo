package io.github.colorduo.update;

import org.junit.Test;
import org.json.*;
import java.util.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class UpdateTest {
    static UpdatePolicy.Vote v(String host,String hash,long rev,boolean authority){return new UpdatePolicy.Vote(host,hash,rev,authority);}
    @Test public void authorityWinsOverFasterMirrors(){UpdatePolicy.Vote a=v("api.github.com","new",3,true);assertSame(a,UpdatePolicy.select(Arrays.asList(v("cdn.example","old",2,false),a),0,""));}
    @Test(expected=IllegalArgumentException.class) public void rejectsSameRevisionConflict(){UpdatePolicy.select(Arrays.asList(v("a","x",2,true),v("b","y",2,false)),0,"");}
    @Test(expected=IllegalArgumentException.class) public void rejectsRollback(){UpdatePolicy.select(Arrays.asList(v("a","x",1,true)),2,"y");}
    @Test(expected=IllegalArgumentException.class) public void rejectsAcceptedRevisionMutation(){UpdatePolicy.select(Arrays.asList(v("a","x",2,true)),2,"y");}
    @Test(expected=IllegalArgumentException.class) public void oneMirrorNotTrusted(){UpdatePolicy.select(Arrays.asList(v("a","x",2,false)),0,"");}
    @Test(expected=IllegalArgumentException.class) public void jsDelivrAliasesAreNotIndependent(){UpdatePolicy.select(Arrays.asList(v("fastly.jsdelivr.net","x",2,false),v("gcore.jsdelivr.net","x",2,false)),0,"");}
    @Test public void independentFallbackSelectsHighest(){assertEquals(3,UpdatePolicy.select(Arrays.asList(v("a","x",3,false),v("b","x",3,false),v("c","y",2,false)),0,"").revision);}
    @Test public void manualJoinsAutoAndGetsResult(){CheckSession s=new CheckSession();assertTrue(s.begin(false));assertFalse(s.begin(true));assertTrue(s.finish());}
    @Test public void autoJoinsManual(){CheckSession s=new CheckSession();assertTrue(s.begin(true));assertFalse(s.begin(false));assertTrue(s.finish());}
    @Test public void manualAfterCompletionStartsFresh(){CheckSession s=new CheckSession();assertTrue(s.begin(false));assertFalse(s.finish());assertTrue(s.begin(true));assertTrue(s.finish());}
    JSONObject fixture() throws Exception {JSONObject o=new JSONObject();o.put("schemaVersion",1);o.put("channel","beta");o.put("versionCode",14);o.put("versionName","0.5.1-beta.1");o.put("policyRevision",2);o.put("maxForcedVersionCode",0);o.put("sha256",String.join("",Collections.nCopies(64,"a")));o.put("size",100);JSONArray urls=new JSONArray();for(int i=0;i<5;i++)urls.put("https://cdn"+i+".example/apk");urls.put("https://github.com/a.apk");o.put("urls",urls);return o;}
    @Test public void validManifestAndForcedBoundary() throws Exception {JSONObject o=fixture();o.put("maxForcedVersionCode",13);UpdateManifest m=new UpdateManifest(o.toString(),"beta");assertTrue(m.required(13));assertFalse(m.required(14));}
    @Test(expected=Exception.class) public void rejectsWrongChannel() throws Exception {new UpdateManifest(fixture().toString(),"stable");}
    @Test(expected=Exception.class) public void rejectsMissingHash() throws Exception {JSONObject o=fixture();o.remove("sha256");new UpdateManifest(o.toString(),"beta");}
    @Test(expected=Exception.class) public void rejectsHttpDownload() throws Exception {JSONObject o=fixture();o.getJSONArray("urls").put(0,"http://cdn.example/a");new UpdateManifest(o.toString(),"beta");}
    @Test(expected=Exception.class) public void rejectsCdnHostDuplicates() throws Exception {JSONObject o=fixture();o.getJSONArray("urls").put(1,"https://cdn0.example/other");new UpdateManifest(o.toString(),"beta");}
    @Test(expected=Exception.class) public void rejectsInvalidForcedBoundary() throws Exception {JSONObject o=fixture();o.put("maxForcedVersionCode",14);new UpdateManifest(o.toString(),"beta");}
    @Test(expected=Exception.class) public void rejectsStringVersion() throws Exception {JSONObject o=fixture();o.put("versionCode","14");new UpdateManifest(o.toString(),"beta");}
    @Test public void handlesBadAndOffsetDates(){assertNull(UpdateManifest.time("bad"));assertNull(UpdateManifest.time("2026-09-20T10:00:00"));assertEquals(UpdateManifest.time("2026-09-20T02:00:00Z"),UpdateManifest.time("2026-09-20T10:00:00+08:00"));}
    @Test public void changedApkHasDifferentHash() throws Exception {Path file=Files.createTempFile("colorduo-update",".apk");try{Files.write(file,new byte[]{1,2});String first=UpdateNetwork.hash(file.toFile());Files.write(file,new byte[]{1,3});assertNotEquals(first,UpdateNetwork.hash(file.toFile()));}finally{Files.delete(file);}}
}

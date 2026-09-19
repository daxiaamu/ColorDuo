#!/usr/bin/env python3
"""Publish metadata only after release identity and >=5 complete CDN copies are verified."""
import argparse, concurrent.futures, datetime as dt, hashlib, json, os, re, subprocess, tempfile, time
from pathlib import Path
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.parse import urlparse

REPO = 'daxiaamu/ColorDuo'
CERT = '4929d9e532d1bd29997e2e49672eab3c51c4c171fa5a8620187e373276dc315e'
class HttpsRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlparse(newurl).scheme != 'https': raise ValueError('Insecure redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def fetch(url, limit=134217728, timeout=20):
    if urlparse(url).scheme != 'https': raise ValueError('HTTPS required')
    req=Request(url,headers={'User-Agent':'ColorDuo-release-updater','Cache-Control':'no-cache'})
    with build_opener(HttpsRedirect).open(req,timeout=timeout) as r:
        if not 200<=r.status<300: raise ValueError('HTTP status')
        chunks=[];total=0;deadline=time.monotonic()+90
        while True:
            b=r.read(65536)
            if not b: break
            chunks.append(b);total+=len(b)
            if total>limit or time.monotonic()>deadline: raise ValueError('Response limit')
        return b''.join(chunks)

def digest(data): return hashlib.sha256(data).hexdigest()
def canonical(obj): return (json.dumps(obj,ensure_ascii=False,sort_keys=True,indent=2)+'\n').encode()
def candidates(official, tag, apk):
    hosts=['ghfast.top','gh-proxy.com','ghproxy.net','gh.llkk.cc','ghp.keleyaa.com','gh.monlor.com','ghproxy.vip','gh.jasonzeng.dev','gh.3w.pm','gh-proxy.org','v6.gh-proxy.com','v6.gh-proxy.org']
    return ['https://'+h+'/'+official for h in hosts]+['https://xget.xi-xu.me/gh/'+REPO+'/releases/download/'+tag+'/'+apk]

def check_cdn(url,sha,size):
    try:
        start=time.monotonic();data=fetch(url,limit=size)
        if len(data)!=size or digest(data)!=sha: raise ValueError('APK SHA/size mismatch')
        return (time.monotonic()-start,url)
    except Exception as e:
        print('CDN rejected:',urlparse(url).hostname,type(e).__name__,str(e),flush=True)
        return None

def validate(m):
    for key in ['schemaVersion','versionCode','size','maxForcedVersionCode','policyRevision']:
        if type(m[key]) is not int: raise ValueError('Not integer: '+key)
    if m['schemaVersion']!=1 or m['channel'] not in ['stable','beta']: raise ValueError('Schema/channel')
    if not 0<=m['maxForcedVersionCode']<m['versionCode'] or m['policyRevision']<1: raise ValueError('Invalid policy')
    if not re.fullmatch('[0-9a-f]{64}',m['sha256']): raise ValueError('Missing hash')
    urls=m['urls'];hosts={urlparse(u).hostname for u in urls if urlparse(u).hostname!='github.com'}
    if len(urls)!=len(set(urls)) or len(hosts)<5 or any(urlparse(u).scheme!='https' for u in urls): raise ValueError('At least five distinct CDN hosts required')
    dt.datetime.fromisoformat(m['publishedAt'].replace('Z','+00:00'))

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--tag',required=True);ap.add_argument('--aapt',required=True);ap.add_argument('--apksigner',required=True);args=ap.parse_args()
    if not re.fullmatch(r'v[0-9A-Za-z.+-]+',args.tag): raise ValueError('Invalid tag')
    release=json.loads(subprocess.check_output(['gh','api','repos/'+REPO+'/releases/tags/'+args.tag],text=True,encoding='utf-8'))
    if release['draft']: raise ValueError('Draft release')
    assets=[a for a in release['assets'] if a['name'].endswith('.apk')]
    if len(assets)!=1: raise ValueError('Exactly one APK asset is required')
    asset=assets[0];official=asset['browser_download_url'];channel='beta' if release['prerelease'] else 'stable'
    data=fetch(official);sha=digest(data);size=len(data)
    if size!=asset['size']: raise ValueError('Asset size mismatch')
    with tempfile.TemporaryDirectory() as temp:
        apk=Path(temp)/'release.apk';apk.write_bytes(data)
        badging=subprocess.check_output([args.aapt,'dump','badging',str(apk)],text=True,encoding='utf-8')
        match=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
        if not match or match[1]!='io.github.colorduo' or args.tag!='v'+match[3]: raise ValueError('APK/tag identity mismatch')
        code=int(match[2]);name=match[3]
        if ('-' in name)!=(channel=='beta'): raise ValueError('Release/APK channel mismatch')
        signer=subprocess.check_output([args.apksigner,'verify','--print-certs',str(apk)],text=True,encoding='utf-8')
        if CERT not in signer.lower(): raise ValueError('Signer changed')
    policy=json.loads(Path('updates/policy.json').read_text())[channel]
    if not policy.get('reason'): raise ValueError('Policy change reason required')
    revision=policy['policyRevision']
    target=Path('updates')/channel/'latest.json'
    if target.exists():
        old=json.loads(target.read_text());old_manifest=json.loads(Path(old['manifestPath']).read_text())
        if code<old_manifest['versionCode'] or policy['maxForcedVersionCode']<old_manifest['maxForcedVersionCode']: raise ValueError('Rollback denied')
        if revision<=old['policyRevision']: raise ValueError('Increment policyRevision before changing a published manifest')
    good=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=13) as pool:
        for result in pool.map(lambda url:check_cdn(url,sha,size),candidates(official,args.tag,asset['name'])):
            if result: good.append(result)
    good.sort();urls=[url for _,url in good]
    if len({urlparse(u).hostname for u in urls})<5: raise ValueError('Fewer than five verified CDN hosts; previous metadata remains unchanged')
    manifest={'schemaVersion':1,'channel':channel,'versionCode':code,'versionName':name,'publishedAt':release['published_at'],'changelog':release['body'] or '',
        'maxForcedVersionCode':policy['maxForcedVersionCode'],'policyRevision':revision,'urls':urls+[official],'url':official,'sha256':sha,'size':size,'releaseUrl':release['html_url']}
    validate(manifest)
    body=canonical(manifest);manifest_hash=digest(body)
    manifest_path='updates/manifests/'+channel+'-'+str(revision)+'-'+manifest_hash+'.json'
    expires=(dt.datetime.now(dt.timezone.utc)+dt.timedelta(days=90)).isoformat(timespec='seconds').replace('+00:00','Z')
    pointer={'schemaVersion':1,'channel':channel,'policyRevision':revision,'manifestPath':manifest_path,'manifestSha256':manifest_hash,'expiresAt':expires}
    path=Path(manifest_path);path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists() and path.read_bytes()!=body: raise ValueError('Immutable manifest conflict')
    path.write_bytes(body)
    target.parent.mkdir(parents=True,exist_ok=True);tmp=target.with_suffix('.tmp');tmp.write_bytes(canonical(pointer));os.replace(tmp,target)
    print('Published local metadata:',target,'verified CDN hosts:',len(good),flush=True)

if __name__=='__main__': main()

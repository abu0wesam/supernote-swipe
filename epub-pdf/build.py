import zipfile,sys
ver,code,out=sys.argv[1:4]
P='/home/user/workspace/p18/'
old=open('/tmp/bk/js/module600.js').read()
head=old[:old.index("var ZK=")]
mod=head+open('/tmp/bk/js/ui_parts.js').read()+"\n"+open('/tmp/bk/js/body.js').read()+"\n},600,[119,2,499,529]);"
b=open(P+'SwipeSwitch.bundle').read()
i=b.rindex("__r(62);"); b=b[:i]+mod+"\n"+b[i:]
b=b.replace("if('convert'===c)return X((0,o.jsx)(u.default,{}","if('convert'===c)return X((0,o.jsx)(_r(d[10]).default,{}",1)
b=b.replace("},497,[1,38,119,498,529,0,251,526,537,2]);","},497,[1,38,119,498,529,0,251,526,537,2,600]);")
# keep-alive for motion listeners (swipes stopped working after the device slept)
A="registerMotionListener:function(t,n){return this.registerEventListener(r(d[7]).EventType.MOTION_EVENT,t,n)}"
assert b.count(A)==1
B="""registerMotionListener:function(t,n){var self=this,EV=r(d[7]).EventType.MOTION_EVENT,g=globalThis,rec={t:t,n:n,dead:false,h:self.registerEventListener(EV,t,n)};
if(!g.__motList){g.__motList=[];g.__motLast=0;
g.__motRefresh=function(){var now=Date.now();if(now-g.__motLast<1500)return;g.__motLast=now;g.__motList.forEach(function(x){if(x.dead)return;try{x.h.remove()}catch(e){}try{x.h=self.registerEventListener(EV,x.t,x.n)}catch(e){}})};
try{r(2).AppState.addEventListener('change',function(s){if('active'===s)g.__motRefresh()})}catch(e){}
try{self.registerPluginLifeListener({onMsg:function(){g.__motRefresh()}})}catch(e){}
try{r(2).DeviceEventEmitter.addListener('onWindowFocusChanged',function(){g.__motRefresh()})}catch(e){}}
g.__motList.push(rec);return {remove:function(){rec.dead=true;try{rec.h.remove()}catch(e){}}}}"""
b=b.replace(A,B)
open('/tmp/bk/b.js','w').write(b)
zi=zipfile.ZipFile(P+'app.npk')
with zipfile.ZipFile('/tmp/bk/app.npk','w',zipfile.ZIP_DEFLATED) as zo:
    for n in zi.namelist(): zo.writestr(zi.getinfo(n),zi.read(n))
    zo.write('/tmp/bk/dex/classes.dex','classes17.dex')
c=open(P+'PluginConfig.json').read().replace('"name": "PDF Tool"','"name": "Tools"').replace('"1.8.0"','"%s"'%ver).replace('"versionCode": "21"','"versionCode": "%s"'%code).replace('"com.swipeswitch.PdfToolPackage"','"com.swipeswitch.PdfToolPackage",\n    "com.swipeswitch.BookPdfPackage"')
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
    for n in ['icon_pdftool.png','drawable-mdpi/assets_icon_text.png','drawable-mdpi/assets_icon_pdftool.png']: z.write('/tmp/ic/gear.png' if 'pdftool' in n else P+n,n)
    z.write('/tmp/bk/app.npk','app.npk'); z.write('/tmp/bk/b.js','SwipeSwitch.bundle'); z.writestr('PluginConfig.json',c)

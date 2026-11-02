var BK=l.NativeModules.BookPdf;
var LV={size:[9,10,11,12,13,14,16,18,20,24],space:[1.0,1.1,1.2,1.3,1.4,1.5,1.6,1.7,1.8,1.9],side:[2,3,4,5,6,7,8,9,10,12],vert:[2,3,4,5,6,7,8,9,10,12]};
var DEF={size:4,space:5,side:6,vert:5,weight:1,fontIdx:0,align:'justify',dir:'auto',en:{size:true,space:true,side:true,vert:true,weight:true,font:true,align:true,dir:true}};
var BASE_FONTS=[{label:'Default',path:'default'},{label:'Serif',path:'serif'},{label:'Sans',path:'sans'},{label:'Mono',path:'mono'}];
function Tx(txt,sz,extra){return r.createElement(l.Text,{style:Object.assign({fontSize:sz,fontWeight:'700',color:ZK},extra||{})},txt)}
function ZIcon2(e){var k=e.kind,B={width:44,height:44,alignItems:'center',justifyContent:'center'};
if(k==='size')return ZV(B,Tx('Aa',26));
if(k==='weight')return ZV(B,Tx('B',32,{fontWeight:'900'}));
if(k==='font')return ZV(B,Tx('\u0639',32));
if(k==='dir')return ZV(B,Tx('\u21C4',30));
if(k==='space'){var bar=function(t){return ZV(ZAbs({left:12,top:t,width:20,height:4,backgroundColor:ZK}))};
return ZV(B,ZV({width:44,height:44},bar(6),bar(20),bar(34),ZV(ZAbs({left:2,top:6,width:4,height:32,backgroundColor:ZK}))))}
if(k==='align'){var ln=function(t,w,rt){return ZV(ZAbs({right:6,top:t,width:w,height:4,backgroundColor:ZK}))};
return ZV(B,ZV({width:44,height:44},ln(7,32),ln(16,22),ln(25,32),ln(34,16)))}
return ZIcon(e)}
function ZRow2(e){return r.createElement(l.View,{style:{flexDirection:'row',alignItems:'center',alignSelf:'stretch',height:58,borderTopWidth:1.5,borderColor:ZK,paddingHorizontal:14}},
ZToggle({on:e.on,onPress:e.onToggle}),r.createElement(l.View,{style:{marginHorizontal:14,opacity:e.on?1:.35}},r.createElement(ZIcon2,{kind:e.kind})),e.children)}
function Seg(lbl,on,fn,dim){return r.createElement(l.Pressable,{key:lbl,onPress:fn,style:{paddingHorizontal:12,height:38,justifyContent:'center',backgroundColor:on?ZK:ZW}},r.createElement(l.Text,{style:{fontSize:18,color:on?ZW:ZK}},lbl))}
function SegGroup(items,cur,set,enabled){return r.createElement(l.View,{pointerEvents:enabled?'auto':'none',style:{flexDirection:'row',borderWidth:2.5,borderColor:ZK,borderRadius:9,overflow:'hidden',opacity:enabled?1:.3}},items.map(function(it){return Seg(it[1],cur===it[0],function(){set(it[0])})}))}
function Btn(lbl,fn,dis,flex){return r.createElement(l.Pressable,{onPress:fn,disabled:dis,style:[S.button,flex?S.buttonMain:null,dis?S.disabled:null]},r.createElement(l.Text,{style:S.buttonText},lbl))}
function mkSettings(c,fonts){var f=fonts[Math.min(c.fontIdx,fonts.length-1)]||fonts[0];
return{size:c.en.size?LV.size[c.size-1]:12,spacing:c.en.space?LV.space[c.space-1]:1.4,side:c.en.side?LV.side[c.side-1]:7,vert:c.en.vert?LV.vert[c.vert-1]:6,weight:c.en.weight?c.weight:1,font:c.en.font?f.path:'default',align:c.en.align?c.align:'book',dir:c.en.dir?c.dir:'auto',chapterBreak:true}}
function saveable(c){return{size:c.size,space:c.space,side:c.side,vert:c.vert,weight:c.weight,fontIdx:c.fontIdx,align:c.align,dir:c.dir,en:c.en}}
function BookScreen(props){
var path=props.path;
var A=r.useState(DEF),cfg=A[0],setCfg=A[1];
var B=r.useState(null),dir=B[0],setDir=B[1];
var C=r.useState(BASE_FONTS),fonts=C[0],setFonts=C[1];var FO=r.useState(false),fopen=FO[0],setFopen=FO[1];var FM=r.useState('pdf'),fmt=FM[0],setFmt=FM[1];
var D=r.useState(false),ready=D[0],setReady=D[1];
var E=r.useState({n:0,i:0}),pgs=E[0],setPgs=E[1];
var F=r.useState(null),img=F[0],setImg=F[1];
var G=r.useState('Reading the book\u2026'),msg=G[0],setMsg=G[1];
var H=r.useState(false),busy=H[0],setBusy=H[1];
var I=r.useState(''),prog=I[0],setProg=I[1];
var J=r.useState(false),full=J[0],setFull=J[1];
var K=r.useState({w:0,h:0}),box=K[0],setBox=K[1];
var L2=r.useState(false),working=L2[0],setWorking=L2[1];
var offRef=r.useRef(-1),fracRef=r.useRef(0),tokRef=r.useRef(0),imgRef=r.useRef(null),loadedRef=r.useRef(false),pgRef=r.useRef(0),fontsRef=r.useRef(BASE_FONTS);
var okNative=!!BK;
function rmFile(p){if(!p)return;try{Promise.resolve(SDK.FileUtils.deleteFile(p)).catch(function(){})}catch(x){}}
function show(idx,tok){
if(!dir)return Promise.resolve();
var out=dir+'/book_'+Date.now()+'.png';
return BK.renderPage(idx,out,2).then(function(res){
if(tok!==tokRef.current){rmFile(out);return}
offRef.current=res.offset;fracRef.current=res.fraction;pgRef.current=res.page;
var old=imgRef.current;imgRef.current=out;setImg(out);setPgs({n:res.pages,i:res.page});setMsg(null);rmFile(old)
})}
function relayout(c,fl,anchorOff,anchorFrac){
var tok=++tokRef.current;setBusy(true);
return BK.layout(JSON.stringify(mkSettings(c,fl)),anchorOff,anchorFrac).then(function(res){
if(tok!==tokRef.current)return;
return show(res.page,tok)}).then(function(){if(tok===tokRef.current)setBusy(false)}).catch(function(e){if(tok===tokRef.current){setBusy(false);setMsg('Failed: '+(e&&e.message?e.message:e))}})}
r.useEffect(function(){var live=true;
if(!okNative){setMsg('This build has no native book module.');return}
SDK.PluginManager.getPluginDirPath().then(function(p){if(!live)return;
if(!p){setMsg('The plugin folder is not available.');return}
setDir(p)}).catch(function(e){setMsg('Failed: '+e)});
return function(){live=false;BK.cancel&&BK.cancel()}},[]);
r.useEffect(function(){if(!dir||loadedRef.current)return;loadedRef.current=true;var live=true;
var cur=0,tot=0;
SDK.PluginCommAPI.getCurrentPageNum().then(function(x){cur=x&&x.success&&typeof x.result==='number'?x.result:0;return SDK.PluginDocAPI.getCurrentTotalPages()}).then(function(x){tot=x&&x.success&&typeof x.result==='number'?x.result:0}).catch(function(){})
.then(function(){return BK.readText(dir+'/book_settings.json')}).then(function(t){var c=DEF;try{if(t){var o=JSON.parse(t);c=Object.assign({},DEF,o,{en:Object.assign({},DEF.en,o.en||{})})}}catch(e){}
return BK.listFonts().then(function(list){var fl=BASE_FONTS.concat(list.map(function(x){return{label:x.name,path:x.path}}));
fontsRef.current=fl;if(live){setFonts(fl);setCfg(c)}return[c,fl]}).catch(function(){return[c,BASE_FONTS]})})
.then(function(cf){if(!live)return;setMsg('Reading the book\u2026');return BK.loadBook(path).then(function(){setReady(true);var fr=tot>0?Math.max(0,Math.min(1,cur/tot)):0;fracRef.current=fr;return relayout(cf[0],cf[1],-1,fr)})})
.catch(function(e){if(live)setMsg('Failed: '+(e&&e.message?e.message:e))});
return function(){live=false}},[dir]);
r.useEffect(function(){var sub=l.DeviceEventEmitter.addListener('BookPdfProgress',function(e){if(e.stage==='export')setProg(e.done+' / '+e.total);else if(e.stage==='read')setMsg('Reading the book\u2026 '+e.done+' / '+e.total);else if(e.stage==='save')setProg('saving\u2026');else if(e.stage==='layout')setMsg('Laying out... '+e.done+' / '+e.total)});return function(){sub.remove()}},[]);
var key=JSON.stringify(mkSettings(cfg,fonts));
var first=r.useRef(true);
r.useEffect(function(){if(!ready){return}
if(first.current){first.current=false;return}
var t=setTimeout(function(){relayout(cfg,fonts,offRef.current,fracRef.current);
if(dir)BK.writeText(dir+'/book_settings.json',JSON.stringify(saveable(cfg)))},350);
return function(){clearTimeout(t)}},[key,ready]);
function go(d){if(!ready||busy||working)return;var n=Math.max(0,Math.min(pgs.n-1,pgs.i+d));if(n===pgs.i)return;var tok=++tokRef.current;show(n,tok).catch(function(e){setMsg('Failed: '+(e&&e.message?e.message:e))})}
function jump(i){if(!ready||busy||working)return;var tok=++tokRef.current;show(Math.max(0,Math.min(pgs.n-1,i)),tok).catch(function(){})}
function upd(k,v){setCfg(function(o){var q=Object.assign({},o);q[k]=v;return q})}
function tog(k){return function(){setCfg(function(o){var en=Object.assign({},o.en);en[k]=!en[k];return Object.assign({},o,{en:en})})}}
function lvl(k,lk,kind,disp){var on=cfg.en[k];var v=cfg[k];var shown=disp(v);
return r.createElement(ZRow2,{key:k,kind:kind,on:on,onToggle:tog(k)},r.createElement(ZSlider,{value:v,enabled:on,onPick:function(n){upd(k,n)}}),r.createElement(l.Text,{style:{width:56,textAlign:'right',fontSize:22,fontWeight:'700',color:ZK}},String(shown)))}
function exportPdf(){
if(!ready||busy||working)return;
var isO=fmt==='orig';var target=path.replace(/\.epub$/i,'')+(isO?' (original style).pdf':'.pdf');
SDK.NativeUIUtils.showRattaDialog((isO?'Create a PDF in the book\'s own style (font size, alignment, spacing read from the EPUB)?\nThe settings above are not used. The EPUB is not changed.':'Create a PDF from this book with these settings?\nThe EPUB is not changed. The PDF is saved next to it.'),'Cancel','Create',false).then(function(ok){
if(!ok)return;
setWorking(true);setProg('starting\u2026');setMsg(null);
var cur=pgRef.current;
var sleepOn=function(v){try{var f=SDK.PluginCommAPI.setSystemDormancyState;if(typeof f==='function')return Promise.resolve(f.call(SDK.PluginCommAPI,v)).catch(function(){})}catch(e){}return Promise.resolve()};
return sleepOn(true).then(function(){return (isO?BK.layout(JSON.stringify({original:true}),offRef.current,fracRef.current):Promise.resolve()).then(function(){return BK.exportPdf(target)})}).then(function(res){
setProg('');setMsg('Done: '+res.pages+' pages, '+(res.bytes/1048576).toFixed(1)+' MB in '+Math.round(res.ms/1000)+' s'+(res.outline?', '+res.outline+' bookmarks':(res.outlineNote?' ('+res.outlineNote+')':''))+'.\n'+res.path);
return sleepOn(false).then(function(){return SDK.NativeUIUtils.showRattaDialog('The PDF is ready. Open it now?','Later','Open',false)}).then(function(o2){if(o2)return SDK.PluginFileAPI.openFile(res.path,cur)})
}).catch(function(e){sleepOn(false);setProg('');setMsg(e&&e.code==='E_CANCELLED'?'Cancelled. Nothing was saved.':'Failed: '+(e&&e.message?e.message:e))}).then(function(){setWorking(false);if(isO)relayout(cfg,fonts,offRef.current,fracRef.current)})
})}
function cancelExport(){setMsg('Cancelling\u2026');BK.cancel()}
var win=l.Dimensions.get('window');
var asp=0.75;
var rt=Math.max(120,Math.min(box.h-10,(box.w-10)/asp)),lt=rt*asp;
var uri=img?'file://'+img:null;
if(full&&uri){return r.createElement(l.Pressable,{style:S.full,onPress:function(){setFull(false)}},r.createElement(l.Image,{source:{uri:uri},resizeMode:'stretch',style:{width:Math.min(win.width,win.height*asp),height:Math.min(win.width/asp,win.height)}}))}
var fcur=fonts[Math.min(cfg.fontIdx,fonts.length-1)]||fonts[0];
if(fopen){return r.createElement(l.View,{style:S.container},
r.createElement(l.View,{style:S.header},r.createElement(l.Text,{style:S.title},'Choose font'),r.createElement(l.View,{style:{flex:1}}),r.createElement(l.Pressable,{onPress:function(){setFopen(false)},hitSlop:10,style:{padding:8}},Tx('\u2715',28))),
r.createElement(l.ScrollView,{style:{flex:1}},fonts.map(function(f,i){var on=i===cfg.fontIdx;return r.createElement(l.Pressable,{key:f.path+i,onPress:function(){upd('fontIdx',i);upd('en',Object.assign({},cfg.en,{font:true}));setFopen(false)},style:{paddingVertical:16,paddingHorizontal:20,borderBottomWidth:1,borderColor:'#999999',backgroundColor:on?ZK:ZW}},r.createElement(l.Text,{numberOfLines:1,style:{fontSize:22,fontWeight:'700',color:on?ZW:ZK}},f.label))})))}
var nav=function(sym,fn){return r.createElement(l.Pressable,{onPress:fn,hitSlop:6,style:{width:64,height:42,borderWidth:2.5,borderColor:ZK,borderRadius:9,alignItems:'center',justifyContent:'center',marginHorizontal:5}},Tx(sym,24))};
var locked=!ready||busy||working;
return r.createElement(l.View,{style:S.container},
r.createElement(l.View,{style:S.header},r.createElement(l.Text,{style:S.title},'PDF Tool'),r.createElement(l.Text,{style:S.page},ready?('Page '+(pgs.i+1)+' / '+pgs.n+(busy?'  \u00b7 updating\u2026':'')):''),r.createElement(l.Pressable,{onPress:function(){return SDK.PluginManager.closePluginView()},style:S.close},r.createElement(l.Text,{style:S.title},'\u2715'))),
r.createElement(l.View,{style:{flex:1,alignSelf:'stretch',alignItems:'center',justifyContent:'center'},onLayout:function(ev){var q=ev.nativeEvent.layout;setBox(function(o){return Math.abs(o.w-q.width)<1&&Math.abs(o.h-q.height)<1?o:{w:q.width,h:q.height}})}},
r.createElement(l.Pressable,{style:[S.screen,{width:lt,height:rt}],onPress:function(){if(uri)setFull(true)}},uri?r.createElement(l.Image,{source:{uri:uri},resizeMode:'stretch',style:{width:lt,height:rt}}):r.createElement(l.Text,{style:S.hint},msg||''))),
r.createElement(l.View,{style:{flexDirection:'row',alignItems:'center',justifyContent:'center',alignSelf:'stretch',height:58,borderTopWidth:1.5,borderColor:ZK,opacity:locked?.4:1},pointerEvents:locked?'none':'auto'},
nav('\u00AB',function(){jump(0)}),nav('\u2039',function(){go(-1)}),r.createElement(l.Text,{style:{width:120,textAlign:'center',fontSize:22,fontWeight:'700',color:ZK}},ready?((pgs.i+1)+' / '+pgs.n):'\u2014'),nav('\u203A',function(){go(1)}),nav('\u00BB',function(){jump(pgs.n-1)})),
r.createElement(l.View,{pointerEvents:locked?'none':'auto',style:{alignSelf:'stretch'}},
lvl('size','size','size',function(v){return LV.size[v-1]}),
lvl('space','space','space',function(v){return LV.space[v-1].toFixed(1)}),
lvl('side','side','width',function(v){return LV.side[v-1]+'%'}),
lvl('vert','vert','height',function(v){return LV.vert[v-1]+'%'}),
r.createElement(ZRow2,{key:'weight',kind:'weight',on:cfg.en.weight,onToggle:tog('weight')},r.createElement(ZSlider,{value:cfg.weight,enabled:cfg.en.weight,onPick:function(n){upd('weight',n)}}),r.createElement(l.Text,{style:{width:56,textAlign:'right',fontSize:22,fontWeight:'700',color:ZK}},String(cfg.weight))),
r.createElement(ZRow2,{key:'font',kind:'font',on:cfg.en.font,onToggle:tog('font')},
r.createElement(l.View,{pointerEvents:cfg.en.font?'auto':'none',style:{flex:1,flexDirection:'row',alignItems:'center',opacity:cfg.en.font?1:.3}},
r.createElement(l.Pressable,{onPress:function(){setFopen(true)},style:{flex:1,height:42,borderWidth:2.5,borderColor:ZK,borderRadius:9,flexDirection:'row',alignItems:'center',justifyContent:'space-between',paddingHorizontal:12}},Tx('\u25BE',22),r.createElement(l.Text,{numberOfLines:1,style:{flex:1,textAlign:'right',fontSize:20,fontWeight:'700',color:ZK}},fcur.label)))),
r.createElement(ZRow2,{key:'align',kind:'align',on:cfg.en.align,onToggle:tog('align')},r.createElement(l.View,{style:{flex:1,alignItems:'flex-end'}},SegGroup([['right','Right'],['justify','Justify'],['left','Left'],['center','Center']],cfg.align,function(v){upd('align',v)},cfg.en.align))),
r.createElement(ZRow2,{key:'dir',kind:'dir',on:cfg.en.dir,onToggle:tog('dir')},r.createElement(l.View,{style:{flex:1,alignItems:'flex-end'}},SegGroup([['auto','Auto'],['rtl','RTL'],['ltr','LTR']],cfg.dir,function(v){upd('dir',v)},cfg.en.dir)))),
r.createElement(l.View,{style:{alignSelf:'stretch',flexDirection:'row',justifyContent:'center',paddingVertical:6,opacity:working?.4:1},pointerEvents:working?'none':'auto'},SegGroup([['pdf','PDF with my settings'],['orig','PDF in the book\'s style']],fmt,function(v){setFmt(v)},true)),
r.createElement(l.View,{style:S.buttons},
Btn(working?('Working '+prog):(fmt==='orig'?'Create PDF (book style)':'Create PDF'),exportPdf,!ready||busy||working,true),
working?Btn('Cancel',cancelExport,false,false):null),
msg&&uri?r.createElement(l.Text,{style:S.message},msg):null,
r.createElement(l.Text,{style:S.hint},'The page above is drawn by the same code that writes the PDF. A switch off = the standard value. Tap the page to see it full screen.'))}
var S=l.StyleSheet.create({container:{flex:1,backgroundColor:'#ffffff'},full:{flex:1,backgroundColor:'#ffffff',alignItems:'center',justifyContent:'center'},header:{flexDirection:'row',alignItems:'center',alignSelf:'stretch',height:56,paddingHorizontal:14,borderBottomWidth:1.5,borderColor:'#000000'},title:{fontSize:24,fontWeight:'700',color:'#000000'},page:{flex:1,fontSize:17,color:'#444444',textAlign:'center'},close:{paddingHorizontal:8},hint:{fontSize:14,color:'#444444',marginVertical:4,textAlign:'center',paddingHorizontal:10},message:{fontSize:16,color:'#000000',marginVertical:4,textAlign:'center'},screen:{borderWidth:2,borderColor:'#000000',alignItems:'center',justifyContent:'center',overflow:'hidden',marginVertical:4,backgroundColor:'#ffffff'},buttons:{flexDirection:'row',alignSelf:'stretch',marginTop:8,paddingHorizontal:10},button:{flex:1,marginHorizontal:4,borderWidth:2,borderColor:'#000000',borderRadius:8,paddingVertical:12,alignItems:'center'},buttonMain:{flex:2},buttonText:{fontSize:19,fontWeight:'700',color:'#000000'},disabled:{opacity:.4}});
function Router(){
var s=r.useState(null),st=s[0],setSt=s[1];
r.useEffect(function(){var live=true;
SDK.PluginCommAPI.getCurrentFilePath().then(function(x){var p=x&&x.success?x.result:null;if(live)setSt({epub:!!p&&/\.epub$/i.test(p),path:p})}).catch(function(){if(live)setSt({epub:false,path:null})});
return function(){live=false}},[]);
if(!st)return r.createElement(l.View,{style:{flex:1,backgroundColor:'#ffffff'}});
return st.epub?r.createElement(BookScreen,{path:st.path}):r.createElement(PdfScreen,null)}
e.default=Router;e.BookScreen=BookScreen;

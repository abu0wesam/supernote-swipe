var ZK='#000000',ZW='#ffffff';
function ZV(st){var ch=Array.prototype.slice.call(arguments,1);return r.createElement.apply(null,[l.View,{style:st,pointerEvents:'none'}].concat(ch))}
function ZAbs(st){return Object.assign({position:'absolute'},st)}
function ZIcon(e){var k=e.kind,B={width:44,height:44,alignItems:'center',justifyContent:'center'};
if(k==='eye')return ZV(B,ZV({width:42,height:24,borderRadius:12,borderWidth:4,borderColor:ZK,alignItems:'center',justifyContent:'center'},ZV({width:14,height:14,borderRadius:7,backgroundColor:ZK})));
if(k==='width'||k==='height'){var inner=ZV({width:44,height:44,transform:[{rotate:k==='height'?'90deg':'0deg'}]},
ZV(ZAbs({left:3,top:7,width:4,height:30,backgroundColor:ZK})),
ZV(ZAbs({right:3,top:7,width:4,height:30,backgroundColor:ZK})),
ZV(ZAbs({left:10,top:20,width:24,height:4,backgroundColor:ZK})),
ZV(ZAbs({left:11,top:16,width:12,height:12,borderLeftWidth:4,borderBottomWidth:4,borderColor:ZK,transform:[{rotate:'45deg'}]})),
ZV(ZAbs({right:11,top:16,width:12,height:12,borderRightWidth:4,borderTopWidth:4,borderColor:ZK,transform:[{rotate:'45deg'}]})));return ZV(B,inner)}
if(k==='dark')return ZV(B,ZV({width:36,height:36,borderRadius:18,borderWidth:4,borderColor:ZK,overflow:'hidden',backgroundColor:ZW},ZV(ZAbs({right:0,top:0,bottom:0,width:14,backgroundColor:ZK}))));
if(k==='sharp'){var cs=function(pos,bw){return ZV(Object.assign({position:'absolute',width:11,height:11,borderColor:ZK},pos,bw))};
return ZV(B,ZV({width:40,height:40},cs({left:0,top:0},{borderLeftWidth:4,borderTopWidth:4}),cs({right:0,top:0},{borderRightWidth:4,borderTopWidth:4}),cs({left:0,bottom:0},{borderLeftWidth:4,borderBottomWidth:4}),cs({right:0,bottom:0},{borderRightWidth:4,borderBottomWidth:4}),
ZV(ZAbs({left:9,top:10,width:0,height:0,borderLeftWidth:11,borderRightWidth:11,borderBottomWidth:20,borderLeftColor:'transparent',borderRightColor:'transparent',borderBottomColor:ZK})),
ZV(ZAbs({left:14,top:19,width:0,height:0,borderLeftWidth:6,borderRightWidth:6,borderBottomWidth:10,borderLeftColor:'transparent',borderRightColor:'transparent',borderBottomColor:ZW}))))}
if(k==='clean')return ZV(B,ZV({width:44,height:44},
ZV(ZAbs({left:26,top:3,width:4,height:24,borderRadius:2,backgroundColor:ZK,transform:[{rotate:'40deg'}]})),
ZV(ZAbs({left:10,top:22,width:22,height:15,borderRadius:4,backgroundColor:ZK,transform:[{rotate:'40deg'}]})),
ZV(ZAbs({left:14,top:26,width:2,height:9,backgroundColor:ZW,transform:[{rotate:'40deg'}]})),
ZV(ZAbs({left:19,top:27,width:2,height:9,backgroundColor:ZW,transform:[{rotate:'40deg'}]})),
ZV(ZAbs({left:24,top:28,width:2,height:9,backgroundColor:ZW,transform:[{rotate:'40deg'}]})),
ZV(ZAbs({left:6,top:3,width:4,height:14,borderRadius:2,backgroundColor:ZK})),
ZV(ZAbs({left:1,top:8,width:14,height:4,borderRadius:2,backgroundColor:ZK}))));
return ZV(B,ZV({width:44,height:44,alignItems:'center',justifyContent:'center'},ZV({width:24,height:32,borderWidth:4,borderColor:ZK,borderRadius:3,alignItems:'center',justifyContent:'center',transform:[{rotate:'-18deg'}]},ZV({width:10,height:16,borderWidth:3,borderColor:ZK,borderRadius:2}))))}
function ZToggle(e){return r.createElement(l.Pressable,{onPress:e.onPress,hitSlop:8,style:{width:68,height:38,borderRadius:19,borderWidth:3,borderColor:ZK,backgroundColor:e.on?ZK:ZW,justifyContent:'center',paddingHorizontal:3}},ZV({width:26,height:26,borderRadius:13,borderWidth:3,borderColor:ZK,backgroundColor:e.on?ZW:ZK,alignSelf:e.on?'flex-end':'flex-start'}))}
function ZDash(){return ZV({width:7,height:3,backgroundColor:ZK})}
function ZSlider(e){var st=r.useState(0),w=st[0],sw=st[1],v=e.value,on=e.enabled;
function pick(ev){if(!w)return;var x=ev.nativeEvent.locationX,n=Math.ceil(x/w*10);n=Math.max(1,Math.min(10,n));if(n!==v)e.onPick(n)}
function step(d){var n=Math.max(1,Math.min(10,v+d));if(n!==v)e.onPick(n)}
var segs=[];for(var q=1;q<=10;q++)segs.push(r.createElement(l.View,{key:q,style:q<=v?{flex:1,height:12,marginHorizontal:4,borderRadius:2,backgroundColor:ZK}:{flex:1,height:12,marginHorizontal:4,borderRadius:3,borderWidth:2.5,borderColor:ZK,backgroundColor:ZW}}));
return r.createElement(l.View,{style:{flex:1,flexDirection:'row',alignItems:'center',height:56,opacity:on?1:.3},pointerEvents:on?'auto':'none'},
r.createElement(l.Pressable,{onPress:function(){return step(-1)},hitSlop:6,style:{flexDirection:'row',alignItems:'center',height:56}},ZDash(),ZV({width:15,height:15,borderRadius:8,borderWidth:3,borderColor:ZK}),ZDash()),
r.createElement(l.View,{style:{flex:1,height:56,justifyContent:'center'},onLayout:function(ev){sw(ev.nativeEvent.layout.width)},onStartShouldSetResponder:function(){return!0},onMoveShouldSetResponder:function(){return!0},onResponderGrant:pick,onResponderMove:pick,onResponderTerminationRequest:function(){return!1}},ZV({flexDirection:'row',alignItems:'center'},segs)),
r.createElement(l.Pressable,{onPress:function(){return step(1)},hitSlop:6,style:{flexDirection:'row',alignItems:'center',height:56}},ZDash(),ZV({width:19,height:19,borderRadius:10,borderWidth:3,borderColor:ZK,alignItems:'center',justifyContent:'center'},ZV({width:8,height:8,borderRadius:4,backgroundColor:ZK})),ZDash()))}
function ZRow(e){return r.createElement(l.View,{style:{flexDirection:'row',alignItems:'center',alignSelf:'stretch',height:58,borderTopWidth:1.5,borderColor:ZK,paddingHorizontal:14}},
ZToggle({on:e.on,onPress:e.onToggle}),r.createElement(l.View,{style:{marginHorizontal:14,opacity:e.on?1:.35}},r.createElement(ZIcon,{kind:e.kind})),e.children)}

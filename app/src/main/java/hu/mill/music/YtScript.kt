package hu.mill.music

/**
 * A YouTube mobil oldalba injektált szkript:
 *  - "látható" állapotot hazudik (lezárt képernyőn se álljon le),
 *  - figyeli a <video> elemet (lejátszás, szünet, vége, idő, cím),
 *  - fogadja a vezérlést (play / pause / seek).
 * Reklám közben a "vége" eseményt nem küldi tovább.
 */
object YtScript {
    val JS = """
(function(){
if(window.__mmInit)return;window.__mmInit=1;
try{
Object.defineProperty(document,'hidden',{get:function(){return false}});
Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'}});
Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'}});
}catch(e){}
document.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation()},true);
document.addEventListener('webkitvisibilitychange',function(e){e.stopImmediatePropagation()},true);
var v=null,lastT=0,lastTitle='',wantPlay=true;
function vid(){try{return new URLSearchParams(location.search).get('v')||''}catch(e){return ''}}
function post(o){o.vid=vid();try{MMYT.post(JSON.stringify(o))}catch(e){}}
function isAd(){return !!document.querySelector('.ad-showing,.ad-interrupting')}
function hook(){
 var n=document.querySelector('video');
 if(n&&n!==v){
  v=n;
  v.addEventListener('play',function(){post({type:'state',playing:true})});
  v.addEventListener('pause',function(){post({type:'state',playing:false})});
  v.addEventListener('ended',function(){if(!isAd())post({type:'ended'})});
  v.addEventListener('timeupdate',function(){
   var now=Date.now();
   if(now-lastT>1000&&!isAd()){lastT=now;post({type:'time',t:v.currentTime||0,d:v.duration||0})}
  });
  if(wantPlay&&v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){})}
 }
 var t=(document.title||'').replace(/ - YouTube${'$'}/,'');
 if(t&&t!=='YouTube'&&t!==lastTitle){lastTitle=t;post({type:'title',title:t})}
}
setInterval(hook,800);hook();
window.__mmCmd=function(c,a){
 if(!v)return;
 if(c==='play'){wantPlay=true;var p=v.play();if(p&&p.catch)p.catch(function(){})}
 else if(c==='pause'){wantPlay=false;v.pause()}
 else if(c==='seek'){v.currentTime=a}
};
})();
""".trimIndent()
}

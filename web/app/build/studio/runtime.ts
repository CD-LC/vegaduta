// What the studio injects into an app when it runs it in the preview iframe.
// Nothing here is ever saved into the app's own HTML - the app stays a
// clean, portable single file; the harness exists only in the preview.
//
// The iframe is sandbox="allow-scripts allow-forms allow-modals" with NO
// allow-same-origin, so the app gets an opaque origin: it cannot read this
// console's cookies, tokens or storage. The cost is that window.localStorage
// throws there - so the harness installs a drop-in localStorage that keeps
// data in memory, seeded by the parent and mirrored back to it by
// postMessage. Apps written against plain localStorage just work.
//
// Harness features (all postMessage, all tagged `vdStudio`):
//   parent -> app  {vdStudio:"inspect", on}       tap-to-select mode
//   app -> parent  {vdStudio:"select", ...}       the tapped element
//   app -> parent  {vdStudio:"storage", data}     localStorage snapshot
//   parent -> app  {vdStudio:"env", dark, offline, delayMs}   preview toggles
//   app -> parent  {vdStudio:"error", message}    uncaught runtime error
//
// public/run-app.html carries a vanilla copy of the storage + error parts
// (it can't import TS); keep the two in step.

/** Preview-only toggles the host can flip live (never saved into the app). */
export interface PreviewEnv {
  dark: boolean;
  offline: boolean;
  delayMs: number;
}

export const DEFAULT_ENV: PreviewEnv = { dark: false, offline: false, delayMs: 0 };

export interface SelectedElement {
  path: number[];
  tag: string;
  text: string;
  html: string;
  color: string;
  background: string;
  fontSize: string;
}

const HARNESS = String.raw`(function(){
var P=window.parent;var seed=__SEED__;var L={m:{}},S={m:{}};
try{for(var k in seed){if(Object.prototype.hasOwnProperty.call(seed,k))L.m[k]=String(seed[k]);}}catch(e){}
var t=null;function sync(){clearTimeout(t);t=setTimeout(function(){try{P.postMessage({vdStudio:"storage",data:L.m},"*");}catch(e){}},150);}
function mk(h,cb){var s={getItem:function(k){k=String(k);return Object.prototype.hasOwnProperty.call(h.m,k)?h.m[k]:null;},
setItem:function(k,v){h.m[String(k)]=String(v);cb();},removeItem:function(k){delete h.m[String(k)];cb();},
clear:function(){h.m={};cb();},key:function(i){return Object.keys(h.m)[i]||null;}};
Object.defineProperty(s,"length",{get:function(){return Object.keys(h.m).length;}});return s;}
try{Object.defineProperty(window,"localStorage",{value:mk(L,sync),configurable:true});}catch(e){}
try{Object.defineProperty(window,"sessionStorage",{value:mk(S,function(){}),configurable:true});}catch(e){}
function report(m,line,stack){try{P.postMessage({vdStudio:"error",message:String(m).slice(0,800),line:line||0,stack:String(stack||"").slice(0,1500)},"*");}catch(e){}}
window.addEventListener("error",function(e){report(e.message||"Error",e.lineno,e.error&&e.error.stack);});
window.addEventListener("unhandledrejection",function(e){var r=e.reason;report("Unhandled promise rejection: "+(r&&r.message||r),0,r&&r.stack);});
var inspect=false,box=null;
function ensureBox(){if(box)return box;box=document.createElement("div");box.setAttribute("data-vd-harness","1");
box.style.cssText="position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #f97316;background:rgba(249,115,22,.12);border-radius:4px;transition:all .06s;display:none";
document.documentElement.appendChild(box);return box;}
function pathOf(el){var p=[];while(el&&el!==document.body&&el.parentElement){var i=Array.prototype.indexOf.call(el.parentElement.children,el);p.unshift(i);el=el.parentElement;}return el===document.body?p:null;}
function over(e){if(!inspect)return;var r=e.target.getBoundingClientRect();var b=ensureBox();b.style.display="block";b.style.left=r.left+"px";b.style.top=r.top+"px";b.style.width=r.width+"px";b.style.height=r.height+"px";}
function pick(e){if(!inspect)return;e.preventDefault();e.stopPropagation();var el=e.target;var cs=getComputedStyle(el);
var clone=el.cloneNode(true);var html=clone.outerHTML||"";
P.postMessage({vdStudio:"select",path:pathOf(el)||[],tag:el.tagName.toLowerCase(),text:(el.textContent||"").replace(/\s+/g," ").trim().slice(0,4000),
html:html.length>1800?html.slice(0,1800)+"...":html,color:cs.color,background:cs.backgroundColor,fontSize:cs.fontSize},"*");}
document.addEventListener("mouseover",over,true);document.addEventListener("click",pick,true);
var envDark=false,off=false,delay=0,mqls=[];
var om=window.matchMedia;
if(om){window.matchMedia=function(q){var m=om.call(window,q);if(!/prefers-color-scheme/.test(String(q)))return m;
var dk=/dark/.test(String(q)),ls=[];var o={media:String(q),onchange:null,
get matches(){return envDark?dk:m.matches;},
addEventListener:function(ty,f){if(ty==="change")ls.push(f);},removeEventListener:function(ty,f){ls=ls.filter(function(x){return x!==f;});},
addListener:function(f){ls.push(f);},removeListener:function(f){ls=ls.filter(function(x){return x!==f;});},dispatchEvent:function(){return false;}};
mqls.push({o:o,ls:function(){return ls;}});return o;};}
function walk(rs,on){for(var j=0;j<rs.length;j++){var r=rs[j];
if(r.media&&r.cssRules){var t0=r.__vdOrig||r.media.mediaText;
if(/prefers-color-scheme/.test(t0)){if(!r.__vdOrig)r.__vdOrig=t0;
r.media.mediaText=on?t0.replace(/\(\s*prefers-color-scheme\s*:\s*dark\s*\)/g,"(min-width:0px)").replace(/\(\s*prefers-color-scheme\s*:\s*light\s*\)/g,"(min-width:1px) and (max-width:0px)"):r.__vdOrig;}
walk(r.cssRules,on);}}}
function applyDark(on){envDark=on;try{document.documentElement.style.colorScheme=on?"dark":"";}catch(e){}
for(var i=0;i<document.styleSheets.length;i++){var rs;try{rs=document.styleSheets[i].cssRules;}catch(e){continue;}try{walk(rs,on);}catch(e){}}
mqls.forEach(function(q){var ev={matches:q.o.matches,media:q.o.media};try{if(q.o.onchange)q.o.onchange(ev);q.ls().slice().forEach(function(f){f(ev);});}catch(e){}});}
var of=window.fetch;
if(of){window.fetch=function(){var a=arguments;if(off)return Promise.reject(new TypeError("Failed to fetch (offline simulation)"));
if(delay>0)return new Promise(function(res){setTimeout(res,delay);}).then(function(){return of.apply(window,a);});return of.apply(window,a);};}
try{Object.defineProperty(navigator,"onLine",{get:function(){return !off;},configurable:true});}catch(e){}
window.addEventListener("message",function(e){if(e.source!==P)return;var d=e.data;if(!d)return;
if(d.vdStudio==="inspect"){inspect=!!d.on;if(!inspect&&box)box.style.display="none";document.documentElement.style.cursor=inspect?"crosshair":"";return;}
if(d.vdStudio==="env"){var nd=!!d.dark,no=!!d.offline;delay=Math.max(0,Math.min(10000,Number(d.delayMs)||0));
if(nd!==envDark)applyDark(nd);
if(no!==off){off=no;try{window.dispatchEvent(new Event(off?"offline":"online"));}catch(e2){}}}});
})();`;

function harnessScript(storageSeed: Record<string, string>): string {
  const seed = JSON.stringify(storageSeed).replace(/</g, "\\u003c");
  return `<script>${HARNESS.replace("__SEED__", () => seed)}</script>`;
}

/** How many lines the injected harness adds before the app's own scripts -
 * error line numbers in the preview are off by exactly this much. */
export const HARNESS_LINES = harnessScript({}).split("\n").length - 1;

/** Build the srcdoc for the preview: the app with the harness injected as
 * the very first script, so localStorage is patched before app code runs. */
export function withHarness(html: string, storageSeed: Record<string, string> = {}): string {
  const script = harnessScript(storageSeed);
  const headOpen = html.match(/<head[^>]*>/i);
  if (headOpen && headOpen.index !== undefined) {
    const at = headOpen.index + headOpen[0].length;
    return html.slice(0, at) + script + html.slice(at);
  }
  return script + html;
}

/** Locate the element the person tapped inside the app's SOURCE (parsed with
 * DOMParser), by the child-index path the harness reported. Returns null
 * when the element was created at runtime by the app's JS - those can only
 * be changed through the AI, not by a direct edit. */
export function findInSource(doc: Document, sel: SelectedElement): Element | null {
  let el: Element | null = doc.body;
  for (const i of sel.path) {
    if (!el) return null;
    el = el.children[i] || null;
  }
  if (!el || el === doc.body) return null;
  return el.tagName.toLowerCase() === sel.tag ? el : null;
}

export function serializeDoc(doc: Document): string {
  return "<!doctype html>\n" + doc.documentElement.outerHTML + "\n";
}

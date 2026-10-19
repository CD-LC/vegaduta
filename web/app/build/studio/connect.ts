// The "Connect" runtime spliced into a generated app when its owner attaches
// an agent or workflow - and, once the app is PUBLISHED, the three
// out-of-the-box powers that need a server-side anchor: alias calls
// (vd.call), the hosted-AI block (vd.ai.chat) and end-user sign-in (vd.auth).
//
// How the network happens: the app always runs sandboxed with an opaque
// origin and connect-src 'none' - it can never fetch() itself. It posts
// {vdStudio:"vdCall"} to its host page, which calls the API from its own
// allowlisted origin and posts {vdStudio:"vdReply"} back. Both hosts
// implement the same protocol (StudioSurface.tsx and public/run-app.html -
// keep them in step). A downloaded file with no host gets a graceful
// rejection after a timeout instead of a silent hang.
//
// Kinds on the wire:
//   {kind:"agent"|"workflow", id, msg}          legacy share-id calls
//   {kind:"call", slug, alias, input, data}     published-app connection call
//   {kind:"ai", slug, prompt}                   hosted-AI turn
//   {kind:"auth", slug, action}                 sign-in gate: user|signIn|signUp|signOut|require
//
// Replies: {q, ok, text} - auth replies also carry {user:{name,email}|null}.
//
// Auth is host-rendered on purpose: the runner's banner promises "never
// enter passwords" inside a user-made app, and the password-field guard
// stays on. The sign-in overlay lives in the HOST page (outside the
// sandbox); the app only ever sees the user's name/email via vd.auth.user().
// The session token never enters the iframe at all - the host attaches it
// as a Bearer header on call/ai requests.
//
// The block is delimited by marker comments and regenerated wholesale on
// every attach/detach/config change, so removing all connections cleanly
// removes it.

import type { StudioConnection, StudioAiConfig, StudioAuthConfig, StudioPublish } from "./store";

export const CONNECT_START = "<!-- vd:connect -->";
export const CONNECT_END = "<!-- /vd:connect -->";

export interface StudioAppBlockConfig {
  publish?: StudioPublish | null;
  auth?: StudioAuthConfig | null;
  ai?: StudioAiConfig | null;
}

// __APP__ and __CXN__ are replaced with JSON at write time.
const RUNTIME = String.raw`(function(){
var APP=__APP__;
var CXN=__CXN__;
var Q={},N=0;
function call(msg){return new Promise(function(res,rej){
if(!window.parent||window.parent===window){rej(new Error("Open this app through its VegaDūta link to use connected services."));return;}
var q=++N;var timer=setTimeout(function(){delete Q[q];rej(new Error("No answer from the app host - is the connection still shared?"));},90000);
Q[q]={res:function(d){clearTimeout(timer);res(d);},rej:function(e){clearTimeout(timer);rej(e instanceof Error?e:new Error(String(e||"failed")));}};
try{msg.q=q;window.parent.postMessage(msg,"*");}catch(e){clearTimeout(timer);delete Q[q];rej(e);}
});}
function textCall(msg){return call(msg).then(function(d){return String(d==null?"Done.":d);});}
if(!window.vd){window.vd={};}
if(!window.vd.agent){window.vd.agent=function(id,m){return textCall({vdStudio:"vdCall",kind:"agent",id:String(id),msg:String(m==null?"":m)});};}
if(!window.vd.workflow){window.vd.workflow=function(id,m){return textCall({vdStudio:"vdCall",kind:"workflow",id:String(id),msg:String(m==null?"":m)});};}
// Published-app powers. Need APP.slug (set at publish); when missing, fall
// back to the share-id form for connections so preview keeps working.
window.vd.call=function(alias,input,data){
var c=null;for(var i=0;i<CXN.length;i++){if(CXN[i].alias===alias){c=CXN[i];}}
if(APP.slug){return textCall({vdStudio:"vdCall",kind:"call",slug:APP.slug,alias:String(alias),input:String(input==null?"":input),data:data||{}});}
if(c){return (c.kind==="workflow"?window.vd.workflow:window.vd.agent)(c.shareId,String(input==null?"":input));}
return Promise.reject(new Error("Nothing is wired to \""+alias+"\" - check the Connect panel."));
};
window.vd.ai={chat:function(prompt){
if(!APP.slug||!APP.ai){return Promise.reject(new Error("Turn on the AI block and publish the app first (Connect tab → AI inside the app)."));}
return textCall({vdStudio:"vdCall",kind:"ai",slug:APP.slug,prompt:String(prompt==null?"":prompt)});
}};
window.vd.auth={
user:function(){
if(!APP.slug||!APP.auth){return Promise.resolve(null);}
return call({vdStudio:"vdCall",kind:"auth",slug:APP.slug,action:"user"}).then(function(d){return d&&d.user?d.user:null;}).catch(function(){return null;});
},
signIn:function(){
if(!APP.slug||!APP.auth){return Promise.reject(new Error("Turn on sign-in and publish the app first (Connect tab → Sign-in)."));}
return call({vdStudio:"vdCall",kind:"auth",slug:APP.slug,action:"signIn"}).then(function(d){return d&&d.user?d.user:null;});
},
signOut:function(){
if(!APP.slug){return Promise.resolve();}
return call({vdStudio:"vdCall",kind:"auth",slug:APP.slug,action:"signOut"}).then(function(){return null;}).catch(function(){return null;});
},
require:function(){return window.vd.auth.signIn();}
};
window.addEventListener("message",function(e){var d=e.data;if(!d||d.vdStudio!=="vdReply")return;var h=Q[d.q];if(h){delete Q[d.q];if(d.ok){h.res(d.user!==undefined?{text:d.text,user:d.user}:d.text);}else{h.rej(new Error(String(d.text||"The call failed.")));}}});
// The floating ✦ panel exists when there's anything usable inside it.
var hasPanel=CXN.length>0||!!(APP.slug&&APP.auth)||!!(APP.slug&&APP.ai);
if(!hasPanel){return;}
var css="#vd-cxn button{font:inherit;cursor:pointer}"
+"#vd-cxn .vdb{position:fixed;right:14px;bottom:14px;z-index:2147483000;width:50px;height:50px;border-radius:50%;border:0;background:var(--primary,#6366f1);color:#fff;font-size:19px;box-shadow:0 4px 14px rgba(0,0,0,.28);display:flex;align-items:center;justify-content:center}"
+"#vd-cxn .vdp{position:fixed;right:14px;bottom:72px;z-index:2147483000;width:292px;max-height:70vh;overflow:auto;background:var(--surface,#fff);color:var(--text,#17181c);border:1px solid rgba(0,0,0,.12);border-radius:14px;box-shadow:0 10px 34px rgba(0,0,0,.3);padding:12px;font:13px/1.45 system-ui,sans-serif;display:none}"
+"#vd-cxn .vdp.open{display:block}"
+"#vd-cxn .vdh{font-weight:700;margin:0 0 8px;font-size:14px}"
+"#vd-cxn .vdc{border:1px solid rgba(0,0,0,.1);border-radius:10px;padding:8px;margin-bottom:8px}"
+"#vd-cxn .vdn{font-weight:600;font-size:12px;margin-bottom:6px;display:flex;align-items:center;gap:5px}"
+"#vd-cxn .vdn .k{font-size:10px;font-weight:500;opacity:.55;text-transform:uppercase;letter-spacing:.04em}"
+"#vd-cxn input{width:100%;box-sizing:border-box;border:1px solid rgba(0,0,0,.16);border-radius:8px;padding:6px 8px;font:inherit;margin-bottom:6px;background:var(--bg,#fff);color:inherit}"
+"#vd-cxn .vds{width:100%;border:0;border-radius:8px;background:var(--primary,#6366f1);color:#fff;padding:7px 10px;font-weight:600}"
+"#vd-cxn .vds:disabled{opacity:.55}"
+"#vd-cxn .vdr{margin-top:6px;font-size:12px;white-space:pre-wrap;word-break:break-word;display:none}"
+"#vd-cxn .vdr.show{display:block;padding:6px;background:rgba(0,0,0,.05);border-radius:6px}"
+"#vd-cxn .vdr.err{color:#c00}"
+"#vd-cxn .vdme{display:flex;align-items:center;gap:8px;font-size:12px;padding:6px 8px;border-radius:8px;background:rgba(0,0,0,.04);margin-bottom:8px}";
var st=document.createElement("style");st.textContent=css;document.head.appendChild(st);
var host=document.createElement("div");host.id="vd-cxn";document.body.appendChild(host);
var btn=document.createElement("button");btn.className="vdb";btn.type="button";btn.title="Connected agents & workflows";btn.textContent="✦";
var panel=document.createElement("div");panel.className="vdp";
panel.innerHTML='<p class="vdh">Connected</p>';
btn.onclick=function(){panel.classList.toggle("open");};
// Signed-in end-user row - shown whenever the app has sign-in configured.
if(APP.slug&&APP.auth){
var me=document.createElement("div");me.className="vdme";panel.appendChild(me);
function paintMe(){window.vd.auth.user().then(function(u){
me.innerHTML="";var s=document.createElement("span");s.style.flex="1";s.style.overflow="hidden";s.style.textOverflow="ellipsis";
s.textContent=u?("Signed in as "+(u.name||u.email)):"Not signed in";me.appendChild(s);
var b=document.createElement("button");b.type="button";b.style.cssText="border:0;border-radius:8px;padding:4px 8px;background:var(--primary,#6366f1);color:#fff;font-size:11px;font-weight:600";
b.textContent=u?"Sign out":"Sign in";
b.onclick=function(){if(u){window.vd.auth.signOut().then(paintMe);}else{window.vd.auth.signIn().then(paintMe).catch(function(){});}};
me.appendChild(b);});}
paintMe();
}
CXN.forEach(function(c){
var row=document.createElement("div");row.className="vdc";
var nm=document.createElement("div");nm.className="vdn";nm.textContent=c.name;
var k=document.createElement("span");k.className="k";k.textContent=c.kind;nm.appendChild(k);
if(c.requiresAuth){var lock=document.createElement("span");lock.className="k";lock.textContent="sign-in";lock.title="End users must sign in first";nm.appendChild(lock);}
var inp=document.createElement("input");inp.placeholder=c.kind==="workflow"?"Input for this run":"Ask "+c.name;
var send=document.createElement("button");send.className="vds";send.type="button";send.textContent=c.kind==="workflow"?"Run":"Send";
var out=document.createElement("div");out.className="vdr";
send.onclick=function(){
var fn=APP.slug&&c.alias?function(){return window.vd.call(c.alias,inp.value);}:(c.kind==="workflow"?function(){return window.vd.workflow(c.shareId,inp.value);}:function(){return window.vd.agent(c.shareId,inp.value);});
send.disabled=true;send.textContent="…";out.className="vdr";out.textContent="";
fn().then(function(t){out.className="vdr show";out.textContent=typeof t==="string"?t:"Done.";}).catch(function(e){out.className="vdr show err";out.textContent=e.message||"Failed.";}).then(function(){send.disabled=false;send.textContent=c.kind==="workflow"?"Run":"Send";});
};
row.appendChild(nm);row.appendChild(inp);row.appendChild(send);row.appendChild(out);panel.appendChild(row);
});
// AI block row - a prompt box that runs on the hosted model.
if(APP.slug&&APP.ai){
var ai=document.createElement("div");ai.className="vdc";
var anm=document.createElement("div");anm.className="vdn";anm.textContent="AI assistant";
var ak=document.createElement("span");ak.className="k";ak.textContent="ai";anm.appendChild(ak);
var ain=document.createElement("input");ain.placeholder="Ask the AI anything";
var asend=document.createElement("button");asend.className="vds";asend.type="button";asend.textContent="Ask";
var aout=document.createElement("div");aout.className="vdr";
asend.onclick=function(){asend.disabled=true;asend.textContent="…";aout.className="vdr";aout.textContent="";
window.vd.ai.chat(ain.value).then(function(t){aout.className="vdr show";aout.textContent=t;}).catch(function(e){aout.className="vdr show err";aout.textContent=e.message||"Failed.";}).then(function(){asend.disabled=false;asend.textContent="Ask";});};
ai.appendChild(anm);ai.appendChild(ain);ai.appendChild(asend);ai.appendChild(aout);panel.appendChild(ai);
}
host.appendChild(panel);host.appendChild(btn);
})();`;

/** Remove the generated connections block; attach/detach rewrites it
 * wholesale, so this is also how "no connections" cleans up. */
export function stripConnections(html: string): string {
  const s = html.indexOf(CONNECT_START);
  const e = html.indexOf(CONNECT_END);
  if (s < 0 || e < s) return html;
  return html.slice(0, s) + html.slice(e + CONNECT_END.length);
}

/** Write (or remove, when empty) the connections block in the app HTML.
 * The block carries the vd.* callables plus a floating panel that talks to
 * each attached service out of the box, no AI round-trip needed. The app
 * block config (publish slug, auth mode, AI toggle) rides in the same
 * block so a shared HTML file keeps its wiring. */
export function writeConnections(
  html: string,
  connections: StudioConnection[],
  app: StudioAppBlockConfig = {}
): string {
  const clean = stripConnections(html);
  const slug = app.publish?.slug || null;
  const authMode = app.auth?.mode && app.auth.mode !== "off" ? app.auth.mode : null;
  const aiOn = !!(app.ai?.enabled && slug);
  const appJson = JSON.stringify({
    slug,
    auth: authMode,
    ai: aiOn,
  }).replace(/</g, "\\u003c");
  const json = JSON.stringify(
    connections.map((c) => ({
      kind: c.kind,
      shareId: c.shareId,
      name: c.name,
      alias: c.alias || null,
      requiresAuth: !!c.requiresAuth,
    }))
  ).replace(/</g, "\\u003c");
  // Write the block whenever there's anything for vd.* to do: attached
  // connections, or a published app (slug unlocks call/ai/auth even with no
  // connections - the app's own code may call them).
  if (connections.length === 0 && !slug) return clean;
  const block = `${CONNECT_START}\n<script>${RUNTIME.replace("__APP__", () => appJson).replace("__CXN__", () => json)}</script>\n${CONNECT_END}`;
  const i = clean.toLowerCase().lastIndexOf("</body>");
  return i >= 0 ? clean.slice(0, i) + block + clean.slice(i) : clean + block;
}

/** The JSON shape the app block carries - what the runtime's APP constant
 * decodes to. Kept minimal: the slug is the identity; auth/ai booleans gate
 * feature rows in the floating panel and vd.* callables. */
export function readAppBlock(html: string): { slug: string | null; auth: string | null; ai: boolean } | null {
  const s = html.indexOf(CONNECT_START);
  const e = html.indexOf(CONNECT_END);
  if (s < 0 || e < s) return null;
  const m = html.slice(s, e).match(/var APP=(\{[^;]*\});/);
  if (!m) return null;
  try {
    return JSON.parse(m[1]);
  } catch {
    return null;
  }
}

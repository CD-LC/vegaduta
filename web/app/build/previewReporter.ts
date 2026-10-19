// Runtime error reporting for the instant preview iframe.
//
// The generated app runs in an opaque-origin sandbox, so the host page can't
// read its console. This script is injected into the srcdoc and posts every
// uncaught error, failed module/asset load, unhandled rejection, console.error
// and a "blank page" heartbeat up to the host, which turns them into a
// one-click "Fix with AI" turn. That closes the build loop without needing a
// headless browser on the server.

export const PREVIEW_MESSAGE_TAG = "__vdPreview";

const REPORTER_BODY = `(function(){
var send=function(m){try{parent.postMessage({__vdPreview:1,message:String(m).slice(0,600)},"*")}catch(e){}};
window.addEventListener("error",function(e){
  if(e.target&&e.target!==window){send("Failed to load "+(e.target.src||e.target.href||e.target.tagName))}
  else{send((e.message||"Script error")+(e.lineno?" (line "+e.lineno+")":""))}
},true);
window.addEventListener("unhandledrejection",function(e){var r=e.reason;send("Unhandled promise rejection: "+((r&&r.message)||r))});
var ce=console.error;
console.error=function(){
  var args=arguments;
  try{
    var text=Array.prototype.slice.call(args).map(function(a){
      if(a&&a.message)return a.message;
      if(a&&typeof a==="object"){try{return JSON.stringify(a)}catch(x){return String(a)}}
      return String(a)}).join(" ");
    send("console.error: "+text);
  }catch(e){}
  return ce.apply(console,args);
};
var blank=function(){
  var r=document.getElementById("root");
  if(r&&!r.childNodes.length){send("Blank page: #root is still empty 4s after load - the app never rendered")}
};
if(document.readyState==="complete"){setTimeout(blank,4000)}
else{window.addEventListener("load",function(){setTimeout(blank,4000)})}
})();`;

/** `<script>` tag to inject as early as possible in the preview document. */
export const PREVIEW_REPORTER = `<script>${REPORTER_BODY}<\/script>`;

/** Insert the reporter at the top of <head> (or the document start). */
export function injectReporter(doc: string): string {
  const m = /<head[^>]*>/i.exec(doc);
  if (!m) return PREVIEW_REPORTER + doc;
  const at = m.index + m[0].length;
  return doc.slice(0, at) + PREVIEW_REPORTER + doc.slice(at);
}

/** React dev warnings and similar chatter are not app-breaking — a fix turn
 * over them would churn working code. */
export function isNoise(message: string): boolean {
  return /^console\.error: (Warning:|%c|Download the React DevTools)/.test(message) ||
    /act\(\.\.\.\)/.test(message) ||
    /ResizeObserver loop/.test(message);
}

/** Add `message` to the deduped, capped error list; returns the same array
 * when nothing changed so React state updates can bail out. */
export function addError(list: string[], message: string, cap = 8): string[] {
  if (isNoise(message) || list.includes(message) || list.length >= cap) return list;
  return [...list, message];
}

/** The instruction sent to the builder for a "Fix with AI" turn. */
export function formatFixRequest(errors: string[]): string {
  return (
    "The live preview reports runtime errors. Fix the cause of each one and " +
    "change nothing else about the app's design or behavior:\n" +
    errors.map((e) => `- ${e}`).join("\n")
  );
}

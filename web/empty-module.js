// Stub for client bundles. `onnxruntime-node` is the NATIVE Node ONNX backend
// and can never run in a browser; Turbopack needs a real file to alias to, so
// this is it. See the turbopack.resolveAlias comment in next.config.js.
module.exports = {};

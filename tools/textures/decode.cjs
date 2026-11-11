// Decodes KTX2 (Basis UASTC/ETC1S) files to raw RGBA using the Basis Universal transcoder.
const fs = require('fs'), path = require('path'), vm = require('vm');
const dir = path.join(__dirname, 'node_modules/three/examples/jsm/libs/basis');
const src = fs.readFileSync(path.join(dir, 'basis_transcoder.js'), 'utf8');
const sandbox = { module: { exports: {} }, exports: {}, console, WebAssembly, process, require, __dirname: dir, setTimeout, TextDecoder, performance: { now: () => Date.now() }, URL };
vm.createContext(sandbox); vm.runInContext(src + '\n;globalThis.__BASIS = typeof BASIS !== "undefined" ? BASIS : module.exports;', sandbox);
const BASIS = sandbox.__BASIS;
BASIS({ wasmBinary: fs.readFileSync(path.join(dir, 'basis_transcoder.wasm')) }).then(m => {
  m.initializeBasis();
  for (let i = 2; i + 1 < process.argv.length; i += 2) {
    const data = new Uint8Array(fs.readFileSync(process.argv[i]));
    const f = new m.KTX2File(data);
    if (!f.isValid()) { console.log('invalid', process.argv[i]); continue; }
    const w = f.getWidth(), h = f.getHeight();
    if (!f.startTranscoding()) { console.log('start failed', process.argv[i]); continue; }
    const RGBA32 = 13, size = f.getImageTranscodedSizeInBytes(0, 0, 0, RGBA32);
    const dst = new Uint8Array(size);
    const ok = f.transcodeImage(dst, 0, 0, 0, RGBA32, 0, -1, -1);
    fs.writeFileSync(process.argv[i + 1], Buffer.concat([Buffer.from(new Uint32Array([w, h]).buffer), Buffer.from(dst)]));
    console.log(ok ? 'ok' : 'FAILED', w, h, path.basename(process.argv[i]));
    f.close(); f.delete();
  }
});

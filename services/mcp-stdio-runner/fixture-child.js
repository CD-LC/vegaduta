'use strict';

// Test-only stand-in "MCP server" child for server.test.js. The runner now
// rejects `node -e <code>` (see the McpStdioCommandGuard mirror in
// server.js), so the suite spawns `node fixture-child.js <mode>` - the same
// "node <script.js>" shape a legitimate registration uses. Fixed modes only:
// this file must never become an eval gadget. Not copied into the image
// (Dockerfile COPYs framing.js + server.js only).

const mode = process.argv[2];

switch (mode) {
    case 'print-path':
        process.stdout.write(process.env.PATH || '');
        break;
    case 'print-path-home':
        process.stdout.write(JSON.stringify({ path: process.env.PATH, home: process.env.HOME }));
        break;
    case 'print-image-env':
        process.stdout.write(JSON.stringify({
            pw: process.env.PLAYWRIGHT_BROWSERS_PATH,
            dc: process.env.DOCKER_CONTAINER,
            pp: process.env.PUPPETEER_CACHE_DIR === undefined ? null : process.env.PUPPETEER_CACHE_DIR,
            secret: process.env.MCP_STDIO_SIDECAR_SHARED_SECRET === undefined ? null : 'leaked',
        }));
        break;
    case 'print-sandbox': {
        // SEC-012: the per-connection sandbox as the child sees it.
        const fs = require('fs');
        const home = process.env.HOME;
        let homeMode = null;
        let homeWritable = false;
        try {
            homeMode = fs.statSync(home).mode & 0o777;
            fs.writeFileSync(`${home}/marker.txt`, 'x');
            homeWritable = true;
        } catch (_e) {
            // Reported as not writable.
        }
        process.stdout.write(JSON.stringify({
            home,
            homeMode,
            homeWritable,
            cwd: process.cwd(),
            tmpdir: process.env.TMPDIR,
            npmCache: process.env.npm_config_cache,
            npmCacheUpper: process.env.NPM_CONFIG_CACHE === undefined ? null : process.env.NPM_CONFIG_CACHE,
            uvCache: process.env.UV_CACHE_DIR,
            xdgCache: process.env.XDG_CACHE_HOME,
            xdgConfig: process.env.XDG_CONFIG_HOME,
            xdgData: process.env.XDG_DATA_HOME,
            uvPython: process.env.UV_PYTHON_INSTALL_DIR === undefined ? null : process.env.UV_PYTHON_INSTALL_DIR,
        }));
        break;
    }
    case 'print-env-var':
        process.stdout.write(process.env[process.argv[3]] || '');
        break;
    case 'hello':
        process.stdout.write('hello-stdout\n');
        break;
    case 'stderr-lines':
        process.stderr.write('line-one\nline-two\n');
        break;
    case 'exit-7':
        process.exit(7);
        break;
    case 'idle':
        setInterval(() => {}, 1000);
        break;
    case 'print-node-options':
        process.stdout.write(process.env.NODE_OPTIONS || '');
        break;
    case 'tick':
        // Chatty (never idle): one byte on stdout every 50 ms.
        setInterval(() => process.stdout.write('.'), 50);
        break;
    case 'echo-stdin':
        process.stdin.on('data', (d) => process.stdout.write(d));
        break;
    default:
        process.stderr.write(`unknown mode: ${mode}\n`);
        process.exit(2);
}

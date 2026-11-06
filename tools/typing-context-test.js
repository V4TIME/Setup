#!/data/data/com.termux/files/usr/bin/node
/**
 * typing-context-test — verifies the v1.3 capture contract against the live panel:
 *   POST /kid-alert {query, pkg, app, seq, time}
 *   GET  /kid-typing  -> lastQuery/lastApp + recent[].app
 *   POST /kid-save    -> document contains the app context
 * Also checks that bracket characters survive (the v1.2 bug).
 */
const http = require('http');
const BASE = 'http://127.0.0.1:8080';
const IP = '192.168.1.42';

function req(method, path, body, headers) {
  return new Promise((resolve, reject) => {
    const r = http.request(BASE + path, {
      method,
      headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, headers || {})
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
  await req('POST', '/connect', 'ip=' + IP);

  // exactly the payload SafetyService now builds
  const q1 = 'query=' + encodeURIComponent('buy milk') +
             '&pkg=' + encodeURIComponent('com.whatsapp') +
             '&app=' + encodeURIComponent('WhatsApp') +
             '&seq=1&time=' + Date.now();
  const q2 = 'query=' + encodeURIComponent('[test]') +
             '&pkg=' + encodeURIComponent('com.android.chrome') +
             '&app=' + encodeURIComponent('Chrome') +
             '&seq=2&time=' + Date.now() + 1;

  console.log('POST typing #1 =>', (await req('POST', '/kid-alert?ip=' + IP, q1)).body);
  console.log('POST typing #2 =>', (await req('POST', '/kid-alert?ip=' + IP, q2)).body);

  const t = JSON.parse((await req('GET', '/kid-typing')).body);
  const dev = (t.devices || []).find(d => d.ip === IP) || {};
  console.log('\nlastQuery =', JSON.stringify(dev.lastQuery), '| lastApp =', dev.lastApp);
  const recent = dev.recent || [];
  console.log('recent entries:');
  for (const e of recent) console.log('   ', JSON.stringify(e));

  const okContext = dev.lastApp === 'Chrome' &&
    recent.some(e => e.text === 'buy milk' && e.app === 'WhatsApp') &&
    recent.some(e => e.text === '[test]' && e.app === 'Chrome');

  const saved = JSON.parse((await req('POST', '/kid-save')).body);
  const docs = JSON.parse((await req('GET', '/kid-docs')).body);
  const latest = (docs.files || [])[0];
  let doc = '';
  if (latest) {
    doc = (await req('GET', '/kid-docs/' + encodeURIComponent(latest))).body;
    console.log('\ndoc', latest, '=>');
    console.log(doc.split('\n').filter(l => l.startsWith('- ')).join('\n'));
  }
  const okDoc = doc.includes('[WhatsApp] buy milk') && doc.includes('[Chrome] [test]');

  console.log('\ncontext captured          :', okContext ? 'PASS' : 'FAIL');
  console.log('brackets preserved        :', doc.includes('[Chrome] [test]') ? 'PASS' : 'FAIL');
  console.log('document has app context  :', okDoc ? 'PASS' : 'FAIL');
  console.log('saved docs                :', (saved.saved || []).length);
  process.exit(okContext && okDoc ? 0 : 1);
})().catch(e => { console.error('FAIL', e); process.exit(1); });

/*
  Desktop stand-in for the phone's MiFiNative bridge, so the screens can be worked on in a browser.
  Every value here is made up. It never loads inside the app (the phone injects the real bridge first).
  ?s=mifi (default) | router | signin | locked | wifi | unknown | offline | low | dropped | empty
*/
(() => {
const mode = new URLSearchParams(location.search).get('s') || 'mifi';
const router = mode === 'router';
// ?update=1 pretends a newer app version is on GitHub.
const upd = new URLSearchParams(location.search).get('update')
  ? { appNewer: true, webNewer: false, app: { version: '1.2.0', size: 2.1e6 } } : { appNewer: false, webNewer: false };
const now = Date.now(), H = ms => Math.floor(ms / 3.6e6), hr = H(now);
const ME = 'AA:BB:CC:00:00:01';
const clients = [
  { mac: ME, name: 'This phone', ip: '192.168.0.100', wifi: true },
  { mac: 'AA:BB:CC:00:00:02', name: 'Laptop', ip: '192.168.0.101', wifi: true },
  { mac: 'AA:BB:CC:00:00:03', name: null, ip: '192.168.0.102', wifi: true },
];
const id = router ? 'router:ZLT-X21' : 'mifi:U10LS';
const devices = [
  { id: 'mifi:U10LS', name: 'Pocket MiFi', kind: 'mifi', model: 'U10LS', host: '192.168.0.1', lastSeen: now - (router ? 864e5 * 2 : 0) },
  { id: 'router:ZLT-X21', name: 'Home router', kind: 'router', model: 'ZLT-X21', host: '192.168.0.1', lastSeen: now - (router ? 0 : 3600e3 * 5) },
];
const low = mode === 'low', dropped = mode === 'dropped';
const status = {
  kind: router ? 'router' : 'mifi', model: router ? 'ZLT-X21' : 'U10LS', signedIn: true,
  battery: router ? null : low ? 12 : 64, charging: false, connected: !dropped,
  network: 'LTE', provider: 'Example Net', bars: 4, rsrp: router ? -88 : -84, snr: 12,
  rxRate: 410000, txRate: 52000, monthBytes: 18.4e9, sessionSec: 3 * 3600 + 1240, uptimeSec: 6 * 86400 + 4000,
  smsUnread: mode === 'empty' ? 0 : 2, tempC: router ? 56 : null, ssid: router ? 'Home-5G' : 'MiFi-1A2B', firmware: router ? 'V1.0.3' : 'BD_U10LSV1.0.0B05',
  clients, raw: {},
};
const problem = { wifi: 'wifi', unknown: 'unknown', offline: 'offline' }[mode] || null;
const signin = mode === 'signin' || mode === 'locked';
const state = () => ({
  id: problem === 'wifi' ? null : id, kind: status.kind, model: status.model,
  name: devices.find(d => d.id === id).name, selected: sel, problem,
  signedIn: !signin && !problem, needsSignIn: signin, lockedUntil: mode === 'locked' ? now + 240e3 : 0, remembered: true,
  status: problem || signin ? null : status, at: Date.now(),
  devices: devices.map(d => ({ ...d, here: d.id === id && !problem, selected: d.id === sel })),
});
let sel = id;
const prefs = { checkMinutes: 5, stayIn: true, smsNotify: true, statusBar: true, lowAlert: true, recordUsage: true, hotAlert: true, fastWhenLow: true, slowInSaver: true, autoUpdate: true, batteryExempt: false, notifications: true };
const ui = {};
const msgs = mode === 'empty' ? [] : [
  { id: '7', number: 'Example Net', text: 'Your data plan has 4.2 GB left and renews on the 30th.', time: now - 42 * 60e3, unread: true, outgoing: false },
  { id: '6', number: '+234 800 000 0000', text: 'Running late, see you at six.', time: now - 3 * 3600e3, unread: true, outgoing: false },
  { id: '5', number: '+234 800 000 0000', text: 'On my way.', time: now - 26 * 3600e3, unread: false, outgoing: true },
  { id: '4', number: 'Example Net', text: 'Dial *100# to see your offers.', time: now - 4 * 864e5, unread: false, outgoing: false },
];
// A fake hourly record: [hour, box bytes, this phone's bytes], 40 days, with a gap two days ago.
const rows = [];
for (let h = hr - 40 * 24; h <= hr; h++) {
  const local = new Date(h * 3.6e6).getHours();
  if (h > hr - 50 && h < hr - 40) continue;
  const day = local >= 7 && local <= 23 ? 1 : 0.15;
  const box = Math.round((60 + 140 * Math.abs(Math.sin(h * 1.7))) * 1e6 * day);
  rows.push([h, box, Math.round(box * (0.35 + 0.25 * Math.abs(Math.cos(h))))]);
}
const dev = {
  [ME]: { name: 'This phone', alias: null, mine: true },
  'AA:BB:CC:00:00:02': { name: 'Laptop', alias: null, mine: true },
  'AA:BB:CC:00:00:03': { name: null, alias: null, mine: false },
  'AA:BB:CC:00:00:04': { name: 'Tablet', alias: null, mine: false },
};
const mifiFields = {
  SSID1: 'MiFi-1A2B', HideSSID: '0', MAX_Access_num: '10', AuthMode: 'WPA2PSK', WPAPSK1_encode: btoa('example-pass'),
  sysIdleTimeToSleep: '10', wifi_coverage: 'medium_mode', net_select: 'NETWORK_auto', ConnectionMode: 'auto_dial', roam_setting_option: 'off',
  profile_name: 'Example', wan_apn: 'web.example', ppp_auth_mode: 'none', upnpEnabled: '1', DMZEnable: '0', IPPortFilterEnable: '0', PortForwardEnable: '0',
  pin_status: '0', mgmt_quicken_power_on: '0', cr_version: 'CR_U10LSV1.0.0B05', wa_inner_version: 'BD_U10LSV1.0.0B05', hardware_version: 'U10LS-1.0',
  realtime_time: String(status.sessionSec), realtime_rx_bytes: '842000000', realtime_tx_bytes: '96000000', ussd_write_flag: '16',
  ussd_data_info: { ussd_data: [...'Balance: 4.2 GB, valid to 30 Nov.'].map(c => c.charCodeAt(0).toString(16).padStart(4, '0')).join(''), ussd_action: '0' },
};
const sum = (from, to, k) => rows.filter(r => r[0] >= from && r[0] < to).reduce((a, r) => a + r[k], 0);
const api = {
  hello: () => ({ app: '1.0.0-dev', web: 1, native: 1, prefs, ui, insets: { bottom: 0, ime: 0 }, route: null, state: state() }),
  state, refresh: state, find: state,
  live: a => { clearInterval(api._t); if (a.on) api._t = setInterval(tick, 1000); return true; },
  delay: () => 38 + Math.round(Math.random() * 14),
  signIn: a => ({ ok: false, error: 'That password didn’t work.', attemptsLeft: 4, state: state() }),
  signOut: state,
  'mifi.get': a => { const o = {}; a.cmd.split(',').forEach(k => o[k] = mifiFields[k]); return o; },
  'mifi.set': a => { const p = a.params || {}; if (p.sysIdleTimeToSleep) mifiFields.sysIdleTimeToSleep = p.sysIdleTimeToSleep; if (p.WiFiCoverage) mifiFields.wifi_coverage = p.WiFiCoverage;
    if (p.BearerPreference) mifiFields.net_select = p.BearerPreference; if (p.ConnectionMode) mifiFields.ConnectionMode = p.ConnectionMode; if (p.upnp_setting_option) mifiFields.upnpEnabled = p.upnp_setting_option; return { result: 'success' }; },
  'router.call': a => ({ 230: { ssid: 'Home-2.4G', enable: '1' }, 231: { ssid: 'Home-5G', enable: '1' }, 248: { apn: 'web.example' }, 561: { ussd_result: 'Balance: 12.0 GB.' } }[a.body.cmd] || {}),
  'sms.list': () => msgs, 'sms.read': a => { msgs.forEach(m => a.ids.includes(m.id) && (m.unread = false)); return true; },
  'sms.unread': a => { msgs.forEach(m => a.ids.includes(m.id) && (m.unread = true)); return true; },
  'sms.delete': a => { a.ids.forEach(i => msgs.splice(msgs.findIndex(m => m.id === i), 1)); return true; },
  'sms.send': a => { msgs.unshift({ id: String(Date.now()), number: a.number, text: a.text, time: Date.now(), unread: false, outgoing: true }); return true; },
  'usage.hours': a => rows.filter(r => r[0] >= a.from && r[0] < a.to),
  'usage.devices': a => Object.entries(dev).map(([mac, d], i) => {
    const tot = sum(a.from, a.to, 1), mine = sum(a.from, a.to, 2);
    return { mac, name: d.name, alias: d.alias, mine: d.mine, first: now - (30 - i * 6) * 864e5, last: i === 3 ? now - 3 * 864e5 : now,
      lo: Math.round((tot - mine) * (0.1 + i * 0.05)), hi: Math.round((tot - mine) * (0.6 - i * 0.05)), onlineSec: i === 3 ? 0 : Math.round((a.to - a.from) * 3600 * (0.7 - i * 0.15)) };
  }),
  'usage.client': a => rows.filter(r => r[0] >= a.from && r[0] < a.to && r[0] % 3).map(r => [r[0], 3600, 0, 0]),
  'usage.temps': a => rows.filter(r => r[0] >= a.from).map(r => [r[0], 48 + (r[0] % 7), 54 + (r[0] % 9)]),
  'usage.setMine': a => { dev[a.mac].mine = a.mine; return true; },
  'usage.rename': a => { dev[a.mac].alias = a.name || null; return true; },
  'usage.me': () => ME,
  'usage.info': () => ({ hours: rows.length, devices: 4, since: (hr - 40 * 24) * 3.6e6, bytes: 186000 }),
  'usage.clear': () => true,
  'prefs.get': () => prefs, 'prefs.set': a => Object.assign(prefs, a), 'ui.set': a => Object.assign(ui, a),
  'device.list': () => state().devices,
  'device.select': a => { sel = a.id; return state(); },
  'device.rename': a => { devices.find(d => d.id === a.id).name = a.name; return state(); },
  'update.info': () => ({ app: '1.0.0-dev', web: 1, repo: 'hebronmimam/mifi-app', checkedAt: now - 5 * 3600e3, latest: upd }),
  'update.check': () => upd,
  'update.web': () => false, 'update.install': () => ({ needsPermission: true }),
  'battery.isExempt': () => false,
};
['openOriginal', 'share', 'haptic', 'notif.request', 'battery.exempt', 'usage.export', 'update.allow', 'wifi.settings', 'openUrl', 'exit']
  .forEach(k => api[k] = a => { console.log('[native]', k, a); return true; });

const port = {
  onmessage: null,
  postMessage(s) {
    const { id: rid, m, a } = JSON.parse(s);
    setTimeout(() => {
      let r, ok = true;
      try {
        if (!api[m]) throw Object.assign(new Error(`No ${m} in the stand-in`), { code: 'error' });
        if (problem && /^(mifi|router|sms)\./.test(m)) throw Object.assign(new Error('Can’t reach it'), { code: 'offline' });
        if (signin && /^(mifi|router|sms)\./.test(m)) throw Object.assign(new Error('Sign in first'), { code: 'signin' });
        r = api[m](a || {});
      } catch (e) { ok = false; r = { code: e.code || 'error', message: e.message }; }
      port.onmessage?.({ data: JSON.stringify(ok ? { id: rid, ok, r } : { id: rid, ok, e: r }) });
    }, 60 + Math.random() * 120);
  },
};
function emit(ev, d) { port.onmessage?.({ data: JSON.stringify({ ev, d }) }); }
function tick() {
  const t = Date.now() / 1000;
  emit('live', { rxRate: dropped ? 0 : Math.round(260000 + 220000 * Math.abs(Math.sin(t / 4)) + Math.random() * 60000),
    txRate: dropped ? 0 : Math.round(30000 + 30000 * Math.abs(Math.cos(t / 3))), bars: 4, battery: status.battery, charging: false, connected: !dropped, t: Date.now() });
}
window.MiFiNative = port;
})();

/*
  MiFi app screens. Everything that touches the phone or the box goes through
  MiFiNative (see bridge/Bridge.kt): {id, m, a} out, {id, ok, r|e} back, {ev, d} events.
  Layout and words follow the approved FIMP pick phone (2026-11-16).
*/
(() => {
'use strict';

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const app = $('[data-app]');
const C = { ok: '#BCF500', warn: '#F5B83D', bad: '#FF6B5B', up: '#6CC9FF', idle: '#6C7078' };
const MINUS = '−';
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---------- bridge ----------
const N = (() => {
  let seq = 0;
  const wait = new Map(), subs = {};
  const port = window.MiFiNative;
  port.onmessage = e => {
    let m; try { m = JSON.parse(e.data); } catch { return; }
    if (m.ev) { (subs[m.ev] || []).forEach(f => { try { f(m.d || {}); } catch (x) { console.error(x); } }); return; }
    const w = wait.get(m.id); if (!w) return;
    wait.delete(m.id);
    if (m.ok) w.res(m.r);
    else w.rej(Object.assign(new Error(m.e?.message || 'That didn’t work.'), { code: m.e?.code || 'error' }));
  };
  return {
    call(m, a = {}) {
      return new Promise((res, rej) => { const id = ++seq; wait.set(id, { res, rej }); port.postMessage(JSON.stringify({ id, m, a })); });
    },
    on(ev, f) { (subs[ev] ||= []).push(f); },
  };
})();
const haptic = kind => N.call('haptic', { kind }).catch(() => {});

// ---------- state ----------
const S = {
  st: null, live: null, prefs: {}, ui: {}, app: '', web: 0,
  hist: Array.from({ length: 60 }, () => [0, 0]),
  ping: null, today: null, todayAt: 0,
  tab: 'now', msgs: null, msgErr: null, msg: null,
  page: 'index', D: {}, Dfor: null,
  per: 'month', info: null, me: null, usage: null,
  upd: null, dropAt: 0, nowKey: '',
  confirm: null, liveOn: false, visible: true,
};

const st = () => S.st || {};
const status = () => st().status || {};
const here = () => !!st().id;
const viewId = () => st().selected || st().id;
const devOf = id => (st().devices || []).find(d => d.id === id) || {};
const isRouter = () => (st().kind || devOf(viewId()).kind || (viewId() || '').split(':')[0]) === 'router';
const what = () => isRouter() ? 'router' : 'MiFi';
const viewIsHere = () => here() && viewId() === st().id;
const devName = (id = viewId()) => devOf(id).name || (st().id === id && st().name) || (isRouter() ? 'Home router' : 'MiFi');
const signedIn = () => viewIsHere() && st().signedIn && !st().needsSignIn;

// ---------- formats ----------
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const two = n => String(n).padStart(2, '0');
const hhmm = d => `${two(d.getHours())}:${two(d.getMinutes())}`;
const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
const dayName = d => `${DOW[d.getDay()]} ${d.getDate()} ${MON[d.getMonth()]}`;
const dateLong = ms => { const d = new Date(ms); return `${dayName(d)} ${d.getFullYear()}`; };
const when = ms => {
  if (!ms) return '';
  const d = new Date(ms), now = new Date(), y = new Date(now); y.setDate(now.getDate() - 1);
  if (sameDay(d, now)) return hhmm(d);
  if (sameDay(d, y)) return 'Yesterday';
  if (now - d < 6 * 864e5) return DOW[d.getDay()];
  return `${d.getDate()} ${MON[d.getMonth()]}`;
};
const whenFull = ms => {
  if (!ms) return 'No date';
  const d = new Date(ms), now = new Date();
  return sameDay(d, now) ? `Today, ${hhmm(d)}` : `${dayName(d)}, ${hhmm(d)}`;
};
const seenAt = ms => {
  if (!ms) return 'Never';
  const d = new Date(ms), now = new Date();
  return sameDay(d, now) ? `Today, ${hhmm(d)}` : now - d < 300 * 864e5 ? `${dayName(d)}, ${hhmm(d)}` : dateLong(ms);
};
const fmt = b => {
  if (b == null) return '—';
  if (b < 1e6) return `${Math.round(b / 1e3)} KB`;
  if (b < 1e9) return `${Math.round(b / 1e6)} MB`;
  return `${(b / 1e9).toFixed(1)} GB`;
};
const ord = n => n + (n % 100 >= 11 && n % 100 <= 13 ? 'th' : ['th', 'st', 'nd', 'rd'][n % 10] || 'th');
const rng = (lo, hi) => lo < 5e5 ? `up to ${fmt(hi)}` : lo >= 1e9 && hi >= 1e9 ? `${(lo / 1e9).toFixed(1)}–${(hi / 1e9).toFixed(1)} GB` : `${fmt(lo)}–${fmt(hi)}`;
const dur = sec => {
  if (sec == null) return '';
  const m = Math.floor(sec / 60), h = Math.floor(m / 60), d = Math.floor(h / 24);
  if (d) return `${d} d ${h % 24} h`;
  if (h) return `${h} h ${m % 60} min`;
  return `${Math.max(1, m)} min`;
};
const clock = sec => {
  if (sec == null) return '—';
  const d = Math.floor(sec / 86400), h = Math.floor(sec / 3600) % 24, m = Math.floor(sec / 60) % 60, s = Math.floor(sec % 60);
  return d ? `${d} d ${two(h)}:${two(m)}` : `${two(h)}:${two(m)}:${two(s)}`;
};
const hrs = sec => {
  const h = sec / 3600;
  return h < 1 ? `${Math.max(1, Math.round(h * 60))} min` : h < 10 && h % 1 ? `${Math.floor(h)} h ${Math.round(h % 1 * 60)} min` : `${Math.round(h)} h`;
};
const mbps = bps => (bps || 0) * 8 / 1e6;
const gen = t => {
  const v = String(t || '').toUpperCase();
  if (!v || v === 'NO_SERVICE' || v === 'LIMITED_SERVICE') return '';
  if (/NR|5G|ENDC/.test(v)) return '5G';
  if (/CA|LTE\+|LTE_A/.test(v)) return '4G+';
  if (/LTE|4G/.test(v)) return '4G';
  if (/WCDMA|HSPA|HSDPA|HSUPA|UMTS|3G|TD/.test(v)) return '3G';
  if (/GSM|EDGE|GPRS|2G/.test(v)) return '2G';
  return t;
};
const sigWord = (rsrp, bars) => {
  if (rsrp != null) return rsrp >= -80 ? 'Excellent' : rsrp >= -90 ? 'Good' : rsrp >= -100 ? 'Fair' : 'Weak';
  if (bars != null) return bars >= 4 ? 'Excellent' : bars >= 3 ? 'Good' : bars >= 2 ? 'Fair' : bars >= 1 ? 'Weak' : 'None';
  return '—';
};
const sigPct = (rsrp, bars) => rsrp != null ? Math.max(0, Math.min(100, Math.round((rsrp + 120) / 40 * 100))) : bars != null ? Math.round(bars / 5 * 100) : 0;
const dbm = v => v == null ? '' : `${v < 0 ? MINUS : ''}${Math.abs(Math.round(v))} dBm`;
const ucs2 = h => {
  const s = String(h || '').trim();
  if (!s || s.length % 4 || !/^[0-9a-f]+$/i.test(s)) return s;
  let o = '';
  for (let i = 0; i < s.length; i += 4) o += String.fromCharCode(parseInt(s.slice(i, i + 4), 16));
  return o;
};
const b64dec = s => { try { return decodeURIComponent(escape(atob(s))); } catch { return ''; } };
const H = ms => Math.floor(ms / 3.6e6);

// ---------- small UI parts ----------
const chev = '<svg aria-hidden="true" class="chev" viewBox="0 0 16 16"><path d="M6 3l5 5-5 5" fill="none" stroke="currentColor" stroke-width="1.6"/></svg>';
const lnk = (k, t, s, v = '') => `<button class="li" data-ps="${k}"><div class="t"><b>${t}</b><span>${s}</span></div>${v ? `<span class="v">${v}</span>` : ''}${chev}</button>`;
const goRow = (tab, t, s, v = '') => `<button class="li" data-go="${tab}"><div class="t"><b>${t}</b><span>${s}</span></div>${v ? `<span class="v">${v}</span>` : ''}${chev}</button>`;
const actRow = (a, t, s, v = '', more = '') => `<button class="li" data-act="${a}" ${more}><div class="t"><b>${t}</b><span>${s}</span></div>${v ? `<span class="v">${v}</span>` : ''}</button>`;
const val = (t, v, s = '') => `<div class="li"><div class="t"><b>${t}</b>${s ? `<span>${s}</span>` : ''}</div><span class="v">${v}</span></div>`;
// A switch. kind: "pref" (this app), "dev" (writes to the box), "pending" (shown, explained, not yet writable).
const tog = (t, s, on, key, kind = 'pref') => `<div class="li"><div class="t"><b>${t}</b><span>${s}</span></div><button class="sw" role="switch" aria-checked="${!!on}" aria-label="${esc(t)}" data-${kind}="${key}"${kind === 'pending' ? ' aria-disabled="true" style="opacity:.45"' : ''}></button></div>`;
const seg = (label, key, opts, on) => `<div class="seg" role="group" aria-label="${esc(label)}">${opts.map(([v, t]) => `<button aria-pressed="${String(v) === String(on)}" data-seg="${key}" data-v="${esc(v)}">${t}</button>`).join('')}</div>`;
const back = t => `<button class="back" data-ps="index">‹ Settings</button><h3 class="scr">${t}</h3>`;
const okword = (t, on = true) => `<span class="okword"><span class="dot"${on ? '' : ' style="background:var(--bad);box-shadow:none"'}></span>${t}</span>`;

function toast(msg, kind) {
  const t = $('[data-toast]');
  t.textContent = msg; t.classList.add('show');
  clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('show'), 2400);
  if (kind) haptic(kind);
}
const fail = e => toast(e?.code === 'signin' ? `Sign in to the ${what()} first.` : e?.code === 'offline' ? `Can’t reach the ${what()} right now.` : (e?.message || 'That didn’t work.'), 'reject');

function openSheet(html) {
  const box = $('[data-sheetbox]');
  $('[data-sheetbody]').innerHTML = html;
  box.setAttribute('aria-label', $('h3', box)?.textContent || 'Details');
  box.inert = false; box.tabIndex = -1;
  box.classList.add('open'); $('[data-scrim]').classList.add('open');
  box.scrollTop = 0;
  $$('[data-screen], [data-tabs]').forEach(e => e.inert = true);
  box.focus({ preventScroll: true });
}
function closeSheet() {
  $('[data-sheetbox]').inert = true;
  $$('[data-screen], [data-tabs]').forEach(e => e.inert = false);
  $('[data-tabs]').inert = app.classList.contains('out');
  $('[data-sheetbox]').classList.remove('open'); $('[data-scrim]').classList.remove('open');
  S.confirm = null;
  document.activeElement?.blur?.();
}
const sheetOpen = () => $('[data-sheetbox]').classList.contains('open');

/** A question before anything that can't be taken back. */
function ask({ title, text, yes, danger = true, then }) {
  S.confirm = then;
  openSheet(`<h3 class="scr">${title}</h3><p class="mute">${text}</p>
    <div class="sheet-actions"><button class="btn ghost" data-act="no">Cancel</button><button class="btn${danger ? ' danger' : ''}" data-act="yes">${yes}</button></div>`);
}

// ---------- mood and the Now screen ----------
function reading() {
  const s = status(), L = S.live || {};
  return {
    battery: L.battery ?? s.battery, charging: L.charging ?? s.charging,
    connected: L.connected ?? s.connected, bars: L.bars ?? s.bars,
    rsrp: s.rsrp, rx: L.rxRate ?? s.rxRate, tx: L.txRate ?? s.txRate,
  };
}

function mood() {
  if (!viewIsHere() || !st().status || st().needsSignIn || st().problem) return 'idle';
  const r = reading();
  if (r.connected === false) return 'bad';
  if (isRouter() || r.battery == null) return 'ok';
  if (!r.charging && r.battery < 15) return 'bad';
  if (!r.charging && r.battery < 30) return 'warn';
  return 'ok';
}

const p = k => $(`[data-p="${k}"]`);

function paintNow() {
  const m = mood(), r = reading(), s = status(), router = isRouter();
  app.dataset.mood = m;
  app.style.setProperty('--state', C[m]);
  if (r.connected === false) { if (!S.dropAt) S.dropAt = Date.now(); } else S.dropAt = 0;

  const prob = st().problem, isHere = viewIsHere();
  let since, link;
  if (!isHere && viewId() && here()) { since = 'Not in reach'; link = `You’re on ${esc(devName(st().id))}’s Wi-Fi`; }
  else if (prob === 'wifi' || (!here() && !prob && S.st)) { since = 'Not in reach'; link = 'Join its Wi-Fi to see it'; }
  else if (prob === 'unknown') { since = 'Unknown Wi-Fi'; link = 'Not a MiFi or router this app knows'; }
  else if (prob === 'offline') { since = 'Not answering'; link = 'It may be restarting'; }
  else if (!S.st) { since = 'Looking…'; link = 'Finding it on your Wi-Fi'; }
  else if (st().needsSignIn) { since = 'Signed out'; link = 'Sign in to see everything'; }
  else if (r.connected === false) {
    since = S.dropAt && Date.now() - S.dropAt > 5000 ? `Dropped ${clock((Date.now() - S.dropAt) / 1000).replace(/^00:/, '')} ago` : 'No internet';
    link = 'No internet. Wi-Fi is still on';
  } else {
    const up = router ? s.uptimeSec : s.sessionSec;
    since = up ? `Up ${dur(up)}` : 'Online';
    const g = gen(s.network);
    link = router ? `Online on ${esc(s.provider || 'the mobile network')}, plugged in` : `Online on ${esc(s.provider || 'the mobile network')}${g ? ' ' + g : ''}`;
  }
  p('since').textContent = since;
  p('link').innerHTML = link;
  if (p('name').textContent !== devName()) { p('name').textContent = devName(); fitName(); }
  p('art').classList.toggle('router', router);
  $$('[data-led]').forEach(g => g.classList.toggle('on', isHere && r.connected !== false && !!st().status));
  p('battfill').setAttribute('width', Math.max(2, 21 * (r.battery || 0) / 100).toFixed(1));

  const ring = router ? sigPct(r.rsrp, r.bars) : (r.battery ?? 0);
  $('[data-ring]').setAttribute('stroke-dashoffset', (829.4 * (1 - (isHere ? ring : 0) / 100)).toFixed(1));
  // The box adds to its counters in bursts, so one second reads 2 Mbps and the next 25: show the last 3 s averaged.
  const on = isHere && r.connected !== false;
  const avg = k => S.liveOn ? S.hist.slice(-3).reduce((a, v) => a + v[k], 0) / 3 : mbps(k ? r.tx : r.rx);
  [['down', 0], ['up', 1]].forEach(([k, i]) => {
    const v = on ? avg(i) : 0, n = Math.round(speedPos(v) * 20);
    p(k).textContent = v.toFixed(1);
    $$(`[data-seg="${k}"] i`).forEach((s, j) => s.classList.toggle('on', j < n));
  });
  if (router) {
    p('pct').textContent = isHere ? `${ring}%` : '—';
    p('battline').textContent = s.tempC != null ? `Signal, router at ${Math.round(s.tempC)} °C` : 'Signal, plugged in';
  } else {
    p('pct').textContent = r.battery != null && isHere ? `${r.battery}%` : '—';
    p('battline').textContent = !isHere ? 'Battery, last seen' : r.battery == null ? (st().signedIn ? 'Battery not reported' : 'Sign in to see the battery')
      : r.charging ? 'Battery, charging' : r.battery < 15 ? 'Battery low, charge it soon' : 'Battery';
  }
  p('sigword').textContent = isHere ? sigWord(r.rsrp, r.bars) : '—';
  p('sig').textContent = isHere ? (r.rsrp != null ? dbm(r.rsrp) : router ? 'from the router' : '') : '';
  p('ping').textContent = S.ping != null && isHere && r.connected !== false ? `${S.ping} ms` : '—';
  p('today').textContent = S.today != null ? fmt(S.today) : '—';
  paintNowState();
}

/** The name steps down the type scale (20, 16, 14) until it fits beside the picture. */
function fitName() {
  const s = p('name'), b = s.parentNode;
  if (!s.clientWidth) return; // not laid out (app in the background): fit on the next paint
  for (const px of [20, 16, 14]) { b.style.fontSize = px + 'px'; if (s.scrollWidth <= s.clientWidth + 1) break; }
}
document.fonts?.ready.then(fitName);
addEventListener('resize', fitName);

/** When the box can't be shown live, the dial gives way to one clear next step. */
function paintNowState() {
  const prob = st().problem, name = esc(devName());
  let key = 'live';
  if (!S.st) key = 'live';
  else if (here() && !viewIsHere()) key = 'other';
  else if (prob === 'wifi' || !here()) key = prob === 'unknown' ? 'unknown' : prob === 'offline' ? 'offline' : 'wifi';
  else if (prob === 'unknown' || prob === 'offline') key = prob;
  else if (st().needsSignIn) key = 'signin:' + (st().lockedUntil > Date.now() ? 'locked' : 'open');
  const box = $('[data-nowstate]'), liveEl = $('[data-live]');
  liveEl.hidden = key !== 'live';
  box.hidden = key === 'live';
  if (key === S.nowKey) return;
  S.nowKey = key;
  const H3 = (h, t, b = '') => `<div class="state-box"><h3>${h}</h3><p>${t}</p>${b}</div>`;
  if (key === 'wifi') box.innerHTML = H3(`Join ${name}’s Wi-Fi`, `The app talks to your ${what()} over its own Wi-Fi. What it has recorded stays here while you’re away.`,
    '<button class="btn wide" data-act="wifi">Open Wi-Fi settings</button>');
  else if (key === 'unknown') box.innerHTML = H3('This Wi-Fi isn’t one the app knows', 'It works with ZTE pocket MiFis, like the MTN U10LS, and Tozed ZLT home routers. Join one of those.',
    '<button class="btn wide" data-act="wifi">Open Wi-Fi settings</button><button class="btn ghost wide" style="margin-top:16px" data-act="find">Look again</button>');
  else if (key === 'offline') box.innerHTML = H3('It isn’t answering', 'Your phone is on its Wi-Fi, but the box didn’t reply. It may be restarting; that takes about a minute.',
    '<button class="btn wide" data-act="find">Try again</button>');
  else if (key === 'other') box.innerHTML = H3(`${name} isn’t in reach`, `Your phone is on ${esc(devName(st().id))}’s Wi-Fi. Switch to it to see it live, or join ${name}’s Wi-Fi.`,
    `<button class="btn wide" data-dev="${esc(st().id)}">Show ${esc(devName(st().id))}</button>`);
  else if (key.startsWith('signin')) box.innerHTML = signInForm();
}

function signInForm() {
  const locked = st().lockedUntil > Date.now(), w = what();
  const secs = Math.ceil((st().lockedUntil - Date.now()) / 1000);
  return `<div class="state-box"><h3>Sign in to ${esc(devName())}</h3>
    <p>${st().borrowed
      ? `This phone is signed in through a browser, and that ends on its own. Type the ${w}’s admin password here once so the app can keep you signed in.`
      : `Type the ${w}’s admin password once. The app keeps it on this phone, locked by Android, and signs itself in from then on.`}</p>
    <form data-signin autocomplete="on">
      <div class="field"><label for="si-pw">Password</label><div class="pw"><input id="si-pw" name="password" type="password" autocomplete="current-password" autocapitalize="off" autocorrect="off" spellcheck="false" required${locked ? ' disabled' : ''}><button type="button" data-act="peek" aria-label="Show the password">Show</button></div></div>
      <div class="list">${tog('Stay signed in', 'Password kept on this phone only', S.prefs.stayIn !== false, 'signinStay', 'local')}</div>
      <p class="err" data-signin-err aria-live="polite">${locked ? `The ${w} is locked after too many tries. Try again in ${secs < 90 ? secs + ' s' : Math.ceil(secs / 60) + ' min'}.` : ''}</p>
      <button class="btn wide" type="submit"${locked ? ' disabled' : ''}>Sign in</button>
    </form></div>`;
}

async function signIn(form) {
  const input = $('#si-pw', form), btn = $('button[type=submit]', form), err = $('[data-signin-err]', form);
  const remember = $('[data-local="signinStay"]', form).getAttribute('aria-checked') === 'true';
  if (!input.value) { err.textContent = 'Type the password first.'; input.focus(); return; }
  btn.classList.add('busy'); btn.textContent = 'Signing in…'; err.textContent = '';
  try {
    const r = await N.call('signIn', { password: input.value, remember });
    if (r.ok) {
      input.value = '';
      toast('Signed in. The app stays signed in from now on.', 'confirm');
      if (remember !== (S.prefs.stayIn !== false)) N.call('prefs.set', { stayIn: remember }).then(v => S.prefs = v).catch(() => {});
      S.nowKey = '';
    } else {
      err.textContent = r.error + (r.attemptsLeft > 0 ? ` ${r.attemptsLeft} ${r.attemptsLeft === 1 ? 'try' : 'tries'} left before it locks.` : '');
      haptic('reject'); input.select();
    }
    if (r.state) onState(r.state);
  } catch (e) { err.textContent = e.message; haptic('reject'); }
  btn.classList.remove('busy'); btn.textContent = 'Sign in';
}

// ---------- live dial ----------
(() => {
  const g = $('[data-ticks]');
  for (let i = 0; i < 60; i++) {
    const a = i / 60 * Math.PI * 2 - Math.PI / 2, r1 = 112, r2 = i % 5 ? 117 : 121;
    const l = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    l.setAttribute('x1', 150 + r1 * Math.cos(a)); l.setAttribute('y1', 150 + r1 * Math.sin(a));
    l.setAttribute('x2', 150 + r2 * Math.cos(a)); l.setAttribute('y2', 150 + r2 * Math.sin(a));
    l.setAttribute('stroke', '#33353A'); l.setAttribute('stroke-width', i % 5 ? 1 : 1.6);
    g.append(l);
  }
})();

// The segment bars use a log scale to 100 Mbps, so 1, 10 and 100 sit a third apart and slow links still show.
function speedPos(v) { return Math.min(1, Math.log10(1 + Math.max(0, v)) / Math.log10(101)); }
$$('[data-seg]').forEach(b => { b.innerHTML = '<i></i>'.repeat(20); });

/** The last minute as two overlapping waves (download under upload), on one shared linear scale. */
function drawTrace() {
  const W = 312, H = 72, n = S.hist.length, top = Math.max(5, ...S.hist.flat()) * 1.15;
  const area = k => {
    const pts = S.hist.map((v, i) => [i / (n - 1) * W, H - 2 - v[k] / top * (H - 8)]);
    let d = `M0 ${H}L0 ${pts[0][1].toFixed(1)}`;
    for (let i = 1; i < n; i++) {
      const [x0, y0] = pts[i - 1], [x1, y1] = pts[i], mx = ((x0 + x1) / 2).toFixed(1);
      d += `C${mx} ${y0.toFixed(1)} ${mx} ${y1.toFixed(1)} ${x1.toFixed(1)} ${y1.toFixed(1)}`;
    }
    return d + `L${W} ${H}Z`;
  };
  $('[data-wd]').setAttribute('d', area(0));
  $('[data-wu]').setAttribute('d', area(1));
}

let delayTimer = 0;
function wantLive() {
  const on = S.visible && S.tab === 'now' && viewIsHere() && !st().needsSignIn && !st().problem;
  if (on === S.liveOn) return;
  S.liveOn = on;
  N.call('live', { on }).catch(() => {});
  clearInterval(delayTimer);
  if (on) {
    const probe = () => N.call('delay').then(ms => { S.ping = ms; paintNow(); }).catch(() => {});
    probe(); delayTimer = setInterval(probe, 10000);
  }
}

async function loadToday() {
  const id = viewId(); if (!id) return;
  if (Date.now() - S.todayAt < 60000) return;
  S.todayAt = Date.now();
  const d = new Date(); d.setHours(0, 0, 0, 0);
  try {
    const rows = await N.call('usage.hours', { dev: id, from: H(d.getTime()), to: H(Date.now()) + 1 });
    S.today = rows.length ? rows.reduce((a, r) => a + r[1], 0) : null;
    paintNow();
  } catch { /* nothing recorded yet */ }
}

// ---------- device switcher ----------
function deviceSheet() {
  const list = st().devices || [];
  const rows = list.map(d => {
    const isHere = d.id === st().id, sel = d.id === viewId();
    const b = isHere ? reading().battery : null;
    const sub = d.kind === 'router' ? `Router, plugged in${isHere ? '' : `, last seen ${when(d.lastSeen)}`}`
      : `Pocket MiFi${b != null ? `, battery ${b}%` : ''}${isHere ? '' : `, last seen ${when(d.lastSeen)}`}`;
    return `<button class="dev" data-dev="${esc(d.id)}"><div><b>${esc(d.name)}</b><span>${sub}</span></div>${sel ? '<span class="tick" aria-label="Showing">✓</span>' : ''}</button>`;
  }).join('');
  openSheet(`<h3 class="scr">Your devices</h3>${rows || '<p class="empty">None yet. Join your MiFi’s or router’s Wi-Fi and the app finds it.</p>'}
    <p class="mp-note">The app asks the box what it is before signing in, so a MiFi and a router can share an address. Live readings need that box’s Wi-Fi; its record is here any time.</p>
    ${viewId() ? `<div class="sheet-actions"><button class="btn ghost one" data-act="renamedev">Rename ${esc(devName())}</button></div>` : ''}`);
}

function renameSheet(title, value, act) {
  openSheet(`<h3 class="scr">${title}</h3><div class="field"><label for="rn">Name</label><input id="rn" name="name" maxlength="40" autocomplete="off" value="${esc(value)}"></div>
    <button class="btn wide" data-act="${act}">Save the name</button>`);
  setTimeout(() => $('#rn')?.focus(), 300);
}

// ---------- tabs ----------
function nav(id) {
  S.tab = id;
  S.sel = null; paintSelBar();
  $$('[data-screen]').forEach(s => s.hidden = s.dataset.screen !== id);
  $$('[data-tabs] button').forEach(b => b.dataset.go === id ? b.setAttribute('aria-current', 'page') : b.removeAttribute('aria-current'));
  const s = $(`[data-screen="${id}"]`); if (s) s.scrollTop = 0;
  closeSheet();
  if (id === 'msgs') { S.msg = null; renderMsgs(); loadMsgs(); }
  if (id === 'usage') loadUsage();
  if (id === 'ussd') { S.ussdHere = viewIsHere(); renderUssd(); }
  if (id === 'settings') setPage('index');
  lensTo($(`[data-go="${id}"]`, tabsEl), true);
  wantLive();
}

// The tab lens (master components 10). It appears in place the first time and after a layout change, and glides on a pick.
const tabsEl = $('[data-tabs]'), lensEl = document.createElement('span');
lensEl.className = 'tb-lens'; lensEl.setAttribute('aria-hidden', 'true'); tabsEl.prepend(lensEl);
let lensTimer = 0;
function lensTo(b, glide) {
  if (!b || !b.offsetWidth) return; // the bar is hidden (signed out, keyboard up): placed when it shows
  const place = () => { tabsEl.style.setProperty('--lens-x', `${b.offsetLeft}px`); tabsEl.style.setProperty('--lens-w', `${b.offsetWidth}px`); };
  clearTimeout(lensTimer);
  if (!glide || !tabsEl.dataset.lens) {
    tabsEl.dataset.lensJump = ''; place(); tabsEl.dataset.lens = 'park';
    lensEl.getBoundingClientRect();
    requestAnimationFrame(() => delete tabsEl.dataset.lensJump);
    return;
  }
  place(); tabsEl.dataset.lens = 'on';
  lensTimer = setTimeout(() => { tabsEl.dataset.lens = 'park'; }, 600);
}
new ResizeObserver(() => lensTo($('[aria-current="page"]', tabsEl))).observe(tabsEl);

// ---------- messages ----------
async function loadMsgs() {
  if (!viewIsHere()) { S.msgs = null; S.msgErr = { code: 'away' }; renderMsgs(); return; }
  try { S.msgs = await N.call('sms.list', { limit: 60 }); S.msgErr = null; }
  catch (e) { S.msgErr = e; }
  if (S.tab === 'msgs') renderMsgs();
  badge();
}

function badge() {
  const n = S.msgs ? S.msgs.filter(m => m.unread && !m.outgoing).length : (viewIsHere() ? status().smsUnread || 0 : 0);
  const b = $('[data-badge]');
  b.hidden = !n; b.textContent = n > 99 ? '99+' : n;
  $('[data-tabs] [data-go="msgs"]').setAttribute('aria-label', n ? `Messages, ${n} unread` : 'Messages');
}

const from = m => m.outgoing ? `To ${m.number || 'unknown'}` : (m.number || 'Unknown sender');
const balanceCode = () => S.ui.ussd || (/MTN/i.test(status().provider || '') ? '*323*4#' : '');

function renderMsgs() {
  const el = $('[data-msgs]'), m = S.msg && S.msgs?.find(x => x.id === S.msg);
  if (m) {
    el.innerHTML = `<button class="back" data-act="msgback">‹ Messages</button><h3 class="scr">${esc(from(m))}</h3>
      <p class="msg-meta">${whenFull(m.time)}, on ${esc(devName())}</p>
      <div class="bubble${m.outgoing ? ' out' : ''}">${esc(m.text)}</div>
      <div class="mp-actions">${m.outgoing ? '' : '<button class="btn ghost" data-act="reply">Reply</button><button class="btn ghost" data-act="unreadmsg">Mark as unread</button>'}<button class="btn danger" data-act="delmsg">Delete</button></div>`;
    if (m.unread) {
      m.unread = false; badge();
      N.call('sms.read', { ids: [m.id] }).catch(() => {});
    }
    paintSelBar();
    return;
  }
  let body;
  if (!S.msgs && S.msgErr) {
    const e = S.msgErr;
    body = `<p class="empty">${e.code === 'away' ? `Messages live on the ${what()}. Join its Wi-Fi to read them.` : e.code === 'signin' ? `Sign in to the ${what()} on the Now screen to read its messages.` : `Can’t reach the ${what()} right now.`}</p>`;
  } else if (!S.msgs) body = '<p class="empty">Reading the messages…</p>';
  else if (!S.msgs.length) body = `<p class="empty">No texts on the ${what()}.</p>`;
  else if (S.sel) body = S.msgs.map(x => {
    const on = S.sel.has(x.id), un = x.unread && !x.outgoing;
    return `<button class="li msg${un ? ' unread' : ''}" role="checkbox" aria-checked="${on}" data-pick="${esc(x.id)}"><span class="ck" aria-hidden="true"><svg viewBox="0 0 16 16"><path d="M3.5 8.5l3 3 6-7"/></svg></span><div class="t"><b>${esc(from(x))}</b><span>${un ? '<i class="sr">Unread. </i>' : ''}${esc(x.text)}</span></div><span class="v small">${when(x.time)}</span></button>`;
  }).join('');
  else body = S.msgs.map(x => `<button class="li msg${x.unread && !x.outgoing ? ' unread' : ''}" data-msg="${esc(x.id)}"><span class="on-dot${x.unread && !x.outgoing ? '' : ' none'}" ${x.unread && !x.outgoing ? 'aria-label="Unread"' : 'aria-hidden="true"'}></span><div class="t"><b>${esc(from(x))}</b><span>${esc(x.text)}</span></div><span class="v small">${when(x.time)}</span></button>`).join('');
  const unread = (S.msgs || []).filter(x => x.unread && !x.outgoing).length;
  let head;
  if (S.sel) {
    const all = S.msgs?.length && S.sel.size === S.msgs.length;
    head = `<div class="msg-head"><h3 class="scr" aria-live="polite">${S.sel.size ? `${S.sel.size} selected` : 'Select texts'}</h3>
      <button class="hd-act" data-act="selall">${all ? 'None' : 'All'}</button><button class="hd-act" data-act="selend">Done</button></div>`;
  } else {
    head = `<div class="msg-head"><h3 class="scr">Messages</h3>
      ${unread ? '<button class="hd-act" data-act="readall">Read all</button>' : ''}${S.msgs?.length ? '<button class="hd-act" data-act="selstart">Select</button>' : ''}</div>`;
  }
  el.innerHTML = `${head}<div class="list">${body}</div>
    ${S.sel ? '' : `<div class="mp-actions"><button class="btn ghost" data-act="newsms"${viewIsHere() ? '' : ' disabled'}>New message</button></div>`}`;
  paintSelBar();
}

/** While texts are being picked, the tab bar gives way to what can be done with them. */
function paintSelBar() {
  const on = !!S.sel && S.tab === 'msgs' && !S.msg;
  app.classList.toggle('selecting', on);
  const bar = $('[data-selbar]');
  bar.hidden = !on;
  if (!on) return;
  const n = S.sel.size, picked = (S.msgs || []).filter(x => S.sel.has(x.id) && !x.outgoing);
  const can = { selread: picked.some(x => x.unread), selunread: picked.some(x => !x.unread), seldel: n > 0 };
  $$('button', bar).forEach(b => b.disabled = !can[b.dataset.act]);
}

function selEnd() { S.sel = null; renderMsgs(); }

/** Read, unread or delete the picked texts. The list changes once the box says yes. */
async function selDo(kind, ids = [...(S.sel || [])]) {
  const w = what();
  const find = id => S.msgs?.find(x => x.id === id);
  try {
    if (kind === 'read') {
      const todo = ids.filter(id => find(id)?.unread);
      if (!todo.length) return;
      await N.call('sms.read', { ids: todo });
      todo.forEach(id => find(id).unread = false);
      toast(todo.length === 1 ? 'Marked as read' : `${todo.length} marked as read`, 'confirm');
    } else if (kind === 'unread') {
      const todo = ids.filter(id => { const x = find(id); return x && !x.unread && !x.outgoing; });
      if (!todo.length) return;
      await N.call('sms.unread', { ids: todo });
      todo.forEach(id => find(id).unread = true);
      toast(todo.length === 1 ? 'Marked as unread' : `${todo.length} marked as unread`, 'confirm');
    } else if (kind === 'delete') {
      const ok = await N.call('sms.delete', { ids });
      if (!ok) throw new Error(`The ${w} didn’t delete them.`);
      S.msgs = S.msgs.filter(x => !ids.includes(x.id));
      toast(ids.length === 1 ? `Deleted from the ${w}` : `${ids.length} deleted from the ${w}`, 'confirm');
    }
    S.sel = null; renderMsgs(); badge();
  } catch (e) { fail(e); }
}

function openMsg(id) {
  S.msg = id;
  if (id && !S.msgs) { loadMsgs().then(renderMsgs); }
  renderMsgs();
  $('[data-screen="msgs"]').scrollTop = 0;
}

function composeSheet(to = '') {
  openSheet(`<h3 class="scr">${to ? `Reply to ${esc(to)}` : 'New message'}</h3>
    <div class="field"><label for="sm-to">To</label><input id="sm-to" name="to" inputmode="tel" autocomplete="off" value="${esc(to)}"></div>
    <div class="field"><label for="sm-text">Message</label><textarea id="sm-text" name="text" maxlength="760"></textarea></div>
    <button class="btn wide" data-act="sendsms">Send from the ${what()}</button>
    <p class="mp-note">Sent from the ${what()}’s SIM, at your network’s normal rate.</p>`);
  setTimeout(() => $(to ? '#sm-text' : '#sm-to')?.focus(), 300);
}

// ---------- USSD: balance and other network codes ----------
/** The USSD tab: check balance first, then codes used before, then any code. */
function renderUssd() {
  const el = $('[data-ussd]'), w = what(), bal = balanceCode(), here = viewIsHere();
  const recent = (S.ui.ussdRecent || []).filter(c => c !== bal).slice(0, 4);
  const row = (code, title, sub) => `<button class="li" data-ussdgo="${esc(code)}"${here ? '' : ' disabled'}><div class="t"><b>${title}</b><span>${sub}</span></div>${chev}</button>`;
  el.innerHTML = `<h3 class="scr">USSD</h3>
    <div class="list">${bal ? row(bal, 'Check balance', `Sends ${esc(bal)}`)
      : `<button class="li" data-act="balcode"><div class="t"><b>Check balance</b><span>Add your network’s balance code once</span></div>${chev}</button>`}${recent.map(c => row(c, esc(c), 'Sent before')).join('')}</div>
    ${bal ? '<button class="raw" style="margin-top:var(--s-2)" data-act="balcode">Change the balance code</button>' : ''}
    <div class="sec">Send a code</div>
    <div class="field"><label for="us-ussd">Code</label><input id="us-ussd" name="ussd" value="" placeholder="${esc(bal || '*123#')}" inputmode="tel" autocomplete="off" spellcheck="false"></div>
    <button class="btn wide" data-act="ussd"${here ? '' : ' disabled'}>Send code</button>
    <div class="ussd-out mute" data-ussdout aria-live="polite">${here ? 'The reply from your network shows here.' : `Join the ${w}’s Wi-Fi to send a code.`}</div>
    <p class="mp-note">Sent from the ${w}’s SIM to your network, the same as dialling it on a phone.</p>`;
}

async function runUssd(btn, given) {
  const root = btn.closest('[data-ussd]');
  const input = $('input[name=ussd]', root), out = $('[data-ussdout]', root), code = (given || input.value).trim();
  if (given) input.value = given;
  if (!/^[*#0-9]+$/.test(code)) { out.textContent = 'A code is made of digits, * and #.'; return; }
  if (!viewIsHere()) { out.textContent = `Join the ${what()}’s Wi-Fi first.`; return; }
  if (code !== balanceCode()) { S.ui.ussdRecent = [code, ...(S.ui.ussdRecent || []).filter(c => c !== code)].slice(0, 6); }
  saveUi();
  out.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
  btn.classList.add('busy'); out.classList.remove('mute'); out.textContent = 'Sending…';
  try { out.textContent = isRouter() ? await ussdRouter(code) : await ussdMifi(code); haptic('confirm'); }
  catch (e) { out.textContent = e.code === 'signin' ? `Sign in to the ${what()} first.` : e.message; haptic('reject'); }
  btn.classList.remove('busy');
}

const done = r => r && (r.result === 'success' || r.result === '0' || r.message === '0' || r.success === true);

async function ussdMifi(code) {
  const r = await N.call('mifi.set', { goformId: 'USSD_PROCESS', params: { USSD_operator: 'ussd_send', USSD_send_number: code, notCallback: 'true' } });
  if (!done(r)) throw new Error('The MiFi didn’t send the code.');
  for (let i = 0; i < 25; i++) {
    await sleep(1000);
    const f = await N.call('mifi.get', { cmd: 'ussd_write_flag', multi: false });
    const v = String(f.ussd_write_flag ?? '');
    if (v === '16') {
      const d = await N.call('mifi.get', { cmd: 'ussd_data_info', multi: false });
      const info = d.ussd_data_info || d;
      const text = ucs2(info.ussd_data) || 'The network answered with no text.';
      if (String(info.ussd_action) !== '1') N.call('mifi.set', { goformId: 'USSD_PROCESS', params: { USSD_operator: 'ussd_cancel', notCallback: 'true' } }).catch(() => {});
      return text;
    }
    if (v && !['15', '0'].includes(v) && i > 3) throw new Error('The network didn’t answer that code. Check it and try again.');
  }
  throw new Error('No reply after 25 seconds. Try again in a minute.');
}

async function ussdRouter(code) {
  // Router USSD (cmd 561): field names follow the router's web app; checked live before relying on them.
  const r = await N.call('router.call', { body: { cmd: 561, method: 'POST', ussd_cmd: code, ussd: code }, timeout: 17000 });
  const text = r.ussd_result || r.ussd_data || r.result || r.data;
  if (!text || text === '0') throw new Error('The router didn’t pass on a reply.');
  return /^[A-Za-z0-9+/=]+$/.test(text) && text.length % 4 === 0 ? (b64dec(text) || text) : text;
}

// ---------- usage ----------
const PW = { day: 'today', week: 'in the last 7 days', month: 'this month', year: 'this year', all: 'since recording started' };

function slots(per) {
  const now = new Date(), y = now.getFullYear(), m = now.getMonth(), d = now.getDate(), out = [];
  const push = (a, b, lab) => out.push({ a: H(a.getTime()), b: H(b.getTime()), lab, future: a > now, live: a <= now && now < b });
  if (per === 'day') {
    for (let i = 0; i < 24; i++) push(new Date(y, m, d, i), new Date(y, m, d, i + 1), `${two(i)}:00`);
    return { out, axis: ['00:00', '12:00', '23:00'], aria: 'Data used each hour today' };
  }
  if (per === 'week') {
    for (let i = 6; i >= 0; i--) { const a = new Date(y, m, d - i); push(a, new Date(y, m, d - i + 1), i ? dayName(a) : 'Today'); }
    return { out, axis: [dayName(new Date(y, m, d - 6)), '', 'Today'], aria: 'Data used each day, last 7 days' };
  }
  if (per === 'month') {
    const n = new Date(y, m + 1, 0).getDate();
    for (let i = 1; i <= n; i++) { const a = new Date(y, m, i); push(a, new Date(y, m, i + 1), i === d ? 'Today' : dayName(a)); }
    return { out, axis: [`1 ${MON[m]}`, '', `${n} ${MON[m]}`], aria: 'Data used each day this month' };
  }
  if (per === 'year') {
    for (let i = 0; i < 12; i++) push(new Date(y, i, 1), new Date(y, i + 1, 1), `${MON[i]} ${y}`);
    return { out, axis: ['Jan', '', 'Dec'], aria: 'Data used each month this year' };
  }
  const since = new Date(S.info?.since || Date.now());
  const months = (y - since.getFullYear()) * 12 + m - since.getMonth() + 1;
  if (months > 24) {
    for (let k = since.getFullYear(); k <= y; k++) push(new Date(k, 0, 1), new Date(k + 1, 0, 1), String(k));
    return { out, axis: [String(since.getFullYear()), '', String(y)], aria: 'Data used each year since recording started' };
  }
  for (let i = 0; i < months; i++) { const a = new Date(since.getFullYear(), since.getMonth() + i, 1); push(a, new Date(a.getFullYear(), a.getMonth() + 1, 1), `${MON[a.getMonth()]} ${a.getFullYear()}`); }
  return { out, axis: [`${MON[since.getMonth()]} ${since.getFullYear()}`, '', `${MON[m]} ${y}`], aria: 'Data used each month since recording started' };
}

// The period picker's lens glides to the pick, the same motion as the tab bar's.
const perEl = $('[data-uperiod]'), perLens = document.createElement('span');
perLens.className = 'seg-lens'; perLens.setAttribute('aria-hidden', 'true'); perEl.prepend(perLens); perEl.classList.add('lensed');
function perLensTo(glide) {
  const b = $('[aria-pressed="true"]', perEl);
  if (!b || !b.offsetWidth) return; // the Usage screen is hidden: placed when it shows
  if (!glide || !perEl.dataset.lens) perEl.dataset.lensJump = '';
  perEl.style.setProperty('--lens-x', `${b.offsetLeft}px`); perEl.style.setProperty('--lens-w', `${b.offsetWidth}px`);
  perEl.dataset.lens = 'on';
  if ('lensJump' in perEl.dataset) { perLens.getBoundingClientRect(); requestAnimationFrame(() => delete perEl.dataset.lensJump); }
}
new ResizeObserver(() => perLensTo(false)).observe(perEl);

async function loadUsage(glide) {
  const id = viewId();
  $$('[data-u]').forEach(b => b.setAttribute('aria-pressed', b.dataset.u === S.per));
  perLensTo(glide === true);
  if (id && viewIsHere() && (!S.blocksAt || Date.now() - S.blocksAt > 60000)) {
    S.blocksAt = Date.now();
    loadBlocks().then(() => { if (S.tab === 'usage' && S.usage) renderUsage(S.usage); });
  }
  if (!id) { renderUsage(null); return; }
  try {
    const [info, me] = await Promise.all([N.call('usage.info'), N.call('usage.me', { dev: id })]);
    S.info = info; S.me = me;
    const sl = slots(S.per), from = sl.out[0].a, to = sl.out[sl.out.length - 1].b;
    const [rows, devs, months] = await Promise.all([N.call('usage.hours', { dev: id, from, to }), N.call('usage.devices', { dev: id, from, to }),
      N.call('usage.months', { dev: id }).catch(() => ({}))]);
    S.usage = { sl, rows, devs, months };
    renderUsage(S.usage);
  } catch (e) { renderUsage(null, e); }
}

function renderUsage(U) {
  const q = k => $(`[data-q="${k}"]`), w = what(), name = esc(devName());
  q('u-restsub').textContent = `The ${w} counts these together`;
  q('u-note').textContent = `Every device that joins is recorded and kept until you clear it. The ${w} counts data for all devices together, not each one, so this phone’s data is exact and other devices show how long they were on. “At least” is what one used while it was the only other device on.`;
  if (!U) {
    q('u-total').textContent = '—'; q('u-sub').textContent = 'Nothing recorded yet. The app records while your phone is on the box’s Wi-Fi.';
    q('u-read').textContent = ''; q('u-chart').innerHTML = ''; q('u-axis').innerHTML = '';
    q('u-yours').innerHTML = q('u-others').innerHTML = '<p class="empty">No devices recorded yet.</p>';
    return;
  }
  const { sl, rows, devs } = U, since = S.info?.since ? H(S.info.since) : Infinity;
  const from = S.info?.since ? new Date(S.info.since) : null, sd = +status().raw?.startDate;
  const boxNow = viewIsHere() ? status().monthBytes : null;
  // The box counts each month itself, from before the app started, and forgets it when the next month starts. The app
  // keeps the most the box showed for every month (when the box's month starts on the 1st, so the two line up), and a
  // year or everything uses that count for each of those months instead of only what the app recorded.
  const boxMonths = { ...(U.months || {}) }, now = new Date(), thisMonth = `${now.getFullYear()}-${two(now.getMonth() + 1)}`;
  if (boxNow != null && !(sd > 1)) boxMonths[thisMonth] = Math.max(boxMonths[thisMonth] || 0, boxNow);
  let extra = 0;
  const adds = new Map(), counted = [];
  if (S.per === 'year' || S.per === 'all') {
    for (const [k, b] of Object.entries(boxMonths).sort()) {
      const [yy, mm] = k.split('-').map(Number), a = H(new Date(yy, mm - 1, 1).getTime()), z = H(new Date(yy, mm, 1).getTime());
      const slot = sl.out.find(s => s.a <= a && a < s.b);
      if (!slot) continue;
      const x = Math.max(0, b - rows.filter(r => r[0] >= a && r[0] < z).reduce((t, r) => t + r[1], 0));
      if (x) { adds.set(slot, (adds.get(slot) || 0) + x); extra += x; counted.push(MON[mm - 1]); }
    }
  }
  const vals = sl.out.map(s => {
    const add = adds.get(s) || 0;
    if (s.future || (s.b <= since && !add)) return undefined;
    const r = rows.filter(x => x[0] >= s.a && x[0] < s.b);
    if (!r.length) return add || (s.live ? undefined : null);
    return r.reduce((a, x) => a + x[1], 0) + add;
  });
  const recorded = rows.reduce((a, x) => a + x[1], 0), total = recorded + extra, phone = Math.min(total, rows.reduce((a, x) => a + x[2], 0));
  // This month comes from the box's own counter, which ran before the app did. Other periods are the app's record.
  const boxMonth = S.per === 'month' ? boxNow : null;
  const fromBox = boxMonth != null && boxMonth >= total, shown = fromBox ? boxMonth : total;
  if (extra) {
    q('u-total').textContent = fmt(total);
    q('u-sub').textContent = `${S.per === 'all' ? 'Everything' : 'This year'} on ${devName()}. ${counted.length > 1 ? `${counted.slice(0, -1).join(', ')} and ${counted.at(-1)} are` : `${counted[0]} is`} the ${w}’s own count; the app recorded the rest${from ? ` since ${from.getDate()} ${MON[from.getMonth()]}` : ''}.`;
  } else if (fromBox) {
    q('u-total').textContent = fmt(boxMonth);
    q('u-sub').textContent = `This month, counted by the ${w}${sd > 1 ? ` since its reset on the ${ord(sd)}` : ''}. The app recorded ${fmt(total)} of it${from ? ` since ${from.getDate()} ${MON[from.getMonth()]}` : ''}.`;
  } else {
    q('u-total').textContent = fmt(total);
    q('u-sub').textContent = `${PW[S.per][0].toUpperCase()}${PW[S.per].slice(1)} on ${devName()}, recorded by the app${rows.length ? '' : '. Nothing recorded for this period'}`;
  }
  q('u-phone').textContent = fmt(phone); q('u-rest').textContent = fmt(shown - phone);
  q('u-restsub').textContent = (fromBox && boxMonth > total) || extra ? `All other use, including before the app started recording` : `The ${w} counts these together`;
  q('u-phonebar').style.width = shown ? `${phone / shown * 100}%` : '0';
  q('u-restbar').style.width = shown ? `${(shown - phone) / shown * 100}%` : '0';
  q('u-axis').innerHTML = sl.axis.map(a => `<span>${esc(a)}</span>`).join('');
  drawChart(sl, vals);

  const on = new Set(viewIsHere() ? (status().clients || []).map(c => c.mac) : []);
  // The box counts every device together, so only this phone gets a data figure. The others show what really
  // differs between them: how long each was on, and the least it used when that can be told apart.
  const devRow = d => {
    const self = d.mac === S.me, now = on.has(d.mac), blocked = !self && isBlocked(d.mac);
    const v = self ? (d.onlineSec ? fmt(phone) : 'None') : d.onlineSec ? hrs(d.onlineSec) : 'None';
    const data = self ? 'Counted exactly by Android' : d.lo > 0 ? `At least ${fmt(d.lo)}` : `Data counted with the others`;
    const sub = blocked ? 'Blocked from the Wi-Fi'
      : d.onlineSec ? `${now ? 'On now. ' : ''}${data}` : `${now ? 'On now. ' : ''}Not on ${PW[S.per]}`;
    const nm = d.alias || d.name || (self ? 'This phone' : 'Unknown device');
    return `<button class="li u-dev" data-rec="${esc(d.mac)}"><span class="on-dot${now ? '' : ' off'}" ${now ? 'aria-label="On now"' : 'aria-hidden="true"'}></span><div class="t"><b>${esc(nm)}</b><span>${sub}</span></div><span class="v num">${v}</span></button>`;
  };
  const mine = devs.filter(d => d.mine || d.mac === S.me), others = devs.filter(d => !(d.mine || d.mac === S.me));
  q('u-yours').innerHTML = mine.map(devRow).join('') || '<p class="empty">Mark a device as yours from its record.</p>';
  q('u-others').innerHTML = others.map(devRow).join('') || '<p class="empty">No other devices.</p>';
  void name;
}

function drawChart(sl, vals) {
  const svg = $('[data-q="u-chart"]'), read = $('[data-q="u-read"]');
  svg.setAttribute('aria-label', sl.aria);
  const W = 342, Ht = 96, base = 95, n = vals.length, slot = W / n, bw = Math.min(24, slot - 2);
  const max = Math.max(1, ...vals.filter(v => v != null));
  let h = `<line class="base" x1="0" x2="${W}" y1="${base + .5}" y2="${base + .5}"/>`;
  vals.forEach((v, i) => {
    if (v === undefined) return;
    const x = i * slot + (slot - bw) / 2, ht = v == null ? 4 : Math.max(2, v / max * (base - 8)), r = Math.min(4, bw / 2, ht), y = base - ht;
    h += `<path class="col${v == null ? ' off' : ''}" data-i="${i}" d="M${x.toFixed(1)} ${base}V${(y + r).toFixed(1)}Q${x.toFixed(1)} ${y.toFixed(1)} ${(x + r).toFixed(1)} ${y.toFixed(1)}H${(x + bw - r).toFixed(1)}Q${(x + bw).toFixed(1)} ${y.toFixed(1)} ${(x + bw).toFixed(1)} ${(y + r).toFixed(1)}V${base}Z"/>`;
  });
  vals.forEach((v, i) => { if (v !== undefined) h += `<rect class="hit" data-i="${i}" x="${(i * slot).toFixed(1)}" y="0" width="${slot.toFixed(1)}" height="${Ht}"/>`; });
  svg.innerHTML = h;
  let b = -1; vals.forEach((v, i) => { if (v != null && (b < 0 || v > vals[b])) b = i; });
  read.textContent = b >= 0 && vals[b] > 0 ? `Busiest: ${sl.out[b].lab}, ${fmt(vals[b])}` : '';
  svg._pick = i => {
    svg.querySelectorAll('.col').forEach(c => c.classList.toggle('dim', +c.dataset.i !== i));
    const v = vals[i];
    read.textContent = v == null ? `${sl.out[i].lab}: no record, the phone was away. Its data is in the next record.` : `${sl.out[i].lab}: ${fmt(v)}`;
  };
}
(() => {
  const svg = $('[data-q="u-chart"]');
  const pick = e => { const t = e.target.closest('.hit'); if (t && svg._pick) svg._pick(+t.dataset.i); };
  svg.addEventListener('pointermove', pick); svg.addEventListener('click', pick);
  svg.addEventListener('pointerleave', () => { if (matchMedia('(hover:hover)').matches && S.usage) renderUsage(S.usage); });
})();

async function recSheet(mac) {
  const d = S.usage?.devs.find(x => x.mac === mac); if (!d) return;
  const id = viewId(), self = mac === S.me, now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6);
  let days = Array(7).fill(0);
  try {
    const rows = await N.call('usage.client', { dev: id, mac, from: H(start.getTime()), to: H(Date.now()) + 1 });
    rows.forEach(r => { const k = Math.floor((r[0] * 3.6e6 - start.getTime()) / 864e5); if (k >= 0 && k < 7) days[k] += r[1]; });
  } catch { /* shows zeros */ }
  const rows = S.usage.rows, phone = Math.min(rows.reduce((a, x) => a + x[1], 0), rows.reduce((a, x) => a + x[2], 0));
  const on = viewIsHere() && (status().clients || []).some(c => c.mac === mac);
  const data = !d.onlineSec ? 'None' : self ? fmt(phone) : d.lo > 0 ? `At least ${fmt(d.lo)}` : 'Not known';
  const nm = d.alias || d.name || (self ? 'This phone' : 'Unknown device');
  const note = self ? 'Android counts this phone’s data exactly.'
    : `The ${what()} counts data for all devices together, not for each one. “At least” is what it carried while this was the only device on besides this phone. While others were on too, its share can’t be told apart.`;
  const extra = d.name ? '' : `It gave no name. Its hardware address ends in ${esc(mac.slice(-5))}. `;
  const lbl = Array.from({ length: 7 }, (_, i) => DOW[new Date(start.getTime() + i * 864e5).getDay()]);
  S.rec = mac;
  openSheet(`<h3 class="scr">${esc(nm)}</h3>
    <div class="list">${self ? val('Yours', 'This phone') : tog('This is mine', 'Moves it between Yours and Others', d.mine, 'mine', 'rec')}${val('First seen', seenAt(d.first))}${val('Last seen', on ? 'On now' : seenAt(d.last))}${val(`Data, ${PW[S.per]}`, `<span class="num">${data}</span>`, self ? 'Exact' : '')}${isBlocked(mac) ? val('Wi-Fi', 'Blocked') : ''}${val(`Online, ${PW[S.per]}`, `<span class="num">${d.onlineSec ? hrs(d.onlineSec) : 'None'}</span>`)}</div>
    <div class="sec">Hours online, last 7 days</div>
    <div class="rec-days">${days.map(s => `<i class="${s ? '' : 'z'}" style="height:${s ? Math.max(4, s / 86400 * 64).toFixed(0) : 2}px" title="${hrs(s || 60)}"></i>`).join('')}</div>
    <div class="rec-lbl">${lbl.map(x => `<span>${x}</span>`).join('')}</div>
    <p class="mp-note">${extra}${note}</p>
    <div class="sheet-actions">${self ? '<button class="btn ghost one" data-act="renamerec">Rename</button>'
      : `<button class="btn ghost" data-act="renamerec">Rename</button>${isBlocked(mac) ? '<button class="btn ghost" data-act="unblockrec">Unblock</button>' : '<button class="btn danger" data-act="blockrec">Block</button>'}`}</div>`);
}

// ---------- blocking a device from the box's Wi-Fi ----------
// Router: the Wi-Fi block list (cmd 278), one per band, which the router keeps off while WPS is on.
// MiFi: the Wi-Fi MAC filter (WIFI_MAC_FILTER). Every write is read back before the screen says it worked.
const MAC = m => String(m || '').toUpperCase().replace(/-/g, ':');
const isBlocked = mac => !!S.blocks && keptOff(S.blocks, MAC(mac));
const BANDS = [['1', '2.4 GHz'], ['0', '5 GHz']];
const wait = ms => new Promise(r => setTimeout(r, ms));

async function readBlocks() {
  if (!viewIsHere()) return null;
  if (isRouter()) {
    const out = {};
    for (const [sub] of BANDS) {
      const r = await N.call('router.call', { body: { cmd: 278, method: 'GET', subcmd: sub } });
      const d = r.datas || {};
      out[sub] = { mode: d.macfilter || 'close', list: (d.maclist || []).map(x => MAC(x.mac)).filter(Boolean) };
    }
    const wps = {};
    for (const [sub, key] of [[0, 'wlan2g_wps_switch'], [1, 'wlan5g_wps_switch']]) {
      wps[sub] = String((await N.call('router.call', { body: { cmd: 132, method: 'GET', subcmd: sub } }))[key]) === '1';
    }
    return { kind: 'router', bands: out, wps };
  }
  const r = await N.call('mifi.get', { cmd: 'ACL_mode,wifi_mac_black_list,wifi_hostname_black_list,wifi_mac_white_list' });
  if (r.ACL_mode == null && r.wifi_mac_black_list == null) return { kind: 'mifi', unsupported: true };
  const split = s => String(s || '').split(';').map(x => x.trim()).filter(Boolean);
  return { kind: 'mifi', mode: String(r.ACL_mode || '0'), black: split(r.wifi_mac_black_list).map(MAC), names: split(r.wifi_hostname_black_list), white: split(r.wifi_mac_white_list).map(MAC) };
}

/** Whether the box keeps this MAC off its Wi-Fi: on a block list, or left off an allow list. */
function keptOff(b, mac) {
  if (!b || b.unsupported) return false;
  if (b.kind === 'router') return Object.values(b.bands).some(x => (x.mode === 'deny' && x.list.includes(mac)) || (x.mode === 'allow' && !x.list.includes(mac)));
  return (b.mode === '2' && b.black.includes(mac)) || (b.mode === '1' && !b.white.includes(mac));
}

async function loadBlocks() {
  try { S.blocks = await readBlocks(); } catch { S.blocks = null; }
}

async function setBlock(mac, block, name) {
  mac = MAC(mac);
  const b = await readBlocks();
  if (!b) throw new Error(`Join the ${what()}’s Wi-Fi first.`);
  if (b.kind === 'router') {
    // The router refuses a block list while WPS is on.
    if (block) for (const sub of [0, 1]) if (b.wps[sub]) {
      const r = await N.call('router.call', { body: { cmd: 132, method: 'POST', subcmd: sub, [sub ? 'wlan5g_wps_switch' : 'wlan2g_wps_switch']: '0' } });
      if (String(r.message) !== '0') throw new Error('The router didn’t turn WPS off, so it can’t block yet.');
      await wait(6000);
    }
    for (const [sub] of BANDS) {
      const { mode, list } = b.bands[sub];
      let datas;
      if (mode === 'allow') { // a white list: being off it is what keeps a device out
        const next = block ? list.filter(m => m !== mac) : [...new Set([...list, mac])];
        datas = { macfilter: 'allow', maclist: next.map(m => ({ mac: m })) };
      } else {
        const cur = mode === 'deny' ? list : [];
        const next = block ? [...new Set([...cur, mac])] : cur.filter(m => m !== mac);
        if (next.length > 32) throw new Error('The router’s block list is full (32 devices).');
        datas = next.length ? { macfilter: 'deny', maclist: next.map(m => ({ mac: m })) } : { macfilter: 'close', maclist: [] };
      }
      const r = await N.call('router.call', { body: { cmd: 278, method: 'POST', subcmd: sub, datas, success: true } });
      if (r.message && r.message !== '0') throw new Error('The router said no to the change.');
    }
    await wait(7000); // the router restarts its Wi-Fi to apply it
  } else {
    if (b.unsupported) throw new Error('This MiFi doesn’t offer blocking through its web API.');
    let params;
    if (b.mode === '1') {
      const next = block ? b.white.filter(m => m !== mac) : [...new Set([...b.white, mac])];
      params = { ACL_mode: '1', wifi_mac_white_list: next.join(';') };
    } else {
      const list = b.mode === '2' ? b.black : [], names = b.mode === '2' ? b.names : [];
      const keep = list.map((m, i) => [m, names[i] || '']).filter(([m]) => m !== mac);
      if (block) keep.push([mac, (name || '').replace(/;/g, ' ').slice(0, 32)]);
      params = { ACL_mode: keep.length ? '2' : '0', wifi_mac_black_list: keep.map(x => x[0]).join(';'), wifi_hostname_black_list: keep.map(x => x[1]).join(';') };
    }
    const r = await N.call('mifi.set', { goformId: 'WIFI_MAC_FILTER', params });
    if (!done(r)) throw new Error('The MiFi said no to the change.');
    await wait(3000);
  }
  S.blocks = await readBlocks();
  if (keptOff(S.blocks, mac) !== block) throw new Error(`The ${what()} didn’t take it. It still ${block ? 'lets it on' : 'keeps it off'}.`);
}

async function blockAsk(block) {
  const mac = S.rec, d = S.usage?.devs.find(x => x.mac === mac), w = what();
  if (!mac || mac === S.me) return;
  const nm = d?.alias || d?.name || 'this device';
  let b;
  try { b = await readBlocks(); } catch (e) { return fail(e); }
  if (!b) return toast(`Join the ${w}’s Wi-Fi to ${block ? 'block' : 'unblock'} it.`);
  if (b.unsupported) return toast('This MiFi doesn’t offer blocking through its web API.', 'reject');
  const wpsOn = b.kind === 'router' && (b.wps[0] || b.wps[1]);
  const text = block
    ? `${esc(nm)} can’t join the ${w}’s Wi-Fi until you unblock it here.${b.kind === 'router' ? ` The Wi-Fi restarts for a few seconds, so your devices drop and reconnect.${wpsOn ? ' This also turns off WPS, which the router keeps off while blocking.' : ''}` : ''}`
    : `${esc(nm)} can join the ${w}’s Wi-Fi again.${b.kind === 'router' ? ' The Wi-Fi restarts for a few seconds.' : ''}`;
  ask({
    title: block ? `Block ${esc(nm)}?` : `Unblock ${esc(nm)}?`, text, yes: block ? 'Block' : 'Unblock', danger: block,
    then: async () => {
      toast(block ? 'Blocking…' : 'Unblocking…');
      try {
        await setBlock(mac, block, d?.alias || d?.name);
        haptic('confirm'); toast(block ? `${nm} is blocked` : `${nm} can join again`, 'confirm');
        if (S.usage) renderUsage(S.usage);
      } catch (e) { fail(e); }
    },
  });
}

// ---------- settings ----------
// Device writes the app makes. Each one is re-read afterwards to confirm the box took it.
// Writes not in this list are shown and explained but not sent until checked on a real box.
const MIFI_READ = 'SSID1,HideSSID,MAX_Access_num,AuthMode,WPAPSK1_encode,sysIdleTimeToSleep,wifi_coverage,net_select,dial_mode,roam_setting_option,' +
  'ppp_status,network_type,network_provider,realtime_time,realtime_rx_bytes,realtime_tx_bytes,lte_rsrp,signalbar,profile_name,m_profile_name,wan_apn,ppp_auth_mode,' +
  'upnpEnabled,DMZEnable,IPPortFilterEnable,PortForwardEnable,pin_status,pinnumber,mgmt_quicken_power_on,cr_version,wa_inner_version,hardware_version';
const MW = {
  sleep: v => ['SET_WIFI_SLEEP_INFO', { sysIdleTimeToSleep: v }, 'sysIdleTimeToSleep', v],
  range: v => ['SET_WIFI_COVERAGE', { WiFiCoverage: v }, 'wifi_coverage', v],
  netmode: v => ['SET_BEARER_PREFERENCE', { BearerPreference: v }, 'net_select', v],
  autoconnect: on => ['SET_CONNECTION_MODE', { ConnectionMode: on ? 'auto_dial' : 'manual_dial', roam_setting_option: S.D.roam_setting_option || 'off' }, 'dial_mode', on ? 'auto_dial' : 'manual_dial'],
  upnp: on => ['UPNP_SETTING', { upnp_setting_option: on ? '1' : '0' }, 'upnpEnabled', on ? '1' : '0'],
};
const PENDING = 'Changing this from the app comes in a screen update, once it has been checked on a real box. Use the original page for now.';

async function loadDevice(force) {
  if (!signedIn()) { S.D = {}; S.Dfor = null; return; }
  if (!force && S.Dfor === st().id) return;
  try {
    if (isRouter()) {
      const D = {};
      for (const cmd of [230, 231, 24, 29, 26, 218, 248, 402, 207]) {
        try { Object.assign(D, { ['c' + cmd]: await N.call('router.call', { body: { cmd, method: 'GET' } }) }); } catch { /* not on this build */ }
      }
      S.D = D;
    } else S.D = await N.call('mifi.get', { cmd: MIFI_READ });
    S.Dfor = st().id;
    if (S.tab === 'settings') setPage(S.page, true);
  } catch (e) { if (e.code !== 'signin') fail(e); }
}

const dv = (k, d = '—') => { const v = S.D[k]; return v == null || v === '' ? d : esc(v); };
const rv = (cmd, keys, d = '—') => { const o = S.D['c' + cmd] || {}; for (const k of keys) if (o[k] != null && o[k] !== '') return esc(o[k]); return d; };
const SLEEP = [['-1', 'Never'], ['10', '10 min'], ['30', '30 min'], ['60', '1 h']];
const RANGE = [['short_mode', 'Short'], ['medium_mode', 'Medium'], ['long_mode', 'Long']];
const NETMODE = [['NETWORK_auto', 'Automatic'], ['Only_LTE', '4G only'], ['Only_WCDMA', '3G only']];
const word = (list, v, d = '—') => (list.find(x => x[0] === v) || [, d])[1];

function setIndex() {
  const m = !isRouter(), s = status(), name = esc(devName()), w = what(), P = S.prefs;
  const live = viewIsHere(), si = signedIn();
  const signed = !live ? `${name} isn’t in reach. Join its Wi-Fi to change its settings.`
    : !si ? `Signed out of ${name}. Sign in on the Now screen.`
    : st().remembered ? `Signed in to ${name}, and stays signed in.` : `Signed in to ${name} until the app closes.`;
  const nClients = live ? (s.clients || []).length : null;
  const up = m ? s.sessionSec : s.uptimeSec;
  return `<h3 class="scr">Settings</h3>
    <p class="signed"><span class="dot"${si ? '' : ' style="background:var(--faint);box-shadow:none"'}></span>${signed}</p>
    <div class="sec">Internet</div><div class="list">
      ${lnk('conn', 'Connection', live && s.connected !== false ? `${esc(s.provider || 'Mobile network')}${up ? `, up ${dur(up)}` : ''}` : 'Not connected', live ? (s.connected === false ? 'Off' : 'On') : '')}
      ${lnk('net', 'Network and APN', m ? `${word(NETMODE, S.D.net_select, 'Mode')}, ${dv('profile_name', dv('m_profile_name', 'APN'))}` : 'Mode and APN')}
    </div>
    <div class="sec">Wi-Fi</div><div class="list">
      ${m ? lnk('wifi', 'Name and password', `${dv('SSID1', esc(s.ssid || 'Wi-Fi'))}, ${dv('AuthMode', 'WPA2').replace('PSK', '').replace('WPA2WPA3', 'WPA2/3')}`)
          : lnk('wifi', 'Two Wi-Fi bands', `${rv(230, ['ssid', 'SSID', 'ssid24'], '2.4 GHz')} and ${rv(231, ['ssid', 'SSID', 'ssid58'], '5 GHz')}`)}
      ${goRow('usage', 'Connected devices', 'Who is on, and their history', nClients != null ? (m && S.D.MAX_Access_num ? `${nClients} of ${dv('MAX_Access_num')}` : String(nClients)) : '')}
      ${m ? lnk('sleep', 'Sleep and range', `${S.D.sysIdleTimeToSleep === '-1' ? 'Never sleeps' : S.D.sysIdleTimeToSleep ? `Sleeps after ${word(SLEEP, S.D.sysIdleTimeToSleep, S.D.sysIdleTimeToSleep + ' min')}` : 'Sleep'}, ${word(RANGE, S.D.wifi_coverage, '').toLowerCase() || 'range'}${S.D.wifi_coverage ? ' range' : ''}`)
          : lnk('limits', 'Speed limits', 'Cap one device so it can’t hog the rest')}
      ${m ? '' : lnk('filter', 'Website filter', 'Block sites for everyone on this Wi-Fi')}
    </div>
    ${m ? `<div class="sec">Battery</div><div class="list">
      ${tog('Battery in the status bar', 'Shows the MiFi’s percentage as an icon', P.statusBar, 'statusBar')}
      ${tog('Alert at 20%', 'A notification before it dies', P.lowAlert, 'lowAlert')}
    </div>` : `<div class="sec">Temperature</div><div class="list">
      ${lnk('temp', 'Temperature', s.tempC != null ? (s.tempC > 70 ? 'Too hot' : 'Normal for a router') : 'Read from the router', s.tempC != null && live ? `<span class="num">${Math.round(s.tempC)} °C</span>` : '')}
      ${tog('Alert when it runs hot', 'A notification above 70 °C', P.hotAlert, 'hotAlert')}
    </div>`}
    <div class="sec">The ${w}</div><div class="list">
      ${lnk('fw', 'Firewall', 'Port filter, forwarding, UPnP, DMZ')}
      ${lnk('adm', 'Password and SIM', 'Admin password, SIM PIN, reset')}
      ${lnk('upd', 'Software update', m ? dv('cr_version', dv('wa_inner_version', esc(s.firmware || 'Version'))) : esc(s.firmware || 'Version'))}
      ${actRow('restart', 'Restart', 'Takes about a minute', '', si ? '' : 'disabled')}
    </div>
    <div class="sec">This app</div><div class="list">
      ${tog('Stay signed in', 'Password kept on this phone only', P.stayIn, 'stayIn')}
      ${tog('Message notifications', `New texts on the ${w} pop up like any app’s`, P.smsNotify && P.notifications !== false, 'smsNotify')}
      ${lnk('bg', 'Checking in the background', `Every ${P.checkMinutes || 5} min, light on battery`)}
      ${lnk('rec', 'Usage record', P.recordUsage === false ? 'Paused' : 'Kept until you clear it')}
      ${lnk('app', 'App updates', updReady() ? `Version ${esc(updReady().version)} is ready` : `Version ${esc(S.app)}, from GitHub`)}
    </div>
    ${m ? `<div class="mp-actions"><button class="btn danger" data-act="shutdown"${si ? '' : ' disabled'}>Shut down the MiFi</button></div>` : ''}
    <button class="raw" data-act="raw"${live ? '' : ' disabled'}>Open the ${w}’s original page</button>`;
}

function setPageHtml(k) {
  const m = !isRouter(), s = status(), w = what(), P = S.prefs, si = signedIn();
  const needLive = ['conn', 'net', 'wifi', 'sleep', 'limits', 'filter', 'fw', 'adm', 'upd'];
  if (needLive.includes(k) && !si) {
    const titles = { conn: 'Connection', net: 'Network and APN', wifi: m ? 'Name and password' : 'Two Wi-Fi bands', sleep: 'Sleep and range', limits: 'Speed limits', filter: 'Website filter', fw: 'Firewall', adm: 'Password and SIM', upd: 'Software update' };
    return back(titles[k]) + `<p class="empty">${viewIsHere() ? `Sign in to the ${w} on the Now screen to see this.` : `Join the ${w}’s Wi-Fi to see this.`}</p>`;
  }
  const pend = (t, sub, on) => tog(t, sub, on, t, 'pending');
  const pg = {
    conn: () => {
      const up = m ? (S.D.realtime_time ?? s.sessionSec) : s.uptimeSec, c = s.connected !== false;
      return back('Connection') + `<div class="list">${val('Status', okword(c ? 'Connected' : 'Not connected', c))}${val('Network', `${esc(s.provider || '—')}${gen(s.network) ? ', ' + gen(s.network) : ''}`)}
        ${val('Connected for', `<span class="num">${clock(up != null ? +up : null)}</span>`)}
        ${m ? val('Received', `<span class="num">${fmt(+S.D.realtime_rx_bytes || null)}</span>`) + val('Sent', `<span class="num">${fmt(+S.D.realtime_tx_bytes || null)}</span>`) : ''}
        ${val('Signal', `<span class="num">${s.rsrp != null ? dbm(s.rsrp) : sigWord(null, s.bars)}</span>`, s.rsrp != null ? sigWord(s.rsrp) : 'Read from the ' + w)}
        ${m ? tog('Connect automatically', 'Reconnects after a drop', S.D.dial_mode !== 'manual_dial', 'autoconnect', 'dev') : pend('Connect automatically', 'Reconnects after a drop', true)}</div>
        <div class="mp-actions"><button class="btn ghost" data-act="${c ? 'disconnect' : 'connect'}"${m ? '' : ' disabled'}>${c ? 'Disconnect' : 'Connect'}</button></div>`;
    },
    net: () => m
      ? back('Network and APN') + `<div class="sec first">Network mode</div>${seg('Network mode', 'netmode', NETMODE, S.D.net_select)}
        <p class="mp-note">Automatic picks 4G whenever it can. 4G only stops it falling back to slower 3G.</p>
        <div class="sec">APN</div><div class="list">${val('Profile', dv('profile_name', dv('m_profile_name')))}${val('APN', dv('wan_apn'))}${val('Sign-in type', { none: 'None', pap: 'PAP', chap: 'CHAP' }[String(S.D.ppp_auth_mode).toLowerCase()] || dv('ppp_auth_mode'))}</div>
        <div class="mp-actions"><button class="btn ghost" data-act="raw">Add a profile on the MiFi’s page</button></div>`
      : back('Network and APN') + `<div class="list">${val('Network', esc(s.network || '—'))}${val('Operator', esc(s.provider || '—'))}${val('APN', rv(248, ['apn', 'APN', 'apn_name', 'profile_apn']))}</div>
        <p class="mp-note">${PENDING}</p><div class="mp-actions"><button class="btn ghost" data-act="raw">Open the router’s page</button></div>`,
    wifi: () => m
      ? back('Name and password') + `<div class="list">${val('Network name', dv('SSID1', esc(s.ssid || '—')))}
          <div class="li"><div class="t"><b>Password</b><span>${dv('AuthMode', 'WPA2').replace('PSK', '')}, the usual kind</span></div><span class="v num" data-pw>••••••••</span><button class="btn ghost sm" data-act="showpw">Show</button></div>
          ${pend('Hide the name', 'Phones must type it to join', S.D.HideSSID === '1')}
          ${val('Most devices at once', `<span class="num">${dv('MAX_Access_num')}</span>`, 'Up to 10')}</div>
          <p class="mp-note">${PENDING}</p><div class="mp-actions"><button class="btn ghost" data-act="raw">Change it on the MiFi’s page</button></div>`
      : back('Two Wi-Fi bands') + `<div class="sec first">2.4 GHz</div><div class="list">${val('Network name', rv(230, ['ssid', 'SSID', 'wifi_ssid']))}${pend('Band on', 'Reaches further, slower', rv(230, ['enable', 'wifi_enable', 'status'], '1') !== '0')}</div>
          <div class="sec">5 GHz</div><div class="list">${val('Network name', rv(231, ['ssid', 'SSID', 'wifi_ssid']))}${pend('Band on', 'Faster, shorter reach', rv(231, ['enable', 'wifi_enable', 'status'], '1') !== '0')}</div>
          <p class="mp-note">Near the router, join the 5 GHz band. Two rooms away, 2.4 GHz holds on better. ${PENDING}</p>
          <div class="mp-actions"><button class="btn ghost" data-act="raw">Change it on the router’s page</button></div>`,
    sleep: () => back('Sleep and range') + `<div class="sec first">Wi-Fi sleeps after</div>${seg('Wi-Fi sleep', 'sleep', SLEEP, S.D.sysIdleTimeToSleep)}
      <p class="mp-note">Never keeps the Wi-Fi up when nobody is using it, so phones don’t drop off. It uses a little more battery.</p>
      <div class="sec">Range</div>${seg('Range', 'range', RANGE, S.D.wifi_coverage)}
      <p class="mp-note">Long reaches further and uses more battery.</p>`,
    limits: () => {
      const list = (S.D.c24?.datas || S.D.c24?.data || []);
      const names = new Map((S.usage?.devs || []).map(d => [d.mac, d.alias || d.name]));
      const rows = Array.isArray(list) && list.length ? list.map(x => `<div class="li"><div class="t"><b>${esc(names.get(String(x.mac || '').toUpperCase()) || x.remark || x.mac || 'Device')}</b><span>${esc(x.mac || '')}</span></div><span class="v num">${esc(x.ippro || x.limit || '—')}</span></div>`).join('')
        : '<p class="empty">No limits set.</p>';
      return back('Speed limits') + `<div class="list">${rows}</div><p class="mp-note">A limit stops one device using the whole connection. The router keeps it even when this phone is away. ${PENDING}</p>
        <div class="mp-actions"><button class="btn ghost" data-act="raw">Set limits on the router’s page</button></div>`;
    },
    filter: () => back('Website filter') + `<div class="list">${pend('Website filter', 'Blocks the sites below for every device', ['1', 'on', 'true'].includes(String(rv(29, ['mode', 'url_filter_mode', 'enable'], '0')).toLowerCase()))}</div>
      <p class="mp-note">${PENDING}</p><div class="mp-actions"><button class="btn ghost" data-act="raw">Add a site on the router’s page</button></div>`,
    temp: () => {
      const t = s.tempC;
      return back('Temperature') + `<div class="u-total temp"><b class="num">${t != null ? Math.round(t) + ' °C' : '—'}</b><span>${t == null ? 'The router hasn’t reported it yet.' : t > 70 ? 'Too hot. Above 70 °C shortens its life.' : 'Normal. Routers run warm; above 70 °C is too hot.'}</span></div>
        <div class="list" style="margin-top:var(--s-4)" data-temps>${val('Highest, last 24 h', '—')}${val('Lowest, last 24 h', '—')}</div>
        <div class="list">${tog('Alert when it runs hot', 'A notification above 70 °C', P.hotAlert, 'hotAlert')}</div>
        <p class="mp-note">Read from the router’s own sensor and kept in the usage record. Keep the top vents clear: that is where the heat leaves.</p>`;
    },
    fw: () => back('Firewall') + `<div class="list">${pend('Port filter', 'Block chosen ports and addresses', S.D.IPPortFilterEnable === '1')}${pend('Port forwarding', 'Let outside traffic reach a device', S.D.PortForwardEnable === '1')}
      ${m ? tog('UPnP', 'Let games and calls open ports themselves', S.D.upnpEnabled === '1', 'upnp', 'dev') : pend('UPnP', 'Let games and calls open ports themselves', false)}${pend('DMZ', 'Expose one device fully. Not safe', S.D.DMZEnable === '1')}</div>
      <p class="mp-note">Rules need addresses and ports, so they’re set on the ${w}’s own page.</p><div class="mp-actions"><button class="btn ghost" data-act="raw">Open the ${w}’s page</button></div>`,
    adm: () => back('Password and SIM') + `<div class="list"><button class="li" data-act="raw"><div class="t"><b>Change the ${w} password</b><span>On its own page. The app asks for the new one next time</span></div>${chev}</button>
      ${pend('SIM PIN', 'Asks for the PIN after every restart', m && ['1', 'enable', 'enabled'].includes(String(S.D.pin_status).toLowerCase()))}${m ? pend('Fast boot', 'Starts quicker, uses a little power when off', S.D.mgmt_quicken_power_on === '1') : ''}</div>
      <div class="mp-actions"><button class="btn ghost" data-act="restart">Restart</button><button class="btn danger" data-act="reset"${m ? '' : ' disabled'}>Factory reset</button><button class="btn ghost" data-act="signout">Sign out on this phone</button></div>`,
    upd: () => back('Software update') + `<div class="list">${val('Version', m ? dv('wa_inner_version', esc(s.firmware || '—')) : esc(s.firmware || '—'), m ? dv('cr_version', '') : '')}${m ? val('Hardware', dv('hardware_version')) : ''}</div>
      <div class="mp-actions"><button class="btn" data-act="raw">Check on the ${w}’s page</button></div>`,
    bg: () => back('Checking in the background') + `<div class="sec first">Check every</div>${seg('Check every', 'checkMinutes', [[2, '2 min'], [5, '5 min'], [15, '15 min']], P.checkMinutes)}
      <p class="mp-note">Each check is one small request on the ${w}’s Wi-Fi: battery, signal, new messages and data used, then the phone sleeps again. A new text reaches you within that time. Expect about 1% of your phone’s battery a day at 5 min.</p>
      <div class="list" style="margin-top:var(--s-4)">${val('Only on its Wi-Fi', 'Always', 'Away from it, nothing is sent')}${tog('Check every minute when it matters', `While the ${w} battery is low or the internet is down`, P.fastWhenLow, 'fastWhenLow')}${tog('Slow down in battery saver', 'Every 15 min while your phone saves power', P.slowInSaver, 'slowInSaver')}
        ${actRow('exempt', 'Run in the background', P.batteryExempt ? 'Android lets the checks run on time' : 'Android may delay checks to save battery', P.batteryExempt ? 'Allowed' : 'Allow')}
        ${actRow('notif', 'Notifications', P.notifications ? 'Battery, alerts and new texts' : 'Off for this app in Android', P.notifications ? 'On' : 'Turn on')}</div>`,
    rec: () => {
      const I = S.info || {};
      return back('Usage record') + `<div class="list">${val('Recording since', I.since ? dateLong(I.since) : 'Not started')}${val('Size on this phone', `<span class="num">${fmt(I.bytes || 0)}</span>`, 'About 2 MB a year')}${val('Kept for', 'Until you clear it')}${tog('Record usage', 'Every hour’s data and every device that joins', P.recordUsage !== false, 'recordUsage')}</div>
        <p class="mp-note">Every hour’s data, every device that joined and how long it stayed, and the router’s temperature. Nothing leaves your phone unless you export it. Android’s own backup carries it to a new phone.</p>
        <div class="mp-actions"><button class="btn ghost" data-act="export">Export the record</button><button class="btn danger" data-act="clearrec">Clear the record</button></div>`;
    },
    app: () => {
      const u = S.upd || {}, L = u.latest || {};
      const checked = u.checkedAt ? seenAt(u.checkedAt) : 'Not yet';
      let action = '<button class="btn" data-act="appcheck">Check now</button>';
      if (L.appNewer) action = `<button class="btn" data-act="appinstall"${S.updBusy ? ' disabled' : ''}>${S.updBusy ? 'Updating…' : `Update to version ${esc(L.app.version)}`}</button>`;
      else if (L.webNewer) action = '<button class="btn" data-act="webupdate">Update the screens now</button>';
      return back('App updates') + `<div class="list">${val('This version', esc(S.app))}${val('Screens', `Version ${S.web}`, 'Updated on their own')}${val('Last checked', checked)}${tog('Look for updates', 'Checks GitHub once a day', P.autoUpdate, 'autoUpdate')}</div>
        <div class="mp-actions">${action}</div>
        <p class="mp-note">Screen changes arrive by themselves. When a new app version is out, the app and your status bar tell you. Tap Update, then Android asks you to confirm once.</p>
        ${u.repo ? `<button class="raw" data-act="github">See the project on GitHub</button>` : ''}`;
    },
  };
  return (pg[k] || pg.app)();
}

function setPage(k, keepScroll) {
  S.page = k;
  const el = $('[data-pset]'), scr = el.closest('.screen'), top = scr.scrollTop;
  el.innerHTML = k === 'index' ? setIndex() : setPageHtml(k);
  scr.scrollTop = keepScroll ? top : 0;
  if (!keepScroll) {
    if (k === 'index' || ['conn', 'net', 'wifi', 'sleep', 'limits', 'filter', 'fw', 'adm', 'upd'].includes(k)) loadDevice();
    if (k === 'rec') N.call('usage.info').then(i => { S.info = i; if (S.page === 'rec') setPage('rec', true); }).catch(() => {});
    if (k === 'app') N.call('update.info').then(u => { S.upd = u; if (S.page === 'app') setPage('app', true); }).catch(() => {});
    if (k === 'bg' || k === 'index') N.call('prefs.get').then(v => { S.prefs = v; }).catch(() => {});
    if (k === 'temp') loadTemps();
  }
}

async function loadTemps() {
  try {
    const rows = await N.call('usage.temps', { dev: viewId(), from: H(Date.now()) - 24, to: H(Date.now()) + 1 });
    if (!rows.length || S.page !== 'temp') return;
    const hi = rows.reduce((a, r) => r[2] > a[2] ? r : a), lo = rows.reduce((a, r) => r[1] < a[1] ? r : a);
    $('[data-temps]').innerHTML = val('Highest, last 24 h', `<span class="num">${Math.round(hi[2])} °C</span>`, whenFull(hi[0] * 3.6e6))
      + val('Lowest, last 24 h', `<span class="num">${Math.round(lo[1])} °C</span>`, whenFull(lo[0] * 3.6e6));
  } catch { /* nothing recorded */ }
}

async function setPref(k, v, el) {
  try {
    S.prefs = await N.call('prefs.set', { [k]: v });
    haptic('tick');
    if (k === 'smsNotify' && v && !S.prefs.notifications) N.call('notif.request');
    toast(`${el.getAttribute('aria-label')} ${v ? 'on' : 'off'}`);
  } catch (e) { el.setAttribute('aria-checked', String(!v)); fail(e); }
}

/** Write to the MiFi, then read the value back so the screen only says what the box really did. */
async function mifiWrite(key, v, label) {
  const [goformId, params, readKey, want] = MW[key](v);
  try {
    const r = await N.call('mifi.set', { goformId, params });
    const back = await N.call('mifi.get', { cmd: readKey });
    S.D[readKey] = back[readKey];
    if (!done(r) || String(back[readKey]) !== String(want)) throw new Error(`The MiFi didn’t take that. It still shows ${back[readKey] || 'the old value'}.`);
    haptic('confirm'); toast(label);
  } catch (e) { fail(e); }
  setPage(S.page, true);
}

async function boxAction(kind) {
  const w = what(), router = isRouter();
  try {
    if (kind === 'restart') {
      const r = router ? await N.call('router.call', { body: { cmd: 6, method: 'POST', rebootType: 1 } }) : await N.call('mifi.set', { goformId: 'REBOOT_DEVICE' });
      if (!router && !done(r)) throw new Error(`The ${w} didn’t restart.`);
      toast(`Restarting the ${w}. Back in about a minute.`, 'confirm');
    } else if (kind === 'shutdown') {
      const r = await N.call('mifi.set', { goformId: 'TURN_OFF_DEVICE' });
      if (!done(r)) throw new Error('The MiFi didn’t switch off.');
      toast('Switching the MiFi off. Hold its power button to start it again.', 'confirm');
    } else if (kind === 'reset') {
      const r = await N.call('mifi.set', { goformId: 'RESTORE_FACTORY_SETTINGS' });
      if (!done(r)) throw new Error('The MiFi didn’t reset.');
      toast('Resetting. The MiFi restarts with its factory Wi-Fi name and password.', 'confirm');
    } else if (kind === 'disconnect' || kind === 'connect') {
      const r = await N.call('mifi.set', { goformId: kind === 'connect' ? 'CONNECT_NETWORK' : 'DISCONNECT_NETWORK', params: { notCallback: 'true' } });
      if (!done(r)) throw new Error(`The MiFi didn’t ${kind}.`);
      toast(kind === 'connect' ? 'Connecting…' : 'Disconnected. The internet stays off until you connect.', 'confirm');
      setTimeout(() => N.call('refresh', { clients: false }).then(onState).catch(() => {}), 3000);
    }
  } catch (e) { fail(e); }
}

// ---------- clicks ----------
document.addEventListener('click', e => {
  const t = e.target;
  if (t.closest('[data-scrim]')) return closeSheet();
  const go = t.closest('[data-go]'); if (go) return nav(go.dataset.go);
  const ps = t.closest('[data-ps]'); if (ps) return setPage(ps.dataset.ps);
  const mg = t.closest('[data-msg]'); if (mg) return openMsg(mg.dataset.msg);
  const ug = t.closest('[data-ussdgo]'); if (ug) return runUssd(ug, ug.dataset.ussdgo);
  const pk = t.closest('[data-pick]');
  if (pk) {
    const id = pk.dataset.pick;
    S.sel.has(id) ? S.sel.delete(id) : S.sel.add(id);
    haptic('tick'); return renderMsgs();
  }
  // Switches first: they carry data-rec / data-dev too, which would otherwise open a record or switch box.
  const sw = t.closest('.sw');
  if (sw) {
    if (sw.dataset.pending) return toast(PENDING);
    const v = sw.getAttribute('aria-checked') !== 'true';
    sw.setAttribute('aria-checked', v);
    if (sw.dataset.pref) return setPref(sw.dataset.pref, v, sw);
    if (sw.dataset.dev) return mifiWrite(sw.dataset.dev, v, `${sw.getAttribute('aria-label')} ${v ? 'on' : 'off'}`);
    if (sw.dataset.rec) {
      return N.call('usage.setMine', { dev: viewId(), mac: S.rec, mine: v })
        .then(() => { haptic('tick'); toast(v ? 'Moved to Yours' : 'Moved to Others'); loadUsage(); })
        .catch(e => { sw.setAttribute('aria-checked', !v); fail(e); });
    }
    return haptic('tick');
  }
  const rc = t.closest('[data-rec]'); if (rc) return recSheet(rc.dataset.rec);
  const dvb = t.closest('[data-dev]');
  if (dvb) {
    const id = dvb.dataset.dev;
    N.call('device.select', { id }).then(v => { onState(v); closeSheet(); toast(`Showing ${devName(id)}`); if (S.tab === 'usage') loadUsage(); }).catch(fail);
    return;
  }
  const up = t.closest('[data-u]');
  if (up) { S.per = up.dataset.u; S.ui.per = S.per; saveUi(); haptic('tick'); return loadUsage(true); }
  const sg = t.closest('[data-seg]');
  if (sg) {
    const k = sg.dataset.seg, v = sg.dataset.v;
    sg.parentNode.querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', b === sg));
    if (k === 'checkMinutes') return setPref(k, +v, { getAttribute: () => `Checks every ${v} min,` });
    if (MW[k]) return mifiWrite(k, v, `${sg.parentNode.getAttribute('aria-label')}: ${sg.textContent}`);
    return;
  }
  const a = t.closest('[data-act]'); if (!a || a.disabled) return;
  act(a.dataset.act, a);
});

// A long press on a text starts picking, with that text picked.
document.addEventListener('contextmenu', e => {
  const mg = e.target.closest('[data-msg]'); if (!mg) return;
  e.preventDefault();
  S.sel = new Set([mg.dataset.msg]); haptic('tick'); renderMsgs();
});

document.addEventListener('submit', e => {
  const f = e.target.closest('[data-signin]'); if (!f) return;
  e.preventDefault(); signIn(f);
});

function act(k, el) {
  const w = what();
  switch (k) {
    case 'devices': return deviceSheet();
    case 'wifi': return N.call('wifi.settings');
    case 'find': S.nowKey = ''; return N.call('find').then(onState).catch(fail);
    case 'peek': { const i = $('#si-pw'); const show = i.type === 'password'; i.type = show ? 'text' : 'password'; el.textContent = show ? 'Hide' : 'Show'; el.setAttribute('aria-label', show ? 'Hide the password' : 'Show the password'); return; }
    case 'yes': { const f = S.confirm; closeSheet(); return f && f(); }
    case 'no': return closeSheet();
    case 'raw': return N.call('openOriginal');
    case 'github': return S.upd?.repo && N.call('openUrl', { url: `https://github.com/${S.upd.repo}` });
    case 'msgback': return openMsg(null);
    case 'selstart': S.sel = new Set(); return renderMsgs();
    case 'selend': return selEnd();
    case 'selall': S.sel = S.sel.size === S.msgs.length ? new Set() : new Set(S.msgs.map(x => x.id)); return renderMsgs();
    case 'readall': return selDo('read', S.msgs.filter(x => x.unread && !x.outgoing).map(x => x.id));
    case 'selread': return selDo('read');
    case 'selunread': return selDo('unread');
    case 'seldel': {
      const n = S.sel.size;
      return ask({
        title: n === 1 ? 'Delete this text?' : `Delete ${n} texts?`, text: `${n === 1 ? 'It’s' : 'They’re'} removed from the ${w}. This can’t be undone.`, yes: 'Delete',
        then: () => selDo('delete'),
      });
    }
    case 'unreadmsg': { const id = S.msg; S.msg = null; return selDo('unread', [id]); }
    case 'ussd': return runUssd(el);
    case 'balcode': {
      openSheet(`<h3 class="scr">Balance code</h3><div class="field"><label for="bc">Code</label><input id="bc" name="bc" inputmode="tel" autocomplete="off" spellcheck="false" placeholder="*310#" value="${esc(balanceCode())}"></div>
        <button class="btn wide" data-act="savebal">Save the code</button><p class="mp-note">Check balance sends this code. Your network’s website or SIM pack lists it.</p>`);
      return setTimeout(() => $('#bc')?.focus(), 300);
    }
    case 'savebal': {
      const v = $('#bc').value.trim();
      if (!/^[*#0-9]+$/.test(v)) return toast('A code is made of digits, * and #.');
      S.ui.ussd = v; saveUi(); closeSheet(); renderUssd(); return toast('Saved', 'confirm');
    }
    case 'newsms': return composeSheet('');
    case 'reply': { const m = S.msgs?.find(x => x.id === S.msg); return composeSheet(m?.number || ''); }
    case 'sendsms': {
      const to = $('#sm-to').value.trim(), text = $('#sm-text').value.trim();
      if (!to || !text) return toast('Add a number and a message.');
      el.classList.add('busy');
      return N.call('sms.send', { number: to, text }).then(ok => {
        el.classList.remove('busy');
        if (!ok) throw new Error(`The ${w} didn’t send it.`);
        closeSheet(); toast(`Sent from the ${w}.`, 'confirm'); loadMsgs();
      }).catch(e => { el.classList.remove('busy'); fail(e); });
    }
    case 'delmsg': return ask({
      title: 'Delete this message?', text: `It’s removed from the ${w}. This can’t be undone.`, yes: 'Delete',
      then: () => N.call('sms.delete', { ids: [S.msg] }).then(() => { S.msgs = S.msgs.filter(x => x.id !== S.msg); S.msg = null; renderMsgs(); badge(); toast(`Deleted from the ${w}`, 'confirm'); }).catch(fail),
    });
    case 'renamedev': return renameSheet(`Rename ${esc(devName())}`, devName(), 'savedevname');
    case 'savedevname': {
      const v = $('#rn').value.trim(); if (!v) return;
      return N.call('device.rename', { id: viewId(), name: v }).then(s => { onState(s); closeSheet(); toast('Renamed', 'confirm'); }).catch(fail);
    }
    case 'renamerec': {
      const d = S.usage?.devs.find(x => x.mac === S.rec);
      return renameSheet('Rename this device', d?.alias || d?.name || '', 'saverecname');
    }
    case 'saverecname': return N.call('usage.rename', { dev: viewId(), mac: S.rec, name: $('#rn').value.trim() })
      .then(() => { closeSheet(); toast('Renamed', 'confirm'); loadUsage(); }).catch(fail);
    case 'blockrec': return blockAsk(true);
    case 'unblockrec': return blockAsk(false);
    case 'export': return N.call('usage.export').catch(fail);
    case 'clearrec': return ask({
      title: 'Clear the usage record?', text: 'Every hour’s data and every device the app has recorded, for all your devices, is erased from this phone.', yes: 'Clear everything',
      then: () => setTimeout(() => ask({
        title: 'This can’t be undone', text: 'Export it first if you might want it later.', yes: 'Yes, clear it',
        then: () => N.call('usage.clear').then(() => { toast('The record is cleared.', 'confirm'); S.info = null; setPage('rec'); }).catch(fail),
      }), 350),
    });
    case 'restart': return ask({ title: `Restart the ${w}?`, text: 'The Wi-Fi and internet stop for about a minute, then come back on their own.', yes: 'Restart', danger: false, then: () => boxAction('restart') });
    case 'shutdown': return ask({ title: 'Shut down the MiFi?', text: 'Everyone on its Wi-Fi goes offline. Hold its power button to start it again.', yes: 'Shut down', then: () => boxAction('shutdown') });
    case 'reset': return ask({
      title: 'Factory reset the MiFi?', text: 'Every setting goes back to how it came, including the Wi-Fi name and password on its label.', yes: 'Reset',
      then: () => setTimeout(() => ask({ title: 'Erase every setting?', text: 'This can’t be undone. Phones on its Wi-Fi need the label’s password to join again.', yes: 'Yes, reset it', then: () => boxAction('reset') }), 350),
    });
    case 'disconnect': return ask({ title: 'Disconnect from the internet?', text: 'The Wi-Fi stays on, but the internet stops until you connect again.', yes: 'Disconnect', then: () => boxAction('disconnect') });
    case 'connect': return boxAction('connect');
    case 'signout': return ask({
      title: `Sign out of the ${w}?`, text: 'The saved password is removed from this phone. You type it again to sign back in.', yes: 'Sign out',
      then: () => N.call('signOut').then(s => { onState(s); S.nowKey = ''; nav('now'); }).catch(fail),
    });
    case 'showpw': {
      const v = el.closest('.li').querySelector('[data-pw]'), hidden = v.textContent.startsWith('•');
      v.textContent = hidden ? (b64dec(S.D.WPAPSK1_encode || '') || 'Not shown by this MiFi') : '••••••••';
      el.textContent = hidden ? 'Hide' : 'Show'; return;
    }
    case 'exempt': return N.call('battery.exempt');
    case 'notif': return N.call('notif.request');
    case 'appcheck':
      el.classList.add('busy'); el.textContent = 'Checking…';
      return N.call('update.check').then(() => N.call('update.info')).then(u => {
        S.upd = u; setPage('app', true);
        const L = u.latest || {};
        toast(L.appNewer ? `Version ${L.app.version} is ready` : L.webNewer ? 'New screens are ready' : 'You have the latest version');
      }).catch(e => { setPage('app', true); fail(e); });
    case 'webupdate':
      el.classList.add('busy');
      return N.call('update.web').then(v => { if (v) { toast('Screens updated'); setTimeout(() => location.reload(), 600); } else { el.classList.remove('busy'); toast('Nothing new to install'); } })
        .catch(e => { el.classList.remove('busy'); fail(e); });
    case 'appinstall': return installApp();
    case 'updlater':
      S.ui.updLater = { v: S.upd?.latest?.app?.version, at: Date.now() }; saveUi(); return paintUpd();
  }
}

// ---------- app updates ----------
// A new app version shows as a bar on Now (and in the status bar, from the phone). One tap installs it.
const updReady = () => { const L = S.upd?.latest; return L?.appNewer ? L.app : null; };

function paintUpd() {
  const bar = $('[data-updbar]'), a = updReady(), later = S.ui?.updLater;
  const snoozed = a && later && later.v === a.version && Date.now() - later.at < 24 * 3.6e6;
  bar.hidden = !a || (snoozed && !S.updBusy);
  if (bar.hidden) return;
  const pct = S.updPct;
  bar.innerHTML = `<span class="t"><b>Version ${esc(a.version)}</b><span class="num">${S.updBusy ? (pct != null ? `Downloading, ${pct}%` : 'Downloading…') : 'Ready to install'}</span></span>
    ${S.updBusy ? '' : '<button class="btn ghost sm" data-act="updlater">Later</button>'}<button class="btn sm" data-act="appinstall"${S.updBusy ? ' disabled' : ''}>${S.updBusy ? 'Updating…' : 'Update'}</button>`;
}

async function loadUpd(fresh) {
  try {
    S.upd = await N.call('update.info');
    const stale = Date.now() - (S.upd.checkedAt || 0) > 6 * 3.6e6;
    // A restarted app has nothing in hand yet, so it looks again on opening.
    if (fresh || ((stale || !S.upd.latest) && S.prefs?.autoUpdate !== false)) { await N.call('update.check'); S.upd = await N.call('update.info'); }
  } catch {}
  paintUpd();
  if (S.tab === 'settings' && S.page === 'app') setPage('app', true);
}

async function installApp() {
  if (S.updBusy) return;
  if (!updReady()) { await loadUpd(true); if (!updReady()) return toast('You have the latest version'); }
  S.updBusy = true; S.updPct = null; paintUpd(); if (S.page === 'app') setPage('app', true);
  try {
    const r = await N.call('update.install');
    if (r.needsPermission) return ask({
      title: 'Allow updates from this app', text: 'Android asks once before an app outside the Play Store can install its own updates. Turn on “Allow from this source”, then come back. The update carries on by itself.',
      yes: 'Open settings', danger: false, then: () => { S.updAfterAllow = true; return N.call('update.allow'); },
    });
    toast('Android asks you to tap Update once.');
  } catch (e) { fail(e); }
  finally { S.updBusy = false; S.updPct = null; paintUpd(); if (S.page === 'app') setPage('app', true); }
}

// ---------- events from the phone ----------
function onState(s) {
  if (!s) return;
  const prevId = S.st?.id, prevNeeds = S.st?.needsSignIn;
  S.st = s;
  // When the phone reaches a box, show that one.
  if (s.id && s.id !== prevId && s.selected !== s.id) { N.call('device.select', { id: s.id }).then(onState).catch(() => {}); }
  // A new MiFi is first named after its Wi-Fi, which is what the owner calls it.
  const d = devOf(s.id), ssid = s.status?.ssid;
  if (s.id && ssid && s.status?.kind === 'mifi' && d.name === 'MiFi' && !S.ui['named:' + s.id] && s.kind === 'mifi') {
    S.ui['named:' + s.id] = 1; saveUi();
    N.call('device.rename', { id: s.id, name: ssid }).then(onState).catch(() => {});
  }
  if (s.id !== prevId || s.needsSignIn !== prevNeeds) { S.Dfor = null; S.todayAt = 0; }
  // The tabs come only after sign-in; until then the Now screen (and its sign-in form) is the app.
  const out = !(s.signedIn && !s.needsSignIn);
  app.classList.toggle('out', out);
  $('[data-tabs]').inert = out || sheetOpen();
  if (out && S.tab !== 'now') nav('now');
  if (!out && S.pendingRoute) { const r = S.pendingRoute; S.pendingRoute = null; route(r); }
  // The status-bar reading and text alerts need Android's notification permission: ask once, after the first sign-in.
  if (!out && S.prefs && !S.prefs.notifications && !S.ui.askedNotif) { S.ui.askedNotif = 1; saveUi(); N.call('notif.request').catch(() => {}); }
  paintNow(); badge(); wantLive(); loadToday();
  if (S.tab === 'settings' && S.page === 'index') setPage('index', true);
  if (S.tab === 'ussd' && S.ussdHere !== viewIsHere()) { S.ussdHere = viewIsHere(); renderUssd(); }
  if (!prevNeeds && s.needsSignIn && S.tab === 'settings') setPage('index', true);
}

N.on('state', onState);
N.on('live', d => {
  S.live = d;
  S.hist.push(d.connected === false ? [0, 0] : [mbps(d.rxRate), mbps(d.txRate)]); S.hist.shift();
  drawTrace(); paintNow();
});
N.on('sms', () => { loadMsgs(); });
// A new reading went into the record: today's figure and the open Usage tab follow it.
N.on('usage', d => {
  if (d.dev !== viewId()) return;
  S.todayAt = 0; loadToday();
  if (S.tab === 'usage' && !sheetOpen()) loadUsage();
});
N.on('route', d => route(d.route));
N.on('insets', setInsets);
N.on('visible', d => { S.visible = !!d.on; S.liveOn = !S.visible && S.liveOn; wantLive(); if (d.on) {
  loadToday(); if (S.tab === 'msgs') loadMsgs();
  // Back from Android's "Allow from this source": carry on with the update the owner asked for.
  if (S.updAfterAllow) { S.updAfterAllow = false; N.call('update.canInstall').then(ok => { if (ok) installApp(); }).catch(() => {}); }
} });
N.on('export', d => toast(d.ok ? 'Saved the record as a spreadsheet file.' : 'Not saved.', d.ok ? 'confirm' : undefined));
N.on('permission', () => N.call('prefs.get').then(v => { S.prefs = v; if (S.tab === 'settings') setPage(S.page, true); }).catch(() => {}));
N.on('update', d => {
  if (d.state === 'ready') { if (updReady()?.version !== d.version) loadUpd(); }
  else if (d.state === 'downloading' && d.total) { S.updPct = Math.round(d.done / d.total * 100); paintUpd(); }
  else if (d.state === 'failed') toast(d.message || 'The update didn’t install.', 'reject');
  else if (d.state === 'installed') toast('Updated.', 'confirm');
});
N.on('back', () => {
  if (sheetOpen()) return closeSheet();
  if (S.tab === 'msgs' && S.sel) return selEnd();
  if (S.tab === 'msgs' && S.msg) return openMsg(null);
  if (S.tab === 'settings' && S.page !== 'index') return setPage('index');
  if (S.tab !== 'now') return nav('now');
  N.call('exit');
});

function setInsets(d) {
  if (!d) return;
  app.style.setProperty('--sb', `${d.bottom || 0}px`);
  if (d.top != null) app.style.setProperty('--st', `${d.top}px`);
  app.style.setProperty('--ime', `${d.ime || 0}px`);
  app.classList.toggle('kb', (d.ime || 0) > 0);
}

function route(r) {
  if (!r) return;
  // Updates don't wait for the box: they come from GitHub.
  if (r === 'update' || r === 'update:go') { nav('now'); return r === 'update:go' ? installApp() : loadUpd(); }
  if (app.classList.contains('out') || !S.st) { S.pendingRoute = r; return; }
  if (r.startsWith('msg:')) { nav('msgs'); openMsg(r.slice(4)); }
  else if (r.startsWith('settings:')) { nav('settings'); setPage(r.slice(9)); }
}

function saveUi() { N.call('ui.set', S.ui).catch(() => {}); }

// Uptime and "dropped … ago" keep counting between readings.
setInterval(() => {
  if (!S.visible) return;
  const s = status();
  if (s.sessionSec != null && S.st?.at) s._base ??= s.sessionSec;
  if (S.tab === 'now') paintNow();
}, 15000);

// ---------- start ----------
(async () => {
  drawTrace();
  try {
    const h = await N.call('hello');
    S.app = h.app; S.web = h.web; S.prefs = h.prefs || {}; S.ui = h.ui || {};
    S.per = S.ui.per || 'month';
    setInsets(h.insets);
    onState(h.state);
    if (h.route) route(h.route);
  } catch (e) { console.error(e); }
  loadUpd();
  N.call('refresh', { clients: true }).then(onState).catch(() => {});
  if (status().smsUnread || viewIsHere()) loadMsgs();
})();
})();

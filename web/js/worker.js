// Background parsing of large files (M3U lists and XMLTV guides), so the page
// stays responsive. Streams the download and keeps only what is needed.

self.onmessage = async (e) => {
  const msg = e.data;
  try {
    if (msg.cmd === 'm3u') self.postMessage({ result: await parseM3u(msg.url) });
    else if (msg.cmd === 'xmltv') self.postMessage({ result: await parseXmltv(msg.url) });
  } catch (err) {
    self.postMessage({ error: err && err.message ? err.message : String(err) });
  }
};

const progress = (p) => self.postMessage({ progress: p });

/** Text stream of a download, un-gzipping it when needed (guides are often .gz). */
async function textStream(url) {
  const r = await fetch(url, { credentials: 'same-origin' });
  if (r.status === 401) throw new Error('Tu sesión ha caducado. Vuelve a entrar.');
  if (!r.ok) throw new Error('El servidor respondió ' + r.status);
  const reader = r.body.getReader();
  const first = await reader.read();
  const head = first.value || new Uint8Array();
  const gz = head[0] === 0x1f && head[1] === 0x8b;
  const bytes = new ReadableStream({
    start(c) {
      if (head.length) c.enqueue(head);
      if (first.done) c.close();
    },
    async pull(c) {
      const { value, done } = await reader.read();
      if (done) c.close();
      else c.enqueue(value);
    },
  });
  const plain = gz ? bytes.pipeThrough(new DecompressionStream('gzip')) : bytes;
  return plain.pipeThrough(new TextDecoderStream()).getReader();
}

// ------------------------------------------------------------------ M3U

async function parseM3u(url) {
  const reader = await textStream(url);
  const cats = { live: new Map(), vod: new Map() };
  const live = [], vod = [];
  let epg = null;
  let buf = '';
  let pending = null;
  let count = 0;
  const attr = (s, name) => {
    const m = new RegExp(name + '="([^"]*)"', 'i').exec(s);
    return m ? m[1].trim() : '';
  };
  const handle = (line) => {
    const l = line.trim();
    if (!l) return;
    if (l.startsWith('#EXTM3U')) {
      epg = attr(l, 'url-tvg') || attr(l, 'x-tvg-url') || null;
      if (epg && epg.includes(',')) epg = epg.split(',')[0].trim();
      return;
    }
    if (l.startsWith('#EXTINF')) {
      const comma = l.indexOf(',', l.lastIndexOf('"') > 0 ? l.lastIndexOf('"') : 0);
      pending = {
        name: (comma >= 0 ? l.slice(comma + 1) : '').trim() || attr(l, 'tvg-name'),
        logo: attr(l, 'tvg-logo') || null,
        group: attr(l, 'group-title') || 'Sin categoría',
        epg: attr(l, 'tvg-id') || null,
        num: parseInt(attr(l, 'tvg-chno'), 10) || 0,
      };
      return;
    }
    if (l.startsWith('#')) return;
    if (!pending) return;
    const p = pending;
    pending = null;
    const isVod = /\/(movie|series)\//i.test(l) || /\.(mp4|mkv|avi|mov|m4v)(\?|$)/i.test(l);
    const kind = isVod ? 'vod' : 'live';
    const map = cats[kind];
    if (!map.has(p.group)) map.set(p.group, String(map.size + 1));
    const cat = map.get(p.group);
    count++;
    if (isVod) {
      const y = /[([ ]((19|20)\d{2})[)\] ]?/.exec(p.name);
      vod.push({ id: 'v' + count, name: p.name, poster: p.logo, cat, rating: 0, year: y ? y[1] : null, added: count, url: l });
    } else {
      live.push({ id: 'c' + count, name: p.name, logo: p.logo, cat, epg: p.epg, num: p.num || live.length + 1, url: l });
    }
    if (count % 5000 === 0) progress(`${count.toLocaleString('es-ES')} elementos…`);
  };
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += value;
    const lines = buf.split(/\r?\n/);
    buf = lines.pop();
    for (const line of lines) handle(line);
  }
  if (buf) handle(buf);
  const toList = (m) => [...m.entries()].map(([name, id]) => ({ id, name }));
  return { cats: { live: toList(cats.live), vod: toList(cats.vod), series: [] }, live, vod, epg };
}

// ------------------------------------------------------------------ XMLTV

function parseTime(s) {
  // "20261015213000 +0200"
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?/.exec(s || '');
  if (!m) return 0;
  let t = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  if (m[7]) {
    const sign = m[7][0] === '-' ? -1 : 1;
    const off = (parseInt(m[7].slice(1, 3), 10) * 60 + parseInt(m[7].slice(3, 5), 10)) * 60000;
    t -= sign * off;
  }
  return t;
}

const ent = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'" };
function decode(s) {
  return s
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&(amp|lt|gt|quot|apos);/g, (m) => ent[m])
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)));
}

async function parseXmltv(url) {
  const reader = await textStream(url);
  const now = Date.now();
  const from = now - 3 * 3600000, to = now + 36 * 3600000;
  const map = new Map();
  const re = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g;
  let buf = '';
  let n = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (value) buf += value;
    let m, last = 0;
    re.lastIndex = 0;
    while ((m = re.exec(buf))) {
      last = re.lastIndex;
      const a = m[1];
      const ch = /channel="([^"]*)"/.exec(a);
      if (!ch) continue;
      const s = parseTime((/start="([^"]*)"/.exec(a) || [])[1]);
      const e = parseTime((/stop="([^"]*)"/.exec(a) || [])[1]);
      if (!s || !e || e < from || s > to) continue;
      const t = /<title[^>]*>([\s\S]*?)<\/title>/.exec(m[2]);
      const d = /<desc[^>]*>([\s\S]*?)<\/desc>/.exec(m[2]);
      const id = decode(ch[1]);
      if (!map.has(id)) map.set(id, []);
      map.get(id).push({ s, e, t: t ? decode(t[1]).trim() : '', d: d ? decode(d[1]).trim().slice(0, 400) : '' });
      if (++n % 20000 === 0) progress(`Guía: ${n.toLocaleString('es-ES')} programas…`);
    }
    buf = buf.slice(last);
    if (buf.length > 4_000_000) buf = buf.slice(-200_000);
    if (done) break;
  }
  const out = [];
  for (const [id, list] of map) {
    list.sort((x, y) => x.s - y.s);
    out.push([id, list]);
  }
  return { map: out };
}

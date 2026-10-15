// Data: access to the site, the provider (Xtream or M3U through the PHP proxy),
// the guide, and what the user keeps (favourites, recents, progress, captures).
import { b64url, cleanTitle, yearIn, searchName, sleep } from './util.js';

// ------------------------------------------------------------------ storage (IndexedDB)

let dbp;
function db() {
  if (!dbp) {
    dbp = new Promise((resolve, reject) => {
      const r = indexedDB.open('nansutv', 1);
      r.onupgradeneeded = () => r.result.createObjectStore('kv');
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  }
  return dbp;
}
export async function kvGet(key) {
  const d = await db();
  return new Promise((res, rej) => {
    const q = d.transaction('kv').objectStore('kv').get(key);
    q.onsuccess = () => res(q.result);
    q.onerror = () => rej(q.error);
  });
}
export async function kvSet(key, val) {
  const d = await db();
  return new Promise((res, rej) => {
    const t = d.transaction('kv', 'readwrite');
    t.objectStore('kv').put(val, key);
    t.oncomplete = () => res();
    t.onerror = () => rej(t.error);
  });
}
export async function kvDel(key) {
  const d = await db();
  return new Promise((res) => {
    const t = d.transaction('kv', 'readwrite');
    t.objectStore('kv').delete(key);
    t.oncomplete = () => res();
  });
}

// ------------------------------------------------------------------ preferences (small, localStorage)

const PREF_DEFAULTS = {
  watermark: true,
  livePreview: true,
  reduceMotion: false,
  subtitleSize: 0,
  subtitleBackground: false,
  liveFormat: 'm3u8',
  direct: false,
  volume: 1,
  muted: false,
};
export const prefs = (() => {
  let saved = {};
  try { saved = JSON.parse(localStorage.getItem('nt:prefs') || '{}'); } catch { /* empty */ }
  const p = { ...PREF_DEFAULTS, ...saved };
  return new Proxy(p, {
    set(t, k, v) {
      t[k] = v;
      try { localStorage.setItem('nt:prefs', JSON.stringify(t)); } catch { /* empty */ }
      return true;
    },
  });
})();

// ------------------------------------------------------------------ site access

export async function authStatus() {
  const r = await fetch('api/auth.php?a=status', { credentials: 'same-origin', cache: 'no-store' });
  if (!r.ok) throw new Error('No se pudo contactar con el servidor (' + r.status + ')');
  return r.json();
}
export async function authPost(action, password) {
  const r = await fetch('api/auth.php?a=' + action, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || 'error');
  return j;
}

/** URL that the browser can load: through the PHP proxy (or as is in direct mode). */
export function viaProxy(url) {
  if (!url) return url;
  if (prefs.direct || url.startsWith('data:') || url.startsWith('blob:')) return url;
  return 'api/proxy.php?u=' + b64url(url);
}

/** Pictures: https ones load directly (faster, no server traffic); http ones go through the proxy. */
export function imgUrl(url) {
  if (!url) return null;
  if (url.startsWith('https://') || url.startsWith('data:')) return url;
  if (url.startsWith('http://')) return location.protocol === 'https:' ? viaProxy(url) : url;
  return null;
}

async function getJson(url) {
  const r = await fetch(viaProxy(url), { credentials: 'same-origin' });
  if (r.status === 401) throw new Error('Tu sesión ha caducado. Vuelve a entrar.');
  if (!r.ok) throw new Error('El servidor respondió ' + r.status);
  const text = await r.text();
  try {
    return JSON.parse(text);
  } catch {
    throw new Error('Respuesta no válida del proveedor');
  }
}

// ------------------------------------------------------------------ state

export const S = {
  playlist: null, // {type:'xtream', name, server, user, pass, epg} | {type:'m3u', name, url, epg}
  cats: { live: [], vod: [], series: [] },
  live: [],
  vod: [],
  series: [],
  lastSync: 0,
  epg: new Map(), // epg id → [{s, e, t, d}]
  epgAt: 0,
  favorites: [], // {kind, id, title, image, at}
  recents: [], // {id, at}
  progress: {}, // uid → {kind, id, title, subtitle, image, pos, dur, at, seriesId}
  version: 0,
};
const listeners = new Set();
export function onChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
/** 'catalog' when lists or guide change (clears computed rows); 'user' for favourites, recents, progress. */
function changed(scope = 'user') {
  S.version++;
  if (scope === 'catalog') memo.clear();
  for (const fn of listeners) fn(scope);
}

const memo = new Map();
/** Remembers a computed value until the catalogue, guide or user data change. */
export function cached(key, fn) {
  if (memo.has(key)) return memo.get(key);
  const v = fn();
  memo.set(key, v);
  return v;
}

export async function loadAll() {
  const [pl, cats, live, vod, series, last, fav, rec, prog] = await Promise.all([
    kvGet('playlist'), kvGet('cats'), kvGet('live'), kvGet('vod'), kvGet('series'), kvGet('lastSync'),
    kvGet('favorites'), kvGet('recents'), kvGet('progress'),
  ]);
  S.playlist = pl || null;
  S.cats = cats || { live: [], vod: [], series: [] };
  S.live = live || [];
  S.vod = vod || [];
  S.series = series || [];
  S.lastSync = last || 0;
  S.favorites = fav || [];
  S.recents = rec || [];
  S.progress = prog || {};
  const epg = await kvGet('epg');
  if (epg) {
    S.epg = new Map(epg.map);
    S.epgAt = epg.at;
  }
  const bd = (await kvGet('backdrops')) || {};
  for (const m of S.vod) if (bd[m.id]) m.backdrop = bd[m.id];
  changed('catalog');
}

export async function savePlaylist(pl) {
  S.playlist = pl;
  await kvSet('playlist', pl);
}

export async function forgetPlaylist() {
  for (const k of ['playlist', 'cats', 'live', 'vod', 'series', 'lastSync', 'epg']) await kvDel(k);
  S.playlist = null;
  S.live = S.vod = S.series = [];
  S.cats = { live: [], vod: [], series: [] };
  S.epg = new Map();
  changed('catalog');
}

// ------------------------------------------------------------------ Xtream

function xbase(pl = S.playlist) {
  let s = (pl.server || '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  return s;
}
function xapi(action, extra = '', pl = S.playlist) {
  return `${xbase(pl)}/player_api.php?username=${encodeURIComponent(pl.user)}&password=${encodeURIComponent(pl.pass)}${action ? '&action=' + action : ''}${extra}`;
}

export async function testXtream(pl) {
  const j = await getJson(xapi('', '', pl));
  if (!j || !j.user_info) throw new Error('El servidor no reconoce esta cuenta');
  if (j.user_info.auth === 0 || j.user_info.status === 'Disabled') throw new Error('Usuario o contraseña incorrectos');
  return j;
}

const num = (v) => {
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : 0;
};
const str = (v) => (v == null ? '' : String(v));

function normCats(list) {
  return (Array.isArray(list) ? list : []).map((c) => ({ id: str(c.category_id), name: str(c.category_name) }));
}

export async function sync(progress = () => {}) {
  const pl = S.playlist;
  if (!pl) throw new Error('No hay lista');
  if (pl.type === 'xtream') {
    progress('Categorías…');
    const [lc, vc, sc] = await Promise.all([
      getJson(xapi('get_live_categories')),
      getJson(xapi('get_vod_categories')),
      getJson(xapi('get_series_categories')),
    ]);
    progress('Canales…');
    const live = await getJson(xapi('get_live_streams'));
    progress('Películas…');
    const vod = await getJson(xapi('get_vod_streams'));
    progress('Series…');
    const series = await getJson(xapi('get_series'));
    S.cats = { live: normCats(lc), vod: normCats(vc), series: normCats(sc) };
    S.live = (Array.isArray(live) ? live : []).map((m, i) => ({
      id: str(m.stream_id),
      name: str(m.name).trim(),
      logo: m.stream_icon || null,
      cat: str(m.category_id),
      epg: m.epg_channel_id || null,
      num: parseInt(m.num, 10) || i + 1,
    }));
    S.vod = (Array.isArray(vod) ? vod : []).map((m) => ({
      id: str(m.stream_id),
      name: str(m.name).trim(),
      poster: m.stream_icon || null,
      cat: str(m.category_id),
      rating: num(m.rating) || num(m.rating_5based) * 2,
      year: (str(m.year).slice(0, 4) || yearIn(m.name)) || null,
      added: parseInt(m.added, 10) || 0,
      ext: m.container_extension || 'mp4',
    }));
    S.series = (Array.isArray(series) ? series : []).map((m) => ({
      id: str(m.series_id),
      name: str(m.name).trim(),
      poster: m.cover || null,
      backdrop: Array.isArray(m.backdrop_path) ? m.backdrop_path[0] || null : m.backdrop_path || null,
      cat: str(m.category_id),
      rating: num(m.rating) || num(m.rating_5based) * 2,
      year: str(m.releaseDate || m.release_date || m.year).slice(0, 4) || yearIn(m.name),
      added: parseInt(m.last_modified, 10) || 0,
      genre: m.genre || null,
      plot: m.plot || null,
    }));
  } else {
    progress('Descargando la lista…');
    const res = await runWorker({ cmd: 'm3u', url: absolute(viaProxy(pl.url)) }, progress);
    S.cats = res.cats;
    S.live = res.live;
    S.vod = res.vod;
    S.series = [];
    if (!pl.epg && res.epg) pl.epg = res.epg;
  }
  S.lastSync = Date.now();
  const bd = (await kvGet('backdrops')) || {};
  for (const m of S.vod) if (bd[m.id]) m.backdrop = bd[m.id];
  progress('Guardando…');
  await Promise.all([
    kvSet('playlist', pl), kvSet('cats', S.cats), kvSet('live', S.live), kvSet('vod', S.vod),
    kvSet('series', S.series), kvSet('lastSync', S.lastSync),
  ]);
  changed('catalog');
}

export function epgUrl() {
  const pl = S.playlist;
  if (!pl) return null;
  if (pl.epg) return pl.epg;
  if (pl.type === 'xtream') return `${xbase()}/xmltv.php?username=${encodeURIComponent(pl.user)}&password=${encodeURIComponent(pl.pass)}`;
  return null;
}

export async function syncEpg(progress = () => {}) {
  const url = epgUrl();
  if (!url) return false;
  // Only the channels of this list, and only the next hours: keeps memory small.
  const ids = [...new Set(S.live.map((c) => c.epg).filter(Boolean))];
  const res = await runWorker({ cmd: 'xmltv', url: absolute(viaProxy(url)), ids }, progress);
  S.epg = new Map(res.map);
  S.epgAt = Date.now();
  await kvSet('epg', { at: S.epgAt, map: res.map });
  changed('catalog');
  return true;
}

/** Workers resolve relative URLs against their own script, so pass full ones. */
const absolute = (u) => new URL(u, location.href).href;

function runWorker(msg, progress) {
  return new Promise((resolve, reject) => {
    const w = new Worker('js/worker.js?v=1');
    w.onmessage = (e) => {
      const d = e.data;
      if (d.progress) progress(d.progress);
      else if (d.error) {
        w.terminate();
        reject(new Error(d.error));
      } else {
        w.terminate();
        resolve(d.result);
      }
    };
    w.onerror = (e) => {
      w.terminate();
      reject(new Error(e.message || 'Error al leer la lista'));
    };
    w.postMessage(msg);
  });
}

// ------------------------------------------------------------------ details

const detailCache = new Map();

export async function movieDetails(m) {
  if (S.playlist?.type !== 'xtream') return null;
  const key = 'm:' + m.id;
  if (detailCache.has(key)) return detailCache.get(key);
  const j = await getJson(xapi('get_vod_info', '&vod_id=' + m.id));
  const i = j?.info || {};
  const d = {
    plot: i.plot || i.description || null,
    cast: i.cast || i.actors || null,
    director: i.director || null,
    genre: i.genre || null,
    duration: i.duration || null,
    year: str(i.releasedate || i.release_date).slice(0, 4) || m.year,
    rating: num(i.rating) || m.rating,
    backdrop: (Array.isArray(i.backdrop_path) ? i.backdrop_path[0] : i.backdrop_path) || null,
    poster: i.movie_image || i.cover_big || m.poster,
  };
  if (d.backdrop && !m.backdrop) m.backdrop = d.backdrop;
  if (d.plot && !m.plot) m.plot = d.plot;
  detailCache.set(key, d);
  return d;
}

export async function seriesDetails(s) {
  const key = 's:' + s.id;
  if (detailCache.has(key)) return detailCache.get(key);
  const j = await getJson(xapi('get_series_info', '&series_id=' + s.id));
  const i = j?.info || {};
  const names = {};
  (j?.seasons || []).forEach((x) => { if (x && x.season_number != null) names[x.season_number] = x.name; });
  const eps = [];
  const groups = j?.episodes || {};
  for (const [season, list] of Object.entries(groups)) {
    for (const e of list || []) {
      eps.push({
        id: str(e.id),
        season: parseInt(e.season ?? season, 10) || 1,
        episode: parseInt(e.episode_num, 10) || eps.length + 1,
        title: cleanEpisodeTitle(str(e.title), s.name, parseInt(e.episode_num, 10) || 0),
        ext: e.container_extension || 'mp4',
        image: e.info?.movie_image || null,
        secs: parseInt(e.info?.duration_secs, 10) || 0,
      });
    }
  }
  const uniq = [...new Map(eps.map((e) => [e.id, e])).values()];
  const seasons = [...new Set(uniq.map((e) => e.season))].sort((a, b) => a - b).map((n) => ({
    number: n,
    name: names[n] && !/^season \d+$/i.test(names[n]) ? names[n] : `Temporada ${n}`,
    episodes: uniq.filter((e) => e.season === n).sort((a, b) => a.episode - b.episode),
  }));
  const d = {
    plot: i.plot || s.plot,
    cast: i.cast || null,
    genre: i.genre || s.genre,
    year: str(i.releaseDate || i.release_date).slice(0, 4) || s.year,
    rating: num(i.rating) || s.rating,
    backdrop: (Array.isArray(i.backdrop_path) ? i.backdrop_path[0] : i.backdrop_path) || s.backdrop,
    poster: i.cover || s.poster,
    seasons,
  };
  if (d.backdrop && !s.backdrop) s.backdrop = d.backdrop;
  detailCache.set(key, d);
  return d;
}

function cleanEpisodeTitle(title, seriesName, n) {
  let t = title.trim();
  const stripped = t.replace(/^.*?[Ss]\d{1,2}\s*[EeXx]\d{1,3}\s*[-–:|.]?\s*/, '').trim();
  if (stripped) t = stripped;
  else if (t.toLowerCase().startsWith(seriesName.toLowerCase())) t = t.slice(seriesName.length).replace(/^[\s\-|:]+/, '');
  return !t || t.toLowerCase() === seriesName.toLowerCase() ? `Episodio ${n}` : t;
}

/** Wide artwork for a few recent movies (one call each), so carousels look right. */
export async function prefetchBackdrops(movies) {
  let changedAny = false;
  for (const m of movies) {
    if (m.backdrop) continue;
    try {
      const d = await movieDetails(m);
      if (d?.backdrop) changedAny = true;
    } catch { /* ignore */ }
  }
  if (changedAny) {
    const bd = (await kvGet('backdrops')) || {};
    for (const m of movies) if (m.backdrop) bd[m.id] = m.backdrop;
    kvSet('backdrops', bd);
    memo.clear();
  }
  return changedAny;
}

// ------------------------------------------------------------------ playback URLs

export function liveUrl(c, forceTs = false) {
  const pl = S.playlist;
  if (c.url) return c.url;
  const ext = forceTs ? 'ts' : prefs.liveFormat === 'ts' ? 'ts' : 'm3u8';
  return `${xbase(pl)}/live/${encodeURIComponent(pl.user)}/${encodeURIComponent(pl.pass)}/${c.id}.${ext}`;
}
export function movieUrl(m) {
  if (m.url) return m.url;
  const pl = S.playlist;
  return `${xbase(pl)}/movie/${encodeURIComponent(pl.user)}/${encodeURIComponent(pl.pass)}/${m.id}.${m.ext || 'mp4'}`;
}
export function episodeUrl(e) {
  const pl = S.playlist;
  return `${xbase(pl)}/series/${encodeURIComponent(pl.user)}/${encodeURIComponent(pl.pass)}/${e.id}.${e.ext || 'mp4'}`;
}

// ------------------------------------------------------------------ guide

export function nowNext(c, now = Date.now()) {
  const list = c.epg ? S.epg.get(c.epg) : null;
  if (!list) return null;
  let cur = null, next = null;
  for (const p of list) {
    if (p.e <= now) continue;
    if (p.s <= now && !cur) cur = p;
    else if (p.s > now) { next = p; break; }
  }
  if (!cur && !next) return null;
  return { now: cur, next };
}

export async function shortEpg(c) {
  if (S.playlist?.type !== 'xtream') return null;
  try {
    const j = await getJson(xapi('get_short_epg', `&stream_id=${c.id}&limit=4`));
    const dec = (s) => {
      try { return decodeURIComponent(escape(atob(s || ''))); } catch { return s || ''; }
    };
    const list = (j?.epg_listings || []).map((p) => ({
      s: parseInt(p.start_timestamp, 10) * 1000,
      e: parseInt(p.stop_timestamp, 10) * 1000,
      t: dec(p.title),
      d: dec(p.description),
    })).filter((p) => p.e > Date.now()).sort((a, b) => a.s - b.s);
    if (!list.length) return null;
    const now = Date.now();
    const cur = list[0].s <= now ? list[0] : null;
    return { now: cur, next: cur ? list[1] || null : list[0] };
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------ live now

const sportStrong = /champions|liga de campeones|uefa|laliga|la liga|liga f\b|liga endesa|hypermotion|premier league|serie a\b|bundesliga|ligue 1|copa del rey|supercopa|europa league|conference league|mundial|eurocopa|copa am[eé]rica|libertadores|\bnba\b|euroliga|euroleague|\bacb\b|\bnfl\b|\bnhl\b|\bmlb\b|f[oó]rmula 1|\bf1\b|moto ?gp|roland garros|wimbledon|us open|open de australia|\batp\b|\bwta\b|\bufc\b|boxeo|tour de francia|vuelta a espa[nñ]a|giro de italia|rugby|grand slam/i;
const sportGeneric = /f[uú]tbol|baloncesto|tenis|partido|jornada|carrera|gran premio/i;
const liveWord = /\bdirecto\b|en vivo|\blive\b|\(d\)/i;
const versus = /\bvs\.?\b| v\. | - /i;
const replay = /resumen|highlights|repetici[oó]n|reemisi[oó]n|\(r\)|previa|post ?partido|magazine|programa/i;
const newsRe = /telediario|informativo|noticias|noticiero|\bnews\b|24 ?horas/i;
const eventName = /\bvs\.?\b| v\. | - |:\s*\S/i;
const noEvent = /no event|sin evento|no hay evento|off ?air|coming soon/i;

export function sportScore(title) {
  let s = 0;
  if (sportStrong.test(title)) s += 2;
  else if (sportGeneric.test(title)) s += 1;
  if (!s) return 0;
  if (liveWord.test(title)) s += 2;
  if (versus.test(title)) s += 1;
  if (replay.test(title)) s -= 3;
  return s;
}

/** Sports and big events airing now, event channels, then a few news channels. */
export function liveNow(limit = 24) {
  return cached('liveNow:' + Math.floor(Date.now() / 60000), () => {
    const now = Date.now();
    const byEpg = new Map();
    for (const c of S.live) if (c.epg && !byEpg.has(c.epg)) byEpg.set(c.epg, c);
    const programmes = [];
    for (const [id, list] of S.epg) {
      const c = byEpg.get(id);
      if (!c) continue;
      const p = list.find((x) => x.s <= now && x.e > now);
      if (p) programmes.push({ c, p });
    }
    const out = [];
    const seenT = new Set(), seenC = new Set();
    const add = (c, title, start, stop, label) => {
      const t = title.toLowerCase().replace(/[^a-z0-9áéíóúñü]/g, '');
      if (out.length < limit && !seenT.has(t) && !seenC.has(c.id)) {
        seenT.add(t);
        seenC.add(c.id);
        out.push({ channel: c, title, start, stop, label });
      }
    };
    programmes
      .map((x) => ({ ...x, score: sportScore(x.p.t) }))
      .filter((x) => x.score >= 2)
      .sort((a, b) => b.score - a.score || a.p.e - b.p.e)
      .forEach((x) => add(x.c, x.p.t, x.p.s, x.p.e, 'DEPORTES'));
    const catName = new Map(S.cats.live.map((c) => [c.id, c.name.toLowerCase()]));
    S.live
      .filter((c) => /evento|event|ppv/.test(catName.get(c.cat) || ''))
      .filter((c) => eventName.test(c.name) && !noEvent.test(c.name))
      .slice(0, 8)
      .forEach((c) => add(c, c.name, 0, 0, 'EVENTO'));
    let news = 0;
    const newsCands = [
      ...programmes.filter((x) => newsRe.test(x.p.t)).map((x) => [x.c, x.p]),
      ...S.live
        .filter((c) => /noticia|news/.test(catName.get(c.cat) || '') || /noticia|news|24 ?h/i.test(c.name))
        .slice(0, 60)
        .map((c) => [c, nowNext(c, now)?.now || null]),
    ];
    for (const [c, p] of newsCands) {
      if (news >= 6) break;
      const before = out.length;
      add(c, p?.t || c.name, p?.s || 0, p?.e || 0, 'NOTICIAS');
      if (out.length > before) news++;
    }
    return out;
  });
}

// ------------------------------------------------------------------ themes

export const THEMES = [
  ['SPORTS', 'Deportes', /deport|sport|f[uú]tbol|futbol|soccer|\bliga\b|laliga|dazn|espn|eurosport|\bnba\b|\bnfl\b|\bf1\b|motor|golf|tenis|tennis|bein|\bufc\b|boxeo|\bppv\b|evento|event/i, ['#1F7A4A', '#0F2E21']],
  ['NEWS', 'Noticias', /noticia|news|informa|24 ?h/i, ['#2A4C9C', '#121D3D']],
  ['KIDS', 'Infantil', /infantil|kids|niñ|child|cartoon|disney|nick|baby|junior|\bclan\b|boing|anim/i, ['#E8752A', '#5A2410']],
  ['MOVIES', 'Cine y series', /cine|movie|film|pel[ií]cula|series|\bhbo\b|cinema|\btnt\b|\baxn\b|\bamc\b|paramount|\bvod\b/i, ['#7A2E8C', '#2A0F33']],
  ['DOCS', 'Documentales', /docu|discovery|national geo|nat ?geo|history|historia|natura|odisea|viaje|travel|ciencia|science/i, ['#1C7C7A', '#0D2E2D']],
  ['MUSIC', 'Música', /m[uú]sica|music|\bmtv\b|hits|\bradio\b|\bvh1\b|concierto/i, ['#C2307A', '#3D0F28']],
];

export function themeCategories() {
  return cached('themeCats', () => {
    const map = new Map();
    for (const c of S.cats.live) {
      const t = THEMES.find((x) => x[2].test(c.name));
      if (!t) continue;
      if (!map.has(t[0])) map.set(t[0], new Set());
      map.get(t[0]).add(c.id);
    }
    return map;
  });
}
export function liveThemes() {
  return cached('themes', () => {
    const cats = themeCategories();
    return THEMES.map(([key, title, , colors]) => {
      const set = cats.get(key);
      const count = set ? S.live.filter((c) => set.has(c.cat)).length : 0;
      return { key, title, colors, count };
    }).filter((t) => t.count > 0);
  });
}
export function channelsOfTheme(key) {
  const set = themeCategories().get(key);
  return set ? S.live.filter((c) => set.has(c.cat)) : [];
}

// ------------------------------------------------------------------ lists

const byAdded = (a, b) => b.added - a.added;
export const recentMovies = (n = 40) => cached('recentM' + n, () => [...S.vod].sort(byAdded).slice(0, n));
export const recentSeries = (n = 40) => cached('recentS' + n, () => [...S.series].sort(byAdded).slice(0, n));

export function recommendedMovies(n = 30) {
  return cached('recM' + n, () => {
    const rated = [...S.vod].filter((m) => m.rating >= 6.5).sort(byAdded).slice(0, n * 2);
    const list = rated.length >= 8 ? rated : [...rated, ...recentMovies(n)];
    return [...new Map(list.map((m) => [m.id, m])).values()].slice(0, n);
  });
}
export function recommendedSeries(n = 30) {
  return cached('recS' + n, () => {
    const rated = [...S.series].filter((m) => m.rating >= 6.5).sort(byAdded).slice(0, n * 2);
    const list = rated.length >= 8 ? rated : [...rated, ...recentSeries(n)];
    return [...new Map(list.map((m) => [m.id, m])).values()].slice(0, n);
  });
}

/** Items of each category, newest first (built once per catalogue). */
export function catIndex(kind) {
  return cached('catIndex:' + kind, () => {
    const map = new Map();
    for (const x of kind === 'series' ? S.series : kind === 'live' ? S.live : S.vod) {
      if (!map.has(x.cat)) map.set(x.cat, []);
      map.get(x.cat).push(x);
    }
    if (kind !== 'live') for (const l of map.values()) l.sort(byAdded);
    return map;
  });
}
export function inCategory(kind, catId, n = 40) {
  return (catIndex(kind).get(catId) || []).slice(0, n);
}

export function similarMovies(m, n = 20) {
  const y = parseInt(m.year, 10);
  const me = searchName(m.name);
  return S.vod
    .filter((x) => x.cat === m.cat && x.id !== m.id)
    .map((x) => {
      const xy = parseInt(x.year, 10);
      let s = 2 + x.rating / 10;
      if (y && xy) s += Math.abs(y - xy) <= 3 ? 1.5 : Math.abs(y - xy) <= 8 ? 0.7 : 0;
      return [x, s];
    })
    .sort((a, b) => b[1] - a[1])
    .map((a) => a[0])
    .filter((x, i, arr) => searchName(x.name) !== me && arr.findIndex((z) => searchName(z.name) === searchName(x.name)) === i)
    .slice(0, n);
}

const genresOf = (g) => (g || '').split(/[,/&|]/).map((x) => x.trim().toLowerCase()).filter((x) => x.length > 2);
export function similarSeries(s, n = 20) {
  const gs = genresOf(s.genre);
  const y = parseInt(s.year, 10);
  const me = searchName(s.name);
  return S.series
    .filter((x) => x.id !== s.id)
    .map((x) => {
      let p = 2 * Math.min(3, genresOf(x.genre).filter((g) => gs.includes(g)).length);
      if (x.cat === s.cat) p += 1.5;
      const xy = parseInt(x.year, 10);
      if (y && xy && Math.abs(y - xy) <= 5) p += 0.8;
      return [x, p + x.rating / 10];
    })
    .filter((a) => a[1] > 1.2)
    .sort((a, b) => b[1] - a[1])
    .map((a) => a[0])
    .filter((x) => searchName(x.name) !== me)
    .slice(0, n);
}

/** "Película" → "pelicula", to search without caring about accents or case. */
export const fold = (s) => (s || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');

export function search(q) {
  const k = fold(q.trim());
  if (k.length < 2) return { live: [], vod: [], series: [] };
  const words = k.split(/\s+/).filter(Boolean);
  const f = (key, list) => {
    const names = cached('fold:' + key, () => list.map((x) => fold(x.name)));
    const out = [];
    for (let i = 0; i < list.length && out.length < 40; i++) if (words.every((w) => names[i].includes(w))) out.push(list[i]);
    return out;
  };
  return { live: f('live', S.live), vod: f('vod', S.vod), series: f('series', S.series) };
}

// ------------------------------------------------------------------ user data

export const uidOf = (kind, id) => `${kind}:${id}`;

export function isFavorite(kind, id) {
  return S.favorites.some((f) => f.kind === kind && f.id === id);
}
export async function toggleFavorite(kind, id, title, image) {
  const i = S.favorites.findIndex((f) => f.kind === kind && f.id === id);
  let added;
  if (i >= 0) {
    S.favorites.splice(i, 1);
    added = false;
  } else {
    S.favorites.unshift({ kind, id, title, image, at: Date.now() });
    added = true;
  }
  await kvSet('favorites', S.favorites);
  changed();
  return added;
}

export async function markChannelWatched(id) {
  S.recents = [{ id, at: Date.now() }, ...S.recents.filter((r) => r.id !== id)].slice(0, 30);
  await kvSet('recents', S.recents);
  changed();
}
export const channelById = (id) => cached('chMap', () => new Map(S.live.map((c) => [c.id, c]))).get(id);
export const movieById = (id) => cached('mMap', () => new Map(S.vod.map((c) => [c.id, c]))).get(id);
export const seriesById = (id) => cached('sMap', () => new Map(S.series.map((c) => [c.id, c]))).get(id);
export const recentChannels = () => S.recents.map((r) => channelById(r.id)).filter(Boolean);

let progressTimer;
export function saveProgress(entry) {
  const finished = entry.dur > 0 && entry.pos > entry.dur * 0.92;
  if (entry.kind === 'movie') {
    if (finished) delete S.progress[entry.uid];
    else if (entry.pos > 15000) S.progress[entry.uid] = { ...entry, at: Date.now() };
  } else if (entry.kind === 'episode') {
    S.progress[entry.uid] = { ...entry, pos: finished ? entry.dur : entry.pos, at: Date.now() };
    const suid = uidOf('series', entry.seriesId);
    if (finished && !entry.hasNext) delete S.progress[suid];
    else if (!finished && entry.pos > 15000) {
      S.progress[suid] = { ...entry, uid: suid, kind: 'series', id: entry.seriesId, title: entry.seriesTitle, image: entry.seriesImage || entry.image, at: Date.now() };
    }
  }
  clearTimeout(progressTimer);
  progressTimer = setTimeout(() => kvSet('progress', S.progress), 800);
}
export function continueWatching() {
  return Object.values(S.progress)
    .filter((p) => p.kind === 'movie' || p.kind === 'series')
    .sort((a, b) => b.at - a.at)
    .slice(0, 30);
}
export async function removeProgress(uid) {
  delete S.progress[uid];
  await kvSet('progress', S.progress);
  changed();
}
export function notifyChanged() {
  changed();
}

// ------------------------------------------------------------------ captures (last frame of live channels)

const capMem = new Map();
export async function saveCapture(id, dataUrl) {
  const v = { at: Date.now(), img: dataUrl };
  capMem.set(id, v);
  await kvSet('cap:' + id, v);
}
export function captureOf(id) {
  return capMem.get(id) || null;
}
const capChecked = new Set();
/** Loads saved captures from storage; returns how many were new. */
export async function loadCaptures(ids) {
  let n = 0;
  await Promise.all([...new Set(ids)].filter((id) => !capMem.has(id) && !capChecked.has(id)).map(async (id) => {
    capChecked.add(id);
    const v = await kvGet('cap:' + id);
    if (v) {
      capMem.set(id, v);
      n++;
    }
  }));
  return n;
}

export { sleep };

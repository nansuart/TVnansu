// Building blocks shared by the screens: pictures, cards, rows, carousels, live preview.
import { h, fmt, cleanTitle } from './util.js';
import { viaProxy, prefs, captureOf, saveCapture, liveUrl, isFavorite, toggleFavorite } from './data.js';
import { toast } from './util.js';

const svg = (body, extra = '') => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ${extra}>${body}</svg>`;
export const ICONS = {
  home: svg('<path d="M3.5 11 12 4l8.5 7M6 9.5V20h12V9.5M10 20v-5.5h4V20"/>'),
  live: svg('<circle cx="12" cy="12" r="2.2" fill="currentColor"/><path d="M16.2 7.4a6 6 0 0 1 0 9.2M7.8 7.4a6 6 0 0 0 0 9.2M19.1 4.9a10 10 0 0 1 0 14.2M4.9 4.9a10 10 0 0 0 0 14.2"/>'),
  search: svg('<circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/>'),
  settings: svg('<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>'),
  play: svg('<path d="M7 4.5v15l12.5-7.5z" fill="currentColor"/>'),
  pause: svg('<path d="M7 4.5h3.5v15H7zM13.5 4.5H17v15h-3.5z" fill="currentColor" stroke="none"/>'),
  plus: svg('<path d="M12 5v14M5 12h14"/>'),
  check: svg('<path d="m5 12.5 4.5 4.5L19 7.5"/>'),
  heart: svg('<path d="M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.4 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z"/>'),
  heartOn: svg('<path d="M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.4 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z" fill="currentColor"/>'),
  chevL: svg('<path d="m14.5 5-7 7 7 7"/>'),
  chevR: svg('<path d="m9.5 5 7 7-7 7"/>'),
  back: svg('<path d="M19 12H5M11 6l-6 6 6 6"/>'),
  close: svg('<path d="M6 6l12 12M18 6 6 18"/>'),
  back10: svg('<path d="M3.5 12a8.5 8.5 0 1 0 2.5-6"/><path d="M3.5 3.5V8H8"/><text x="12.2" y="15.3" font-size="7.2" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none" font-family="Inter,sans-serif">10</text>'),
  fwd10: svg('<path d="M20.5 12a8.5 8.5 0 1 1-2.5-6"/><path d="M20.5 3.5V8H16"/><text x="11.8" y="15.3" font-size="7.2" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none" font-family="Inter,sans-serif">10</text>'),
  prev: svg('<path d="M18 5v14L8 12z" fill="currentColor"/><path d="M6 5v14"/>'),
  next: svg('<path d="M6 5v14l10-7z" fill="currentColor"/><path d="M18 5v14"/>'),
  vol: svg('<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/>'),
  mute: svg('<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor"/><path d="m16 9.5 5 5M21 9.5l-5 5"/>'),
  audio: svg('<path d="M4.5 5.5h15a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H10l-4.5 3.5v-3.5h-1a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z"/><path d="m9 14 3-6.5 3 6.5M10 12h4"/>'),
  cc: svg('<rect x="3" y="5.5" width="18" height="13" rx="2.5"/><path d="M10.5 10.3a2.3 2.3 0 1 0 0 3.4M17 10.3a2.3 2.3 0 1 0 0 3.4"/>'),
  list: svg('<path d="M8.5 6.5h11M8.5 12h11M8.5 17.5h11"/><circle cx="4.5" cy="6.5" r="1" fill="currentColor"/><circle cx="4.5" cy="12" r="1" fill="currentColor"/><circle cx="4.5" cy="17.5" r="1" fill="currentColor"/>'),
  fs: svg('<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>'),
  fsExit: svg('<path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/>'),
  grid: svg('<rect x="4" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5"/>'),
};

/** Element holding an SVG icon. */
export function icon(name, cls = 'ic') {
  const s = h('span.' + cls);
  s.innerHTML = ICONS[name] || '';
  return s;
}

export function spinner() {
  return h('div.spinner');
}
export function loading(msg) {
  return h('div.center', null, spinner(), msg ? h('div.dim', null, msg) : null);
}
export function emptyState(title, msg) {
  return h('div.center.empty', null, h('div.t-title', null, title), msg ? h('div.dim', { style: { maxWidth: '520px' } }, msg) : null);
}

export function progressBar(fraction) {
  return h('div.progress', null, h('i', { style: { width: Math.max(0, Math.min(1, fraction || 0)) * 100 + '%' } }));
}

// ------------------------------------------------------------------ pictures
//
// Posters and logos are the heaviest part of the site. https pictures load
// straight from their server. http ones (most IPTV logos) first try https and
// only go through the PHP proxy when that fails, a few at a time and only once
// they are on screen, so hundreds of them never pile up on the hosting.

const SLOTS = 4;
let inFlight = 0;
const waiting = [];
const noHttps = new Set();
const hostFails = new Map();
let pageHttps = location.protocol === 'https:';
try { if (localStorage.getItem('nt:testHttps') === '1') pageHttps = true; } catch { /* for local tests */ }
const hostOf = (u) => {
  try { return new URL(u).host; } catch { return ''; }
};

function pump() {
  while (inFlight < SLOTS && waiting.length) {
    const el = waiting.pop(); // newest first: what the user is looking at now
    if (!el.isConnected || el._started) continue;
    el._started = true;
    inFlight++;
    let done = false;
    const fin = (ok) => {
      if (done) return;
      done = true;
      inFlight--;
      clearTimeout(t);
      const host = el._host;
      if (ok) hostFails.set(host, -1000);
      else if (hostFails.get(host) !== -1000) hostFails.set(host, (hostFails.get(host) || 0) + 1);
      pump();
    };
    const t = setTimeout(() => fin(false), 15000);
    el.addEventListener('load', () => fin(true), { once: true });
    el.addEventListener('error', () => fin(false), { once: true });
    el.src = el._proxy;
  }
}
const lazy = 'IntersectionObserver' in window
  ? new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      lazy.unobserve(e.target);
      waiting.push(e.target);
    }
    pump();
  }, { rootMargin: '300px' })
  : null;

/** <img> for a remote picture (see above). onFail runs when it can't be shown. */
export function picture(raw, cls = '', onFail) {
  const i = h('img' + (cls ? '.' + cls.split(' ').join('.') : ''), { alt: '', decoding: 'async', draggable: false });
  const fail = () => {
    i.classList.add('missing');
    if (onFail) onFail();
  };
  if (!raw || !/^(https?:|data:)/i.test(raw)) {
    i.classList.add('missing');
    return i;
  }
  if (raw.startsWith('data:') || prefs.direct) {
    i.loading = 'lazy';
    i.onerror = fail;
    i.src = raw;
    return i;
  }
  const host = hostOf(raw);
  const viaServer = () => {
    if ((hostFails.get(host) || 0) >= 3) return fail(); // that server is down: stop asking
    i._proxy = viaProxy(raw);
    i._host = host;
    i.onerror = fail;
    if (lazy) lazy.observe(i);
    else {
      waiting.push(i);
      pump();
    }
  };
  i.referrerPolicy = 'no-referrer';
  i.loading = 'lazy';
  if (raw.startsWith('https://') || !pageHttps) {
    i.onerror = raw.startsWith('https://') ? fail : () => { i.onerror = null; viaServer(); };
    i.src = raw;
  } else if (noHttps.has(host)) {
    viaServer();
  } else {
    i.onerror = () => {
      i.onerror = null;
      noHttps.add(host);
      viaServer();
    };
    i.src = 'https://' + raw.slice(7);
  }
  return i;
}

/** Fill a box with media, cropping instead of stretching. */
export function coverImg(src, cls = 'bg') {
  return picture(src, cls);
}

/** Channel logo on its plate, or the channel name when there is no logo. */
export function logoTile(url, name, { hideMissing = false } = {}) {
  const tile = h('div.logo-tile');
  const nm = hideMissing ? null : h('div.nm', null, name || '');
  if (!url) {
    if (hideMissing) tile.style.display = 'none';
    else tile.append(nm);
    return tile;
  }
  const i = picture(url, '', () => {
    if (hideMissing) tile.style.display = 'none';
    else nm.style.display = 'flex';
  });
  i.addEventListener('load', () => {
    if (nm) nm.style.display = 'none';
    // Dark logos on a light plate so they never disappear. Pictures from other
    // servers can't be read: they get a faint outline instead.
    if (!i.src.includes('api/proxy.php')) {
      i.classList.add('xo');
      return;
    }
    try {
      const c = document.createElement('canvas');
      c.width = c.height = 16;
      const g = c.getContext('2d', { willReadFrequently: true });
      g.drawImage(i, 0, 0, 16, 16);
      const d = g.getImageData(0, 0, 16, 16).data;
      let dark = 0, opaque = 0;
      for (let p = 0; p < d.length; p += 4) {
        if (d[p + 3] > 128) {
          opaque++;
          if (d[p] + d[p + 1] + d[p + 2] < 180) dark++;
        }
      }
      if (opaque && dark / opaque > 0.22 && opaque < 230) tile.classList.add('light');
    } catch { /* not readable */ }
  });
  tile.append(i);
  if (nm) tile.append(nm);
  return tile;
}

// ------------------------------------------------------------------ favourites button

/** Round +/✓ (or heart) button for cards; updates itself. */
export function favButton(kind, id, title, image, { heart = false, cls = 'favbtn' } = {}) {
  const b = h('button.' + cls, { type: 'button', tabIndex: -1 });
  const draw = () => {
    const on = isFavorite(kind, id);
    b.classList.toggle('on', on);
    b.innerHTML = heart ? (on ? ICONS.heartOn : ICONS.heart) : on ? ICONS.check : ICONS.plus;
    b.title = on ? (kind === 'live' ? 'Quitar de favoritos' : 'Quitar de Mi lista') : kind === 'live' ? 'Añadir a favoritos' : 'Añadir a Mi lista';
  };
  draw();
  b.addEventListener('pointerdown', (e) => e.stopPropagation());
  b.addEventListener('click', async (e) => {
    e.stopPropagation();
    e.preventDefault();
    const added = await toggleFavorite(kind, id, title, image);
    draw();
    toast(added ? (kind === 'live' ? 'Añadido a favoritos' : 'Añadido a Mi lista') : kind === 'live' ? 'Quitado de favoritos' : 'Quitado de Mi lista');
  });
  b._draw = draw;
  return b;
}

// ------------------------------------------------------------------ cards

/** Poster card (2:3) with title, optional subtitle, rating, progress and a +/✓ button. */
export function posterCard({ title, image, subtitle, rating, progress, onClick, onLong, key, fav, wide = false }) {
  const art = h('div.art', null, picture(image), h('div.fallback', null, title), h('div.hov'), icon('play', 'playic'));
  if (rating > 0) art.append(h('div.chip.tl', null, '★ ' + rating.toFixed(1).replace('.', ',')));
  if (progress > 0) art.append(progressBar(progress));
  if (fav) art.append(favButton(fav.kind, fav.id, fav.title, fav.image));
  const el = h('div.card.f' + (wide ? '.wide' : ''), { tabIndex: -1, dataset: { key: key || '' }, title }, art, h('div.ttl.clamp1', null, title), subtitle ? h('div.sub.clamp1', null, subtitle) : null);
  bindClick(el, onClick, onLong);
  return el;
}

/** Live channel card (16:9): the last frame seen, or the logo. */
export function channelCard(c, { onClick, onLong, showAge = false } = {}) {
  const art = h('div.art');
  const fill = () => {
    const cap = captureOf(c.id);
    art.replaceChildren();
    if (cap) {
      art.append(h('img.capimg', { src: cap.img, alt: '' }));
      if (showAge) art.append(h('div.chip.tl', null, fmt.ago(cap.at)));
    } else art.append(logoTile(c.logo, c.name));
    art.append(h('div.hov'), icon('play', 'playic'), favButton('live', c.id, c.name, c.logo, { heart: true }));
    art._hasCap = !!cap;
  };
  fill();
  const el = h('div.card.wide.f', { tabIndex: -1, dataset: { key: 'c' + c.id, ch: c.id }, title: c.name }, art, h('div.ttl.clamp1', null, c.name));
  el._refill = () => { if (!art._hasCap && captureOf(c.id)) fill(); };
  bindClick(el, onClick, onLong);
  return el;
}

/** Swaps logos for captures that finished loading, without redrawing the screen. */
export function refreshCaptures(root) {
  root.querySelectorAll('[data-ch]').forEach((el) => el._refill && el._refill());
}

export function eventSubtitle(ev) {
  return [
    ev.channel.name.toLowerCase() !== ev.title.toLowerCase() ? ev.channel.name : null,
    ev.start ? `${fmt.clock(ev.start)} – ${fmt.clock(ev.stop)}` : null,
  ].filter(Boolean).join('  ·  ');
}

export const TINT = { DEPORTES: '#16402A', NOTICIAS: '#1B2A55', EVENTO: '#4A1E2A' };

export function eventCard(ev, { onClick } = {}) {
  const el = h('div.event.f', { tabIndex: -1, dataset: { ch: ev.channel.id, key: 'e' + ev.channel.id }, title: ev.title });
  const bg = h('div.evbg');
  const fill = () => {
    const cap = captureOf(ev.channel.id);
    bg.replaceChildren();
    if (cap) bg.append(h('img.cap', { src: cap.img, alt: '' }));
    else {
      bg.append(h('div.tint', { style: { background: `linear-gradient(135deg, ${TINT[ev.label] || '#2e2e38'}, #17171c)` } }));
      bg.append(h('div.lg', null, logoTile(ev.channel.logo, '', { hideMissing: true })));
    }
    bg._hasCap = !!cap;
  };
  fill();
  el._refill = () => { if (!bg._hasCap && captureOf(ev.channel.id)) fill(); };
  el.append(bg, h('div.shade'), h('span.livechip', null, h('i.dot'), ev.label), icon('play', 'playic'));
  const sub = eventSubtitle(ev);
  const txt = h('div.txt', null, h('div.t' + (sub ? '.clamp2' : '.clamp3'), null, ev.title), sub ? h('div.s.clamp1', null, sub) : null);
  if (ev.start && ev.stop > ev.start) txt.append(progressBar((Date.now() - ev.start) / (ev.stop - ev.start)));
  el.append(txt);
  bindClick(el, onClick);
  return el;
}

// ------------------------------------------------------------------ rows

/**
 * Titled horizontal row with arrow buttons for the mouse. Items are built lazily:
 * only the first ones until the row is reached (by focus, hover or scrolling).
 * opts.more adds a "Ver todo" link next to the title.
 */
export function shelf(title, items, render, { live = false, key, more } = {}) {
  const row = h('div.row', { dataset: { group: key || title } });
  const first = 16;
  items.slice(0, first).forEach((it, i) => row.append(render(it, i)));
  let done = items.length <= first;
  const rest = () => {
    if (done) return;
    done = true;
    items.slice(first).forEach((it, i) => row.append(render(it, i + first)));
    update();
  };
  const left = h('button.rarr.l', { type: 'button', tabIndex: -1, title: 'Anterior', hidden: true, html: ICONS.chevL });
  const right = h('button.rarr.r', { type: 'button', tabIndex: -1, title: 'Siguiente', hidden: true, html: ICONS.chevR });
  const update = () => {
    const max = row.scrollWidth - row.clientWidth;
    left.hidden = row.scrollLeft <= 4;
    right.hidden = row.scrollLeft >= max - 4 && done;
  };
  const step = (d) => {
    rest();
    row.scrollBy({ left: d * Math.max(200, row.clientWidth * 0.82), behavior: prefs.reduceMotion ? 'auto' : 'smooth' });
  };
  left.addEventListener('click', () => step(-1));
  right.addEventListener('click', () => step(1));
  row.addEventListener('scroll', () => {
    if (!done && row.scrollLeft > 0) rest();
    update();
  }, { passive: true });
  row.addEventListener('focusin', rest);
  const wrap = h('div.rowwrap', null, row, left, right);
  wrap.addEventListener('pointerenter', () => {
    rest();
    update();
  });
  const head = h('div.shelf-h', null, h('h3', null, live ? h('i.dot') : null, title));
  if (more) {
    const m = h('button.more', { type: 'button', tabIndex: -1 }, 'Ver todo', icon('chevR', 'ic sm'));
    m.addEventListener('click', more);
    head.append(m);
  }
  requestAnimationFrame(update);
  return h('section.shelf', null, head, wrap);
}

export function skeletonShelf(count = 7) {
  const row = h('div.row');
  for (let i = 0; i < count; i++) row.append(h('div.sk.card'));
  return h('section.shelf', null, h('div.shelf-h', null, h('div.sk', { style: { width: '180px', height: '18px' } })), row);
}

/** Click and long-press (hold OK / touch for 650 ms, or right click). */
export function bindClick(el, onClick, onLong) {
  if (onClick) el.addEventListener('click', (e) => {
    if (el._longFired) {
      el._longFired = false;
      return;
    }
    onClick(e);
  });
  if (onLong) {
    let t;
    const start = () => {
      el._longFired = false;
      t = setTimeout(() => {
        el._longFired = true;
        onLong();
      }, 650);
    };
    const end = () => clearTimeout(t);
    el.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.repeat) start();
    });
    el.addEventListener('keyup', (e) => {
      if (e.key === 'Enter') end();
    });
    el.addEventListener('pointerdown', (e) => { if (e.pointerType === 'touch') start(); });
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);
    el.addEventListener('pointerleave', end);
    el.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      end();
      onLong();
    });
  }
}

/** Pill button. opts.icon adds an SVG before the text; primary is the white one. */
export function button(text, onClick, { cls = '', primary = false, icon: ic = null, title = null } = {}) {
  const b = h('button.pill.f' + (cls ? '.' + cls : '') + (primary ? '.primary' : ''), { tabIndex: -1, type: 'button', title });
  if (ic) b.append(icon(ic));
  b.append(h('span', null, text || ''));
  if (primary) b.dataset.default = '';
  b.addEventListener('click', onClick);
  return b;
}

/** Round icon-only button. */
export function iconButton(name, onClick, title, cls = '') {
  const b = h('button.pill.round.f' + (cls ? '.' + cls : ''), { tabIndex: -1, type: 'button', title, 'aria-label': title });
  b.innerHTML = ICONS[name];
  b.addEventListener('click', onClick);
  return b;
}

export function segmented(options, selected, onSelect) {
  const wrap = h('div.seg', { dataset: { group: 'seg' + Math.random() } });
  options.forEach((o, i) => {
    const b = h('button.tab.f' + (i === selected ? '.on' : ''), { tabIndex: -1, type: 'button' }, o);
    b.addEventListener('click', () => {
      wrap.querySelectorAll('.tab').forEach((x) => x.classList.remove('on'));
      b.classList.add('on');
      onSelect(i);
    });
    wrap.append(b);
  });
  return wrap;
}

/** Carousel position dots; clickable when onPick is given. */
export function dots(count, index, onPick) {
  const d = h('div.dots');
  for (let i = 0; i < count; i++) {
    const b = h('button' + (i === index ? '.on' : ''), { type: 'button', tabIndex: -1, 'aria-label': `Elemento ${i + 1}` });
    if (onPick) b.addEventListener('click', (e) => { e.stopPropagation(); onPick(i); });
    d.append(b);
  }
  return d;
}

// ------------------------------------------------------------------ live preview

/** One muted preview stream at a time (providers allow few connections). */
export const Preview = {
  current: null,
  paused: false,
  stop() {
    if (this.current) this.current.stop();
    this.current = null;
    if (tourSnap) tourSnap.abort();
  },
};
document.addEventListener('visibilitychange', () => {
  if (document.hidden) Preview.stop();
});

/**
 * A muted live picture of a channel inside a box: the last capture or logo first,
 * the video fades in once it plays (after a short rest on it), and a frame is
 * saved as the channel's capture.
 */
export function livePreview(channel, { delay = 1800 } = {}) {
  const wrap = h('div.video-cover');
  const cap = captureOf(channel.id);
  if (cap) wrap.append(h('img', { src: cap.img, alt: '' }));
  else {
    const lt = logoTile(channel.logo, '', { hideMissing: true });
    lt.classList.add('floating');
    wrap.append(lt);
  }
  if (!prefs.livePreview) return { el: wrap, stop() {} };
  const video = h('video', { muted: true, playsInline: true, autoplay: true });
  video.muted = true;
  wrap.append(video);
  let hls = null, ts = null, timer = null, capTimer = null, stopped = false;
  const ctl = {
    el: wrap,
    stop() {
      stopped = true;
      clearTimeout(timer);
      clearInterval(capTimer);
      try { hls && hls.destroy(); } catch { /* empty */ }
      try { ts && (ts.unload(), ts.detachMediaElement(), ts.destroy()); } catch { /* empty */ }
      hls = ts = null;
      video.removeAttribute('src');
      try { video.load(); } catch { /* empty */ }
      if (Preview.current === ctl) Preview.current = null;
    },
  };
  const start = () => {
    if (stopped || !wrap.isConnected) return;
    if (Preview.paused || document.hidden) {
      timer = setTimeout(start, 1500);
      return;
    }
    if (Preview.current && Preview.current !== ctl) Preview.current.stop();
    if (tourSnap) tourSnap.abort();
    Preview.current = ctl;
    attachStream(video, liveUrl(channel), true, (o) => { hls = o.hls; ts = o.ts; }, () => ctl.stop());
  };
  video.addEventListener('playing', () => {
    video.classList.add('on');
    clearInterval(capTimer);
    setTimeout(() => grab(video, channel.id), 2500);
    capTimer = setInterval(() => grab(video, channel.id), 60000);
  });
  timer = setTimeout(start, delay);
  return ctl;
}

/** Saves the frame on screen (small JPEG) as the channel's capture. True when saved. */
export function grab(video, id) {
  try {
    if (!video.videoWidth) return false;
    const c = document.createElement('canvas');
    c.width = 480;
    c.height = Math.round((480 * video.videoHeight) / video.videoWidth) || 270;
    const g = c.getContext('2d', { willReadFrequently: true });
    g.drawImage(video, 0, 0, c.width, c.height);
    const px = g.getImageData(0, 0, c.width, c.height).data;
    let sum = 0;
    for (let i = 0; i < px.length; i += 4 * 97) sum += px[i] + px[i + 1] + px[i + 2];
    if (sum / (px.length / (4 * 97)) / 3 < 10) return false; // black frame
    saveCapture(id, c.toDataURL('image/jpeg', 0.72));
    return true;
  } catch { return false; /* cross-origin video in direct mode */ }
}

// ------------------------------------------------------------------ automatic thumbnails

let tourSnap = null; // the channel being captured right now

/**
 * Visits the given channels one at a time in the background (a hidden, muted player that
 * only loads a couple of seconds), saves a frame of what each one is showing and swaps it
 * into the cards under root. Waits while anything else plays; repeats every `every` ms.
 */
export function thumbTour(root, channels, { every = 15 * 60000 } = {}) {
  let stopped = false, timer = null;
  const tried = new Map();
  const busy = () => document.hidden || Preview.current || document.querySelector('.player');
  const due = (c) => {
    const cap = captureOf(c.id);
    const t = tried.get(c.id) || 0;
    return (!cap || Date.now() - cap.at > every) && Date.now() - t > every;
  };
  const next = (ms) => {
    clearTimeout(timer);
    if (!stopped) timer = setTimeout(step, ms);
  };
  async function step() {
    if (stopped) return;
    if (!root.isConnected) return stop();
    if (!prefs.autoThumbs || busy()) return next(4000);
    const c = channels.find(due);
    if (!c) return next(60000);
    tried.set(c.id, Date.now());
    const res = await snap(c);
    if (res === 'aborted') tried.delete(c.id);
    if (res === 'ok' && root.isConnected) refreshCaptures(root);
    next(1500);
  }
  function snap(c) {
    return new Promise((resolve) => {
      const v = h('video.tourvid', { muted: true, playsInline: true, preload: 'auto' });
      v.muted = true;
      document.body.append(v);
      let eng = null, done = false;
      const finish = (res) => {
        if (done) return;
        done = true;
        clearTimeout(limit);
        clearInterval(watch);
        try { eng && eng.hls && eng.hls.destroy(); } catch { /* empty */ }
        try { eng && eng.ts && (eng.ts.unload(), eng.ts.detachMediaElement(), eng.ts.destroy()); } catch { /* empty */ }
        v.removeAttribute('src');
        try { v.load(); } catch { /* empty */ }
        v.remove();
        if (tourSnap === me) tourSnap = null;
        resolve(res);
      };
      const me = { abort: () => finish('aborted') };
      tourSnap = me;
      const limit = setTimeout(() => finish('fail'), 15000);
      const watch = setInterval(() => { if (stopped || busy()) finish('aborted'); }, 500);
      v.addEventListener('timeupdate', () => {
        if (done || v.currentTime < 0.5) return;
        if (grab(v, c.id)) finish('ok');
        else if (v.currentTime > 5) finish('fail'); // only black frames
      });
      eng = attachStream(v, liveUrl(c), true, null, () => finish('fail'), { lite: true });
    });
  }
  function stop() {
    stopped = true;
    clearTimeout(timer);
    if (tourSnap) tourSnap.abort();
  }
  next(2500);
  return stop;
}

/**
 * Plays a URL in a <video>: HLS with hls.js, MPEG-TS with mpegts.js, everything
 * else natively.
 */
export function attachStream(video, rawUrl, live, onEngines, onFatal, { direct = false, lite = false } = {}) {
  const isHls = /\.m3u8(\?|$)/i.test(rawUrl);
  const isTs = /\.ts(\?|$)/i.test(rawUrl) || (live && !isHls && !/\.(mp4|mkv|webm|mov|m4v)(\?|$)/i.test(rawUrl));
  const url = direct ? rawUrl : viaProxy(rawUrl);
  const engines = { hls: null, ts: null };
  if (isHls && window.Hls && window.Hls.isSupported()) {
    const hls = new window.Hls({
      lowLatencyMode: false,
      maxBufferLength: lite ? 2 : live ? 20 : 40,
      maxMaxBufferLength: lite ? 4 : 60,
      backBufferLength: 20,
      manifestLoadingMaxRetry: 2,
      levelLoadingMaxRetry: 3,
      fragLoadingMaxRetry: 3,
      xhrSetup: (xhr) => { xhr.withCredentials = true; },
    });
    engines.hls = hls;
    let recovered = 0;
    hls.on(window.Hls.Events.ERROR, (_, data) => {
      if (!data.fatal) return;
      if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR && recovered++ < 2) hls.recoverMediaError();
      else if (onFatal) onFatal(data);
    });
    hls.loadSource(url);
    hls.attachMedia(video);
  } else if (isHls && video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = url;
  } else if (isTs && window.mpegts && window.mpegts.getFeatureList().mseLivePlayback) {
    const p = window.mpegts.createPlayer(
      { type: 'mpegts', isLive: live, url: new URL(url, location.href).href, withCredentials: true },
      { enableWorker: true, liveBufferLatencyChasing: live, lazyLoad: !live, autoCleanupSourceBuffer: true },
    );
    engines.ts = p;
    p.on(window.mpegts.Events.ERROR, (type, detail) => onFatal && onFatal({ type, detail }));
    p.attachMediaElement(video);
    p.load();
    const pr = p.play();
    if (pr && pr.catch) pr.catch(() => {});
  } else {
    video.src = url;
  }
  const pr = video.play();
  if (pr && pr.catch) pr.catch(() => {});
  if (onEngines) onEngines(engines);
  return engines;
}

export { cleanTitle };

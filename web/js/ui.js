// Building blocks shared by the screens: cards, rows, carousels, live preview.
import { h, img, fmt, cleanTitle } from './util.js';
import { imgUrl, viaProxy, prefs, captureOf, saveCapture, liveUrl } from './data.js';

export const ICONS = {
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 11 12 4l8.5 7M6 9.5V20h12V9.5M10 20v-5.5h4V20"/></svg>',
  live: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="2.2" fill="currentColor"/><path d="M16.2 7.4a6 6 0 0 1 0 9.2M7.8 7.4a6 6 0 0 0 0 9.2M19.1 4.9a10 10 0 0 1 0 14.2M4.9 4.9a10 10 0 0 0 0 14.2"/></svg>',
  movies: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10 9l5.5 3-5.5 3z" fill="currentColor"/></svg>',
  series: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="8" width="15" height="12" rx="2.5"/><path d="M7 4.5h12a2.5 2.5 0 0 1 2.5 2.5v9"/></svg>',
  search: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/></svg>',
  settings: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16M4 12h16M4 18h16"/><circle cx="15" cy="6" r="2.4" fill="currentColor"/><circle cx="8" cy="12" r="2.4" fill="currentColor"/><circle cx="13" cy="18" r="2.4" fill="currentColor"/></svg>',
};

export function spinner() {
  return h('div.spinner');
}
export function loading(msg) {
  return h('div.center', null, spinner(), msg ? h('div.dim', null, msg) : null);
}
export function emptyState(title, msg) {
  return h('div.center', null, h('div.t-title', null, title), msg ? h('div.dim', { style: { maxWidth: '60rem' } }, msg) : null);
}

export function progressBar(fraction) {
  return h('div.progress', null, h('i', { style: { width: Math.max(0, Math.min(1, fraction || 0)) * 100 + '%' } }));
}

/** Channel logo on its plate, or the channel name when there is no logo. */
export function logoTile(url, name, { hideMissing = false } = {}) {
  const tile = h('div.logo-tile');
  const src = imgUrl(url);
  if (!src) {
    if (hideMissing) tile.style.display = 'none';
    else tile.append(h('div.nm', null, name || ''));
    return tile;
  }
  const i = img(src);
  i.addEventListener('load', () => {
    // Dark logos on a light plate so they never disappear.
    try {
      const c = document.createElement('canvas');
      c.width = c.height = 16;
      const g = c.getContext('2d');
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
    } catch { /* cross-origin pictures can't be read; leave as is */ }
  });
  i.addEventListener('error', () => {
    if (hideMissing) tile.style.display = 'none';
  });
  tile.append(i);
  if (!hideMissing) tile.append(h('div.nm', null, name || ''));
  return tile;
}

/** Poster card (2:3) with title, optional subtitle, rating and progress. */
export function posterCard({ title, image, subtitle, rating, progress, onClick, onLong, key }) {
  const art = h('div.art', null, img(imgUrl(image)), h('div.fallback', null, title));
  if (rating > 0) art.append(h('div.chip.tr', null, '★ ' + rating.toFixed(1).replace('.', ',')));
  if (progress > 0) art.append(progressBar(progress));
  const el = h('div.card.f', { tabIndex: -1, dataset: { key: key || '' } }, art, h('div.ttl.clamp1', null, title), subtitle ? h('div.sub.clamp1', null, subtitle) : null);
  bindClick(el, onClick, onLong);
  return el;
}

/** Live channel card (16:9): the last frame seen, or the logo. */
export function channelCard(c, { onClick, onLong, width, showAge = false } = {}) {
  const art = h('div.art');
  const cap = captureOf(c.id);
  if (cap) {
    art.append(h('img', { src: cap.img, style: { width: '100%', height: '100%', objectFit: 'cover' } }));
    if (showAge) art.append(h('div.chip.tl', null, fmt.ago(cap.at)));
  } else {
    art.append(logoTile(c.logo, c.name));
  }
  const el = h('div.card.wide.f', { tabIndex: -1, style: { width: (width || 18.8) + 'rem' } }, art, h('div.ttl.clamp1', null, c.name));
  bindClick(el, onClick, onLong);
  return el;
}

export function eventSubtitle(ev) {
  return [
    ev.channel.name.toLowerCase() !== ev.title.toLowerCase() ? ev.channel.name : null,
    ev.start ? `${fmt.clock(ev.start)} – ${fmt.clock(ev.stop)}` : null,
  ].filter(Boolean).join('  ·  ');
}

export const TINT = { DEPORTES: '#16402A', NOTICIAS: '#1B2A55', EVENTO: '#4A1E2A' };

export function eventCard(ev, { onClick } = {}) {
  const el = h('div.event.f', { tabIndex: -1 });
  const cap = captureOf(ev.channel.id);
  if (cap) el.append(h('img.cap', { src: cap.img }));
  else {
    el.append(h('div.tint', { style: { background: `linear-gradient(135deg, ${TINT[ev.label] || '#2e2e38'}, #17171c)` } }));
    el.append(h('div.lg', null, logoTile(ev.channel.logo, '', { hideMissing: true })));
  }
  el.append(h('div.shade'), h('span.livechip', null, h('i.dot'), ev.label));
  const sub = eventSubtitle(ev);
  const txt = h('div.txt', null, h('div.t' + (sub ? '.clamp2' : '.clamp3'), null, ev.title), sub ? h('div.s.clamp1', null, sub) : null);
  if (ev.start && ev.stop > ev.start) txt.append(progressBar((Date.now() - ev.start) / (ev.stop - ev.start)));
  el.append(txt);
  bindClick(el, onClick);
  return el;
}

export function themeTile({ title, subtitle, colors, onClick }) {
  const el = h('div.theme.f', { tabIndex: -1, style: { background: `linear-gradient(135deg, ${colors[0]}, ${colors[1]})` } },
    h('div.n', null, title), h('div.c', null, subtitle));
  bindClick(el, onClick);
  return el;
}

/** Titled horizontal row. Items are built lazily: only the first ones until the row is reached. */
export function shelf(title, items, render, { live = false, key } = {}) {
  const row = h('div.row', { dataset: { group: key || title } });
  const first = 14;
  items.slice(0, first).forEach((it, i) => row.append(render(it, i)));
  if (items.length > first) {
    let done = false;
    row.addEventListener('focusin', () => {
      if (done) return;
      done = true;
      items.slice(first).forEach((it, i) => row.append(render(it, i + first)));
    });
  }
  return h('section.shelf', null, h('h3', null, live ? h('i.dot') : null, title), row);
}

export function skeletonShelf(count = 7) {
  const row = h('div.row');
  for (let i = 0; i < count; i++) row.append(h('div.sk', { style: { width: '10.6rem', aspectRatio: '2/3' } }));
  return h('section.shelf', null, h('h3', null, h('div.sk', { style: { width: '15rem', height: '1.4rem' } })), row);
}

/** Click and long-press (hold OK / mouse for 600 ms). */
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
    el.addEventListener('pointerdown', start);
    el.addEventListener('pointerup', end);
    el.addEventListener('pointerleave', end);
    el.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      onLong();
    });
  }
}

export function button(text, onClick, { cls = '', primary = false } = {}) {
  const b = h('button.pill.f' + (cls ? '.' + cls : ''), { tabIndex: -1, type: 'button' }, text);
  if (primary) b.dataset.default = '';
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

export function dots(count, index) {
  const d = h('div.dots');
  for (let i = 0; i < count; i++) d.append(h('i' + (i === index ? '.on' : '')));
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
  },
};

/**
 * A muted live picture of a channel inside a box: the last capture or logo first,
 * the video fades in once it plays (after the user rests on it for 2 s), and a frame
 * is saved as the channel's capture.
 */
export function livePreview(channel, { delay = 2000 } = {}) {
  const wrap = h('div.video-cover');
  const cap = captureOf(channel.id);
  if (cap) wrap.append(h('img', { src: cap.img }));
  else {
    const lt = logoTile(channel.logo, '', { hideMissing: true });
    lt.style.background = 'transparent';
    lt.style.left = '30%';
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
    if (stopped || Preview.paused || !wrap.isConnected) return;
    if (Preview.current && Preview.current !== ctl) Preview.current.stop();
    Preview.current = ctl;
    attachStream(video, liveUrl(channel), true, (o) => { hls = o.hls; ts = o.ts; });
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

/** Saves the frame on screen (small JPEG) as the channel's capture. */
export function grab(video, id) {
  try {
    if (!video.videoWidth) return;
    const c = document.createElement('canvas');
    c.width = 480;
    c.height = Math.round((480 * video.videoHeight) / video.videoWidth) || 270;
    const g = c.getContext('2d');
    g.drawImage(video, 0, 0, c.width, c.height);
    const px = g.getImageData(0, 0, c.width, c.height).data;
    let sum = 0;
    for (let i = 0; i < px.length; i += 4 * 97) sum += px[i] + px[i + 1] + px[i + 2];
    if (sum / (px.length / (4 * 97)) / 3 < 10) return; // black frame
    saveCapture(id, c.toDataURL('image/jpeg', 0.72));
  } catch { /* cross-origin video in direct mode */ }
}

/**
 * Plays a URL in a <video>: HLS with hls.js, MPEG-TS with mpegts.js, everything
 * else natively. Live TS URLs fall back to HLS and the other way round.
 */
export function attachStream(video, rawUrl, live, onEngines, onFatal) {
  const isHls = /\.m3u8(\?|$)/i.test(rawUrl);
  const isTs = /\.ts(\?|$)/i.test(rawUrl) || (live && !isHls && !/\.(mp4|mkv|webm|mov|m4v)(\?|$)/i.test(rawUrl));
  const url = viaProxy(rawUrl);
  const engines = { hls: null, ts: null };
  if (isHls && window.Hls && window.Hls.isSupported()) {
    const hls = new window.Hls({
      lowLatencyMode: false,
      maxBufferLength: live ? 20 : 40,
      backBufferLength: 30,
      xhrSetup: (xhr) => { xhr.withCredentials = true; },
    });
    engines.hls = hls;
    hls.on(window.Hls.Events.ERROR, (_, data) => {
      if (!data.fatal) return;
      if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError();
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

/** Fill a box with media, cropping instead of stretching. */
export function coverImg(src, cls = 'bg') {
  return img(imgUrl(src), cls);
}

export { cleanTitle };

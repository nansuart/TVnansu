// Full-screen player: live TV with zapping and the Hispano IPTV watermark, movies and
// episodes with seeking, progress and "next episode". Works with the mouse
// (controls appear when it moves) and with the keyboard (shortcuts as on the TV).
import { h, fmt, toast, debounce } from './util.js';
import { pushLayer, popLayer, onKey, focus } from './nav.js';
import { attachStream, grab, logoTile, progressBar, Preview, ICONS, picture } from './ui.js';
import { prefs, markChannelWatched, saveProgress, S, liveUrl, nowNext, shortEpg, notifyChanged, directVodUrl, directMemo, viaProxy, failureOf } from './data.js';

let active = null;

export function isPlaying() {
  return !!active;
}

/**
 * items: [{kind:'live'|'movie'|'episode', id, title, subtitle, image, url, channel,
 *          seriesId, seriesTitle, seriesImage}]
 */
export function play(items, index = 0, startMs = 0) {
  if (!items.length) return;
  if (active) active.close(true);
  Preview.stop();
  active = createPlayer(items, Math.max(0, Math.min(index, items.length - 1)), startMs);
}

const LANGS = {
  spa: 'Español', es: 'Español', esp: 'Español', eng: 'Inglés', en: 'Inglés', fre: 'Francés', fra: 'Francés', fr: 'Francés',
  ger: 'Alemán', deu: 'Alemán', de: 'Alemán', ita: 'Italiano', it: 'Italiano', por: 'Portugués', pt: 'Portugués',
  jpn: 'Japonés', ja: 'Japonés', kor: 'Coreano', ko: 'Coreano', chi: 'Chino', zho: 'Chino', zh: 'Chino', rus: 'Ruso', ru: 'Ruso',
  cat: 'Catalán', ca: 'Catalán', lat: 'Español latino', ara: 'Árabe', ar: 'Árabe', und: '',
};
/** "Español · E-AC3 5.1" from what the file says about a track. */
function trackLabel(t, i, word) {
  const lang = LANGS[(t.language || '').toLowerCase()] ?? (t.language || '');
  const title = (t.label || t.title || '').trim();
  const codec = (t.codec || '').replace(/^eac3$/i, 'E-AC3').replace(/^ac3$/i, 'AC3').replace(/^subrip$/i, 'SRT').toUpperCase().replace('E-AC3', 'E-AC3');
  const ch = t.channels > 2 ? (t.channels === 6 ? ' 5.1' : t.channels === 8 ? ' 7.1' : ` ${t.channels}ch`) : '';
  const main = title && (!lang || title.toLowerCase().includes(lang.toLowerCase().slice(0, 4))) ? title : [lang, title].filter(Boolean).join(' · ');
  return (main || `${word} ${i + 1}`) + (codec && !main.toUpperCase().includes(codec) ? ` · ${codec}${ch}` : ch);
}

let moviLib = null;
/** Loads the MKV player only the first time an MKV is opened (about 10 MB, from this hosting). */
function loadMoviLib() {
  if (!moviLib) {
    moviLib = import(new URL('lib/movi/element.slim.js', location.href).href).catch((e) => {
      moviLib = null;
      throw e;
    });
  }
  return moviLib;
}

let abLib = null;
/** Loads avbridge (the third player, for tests) only when chosen. */
function loadAvbridgeLib() {
  if (!abLib) {
    globalThis.AVBRIDGE_LIBAV_BASE = new URL('lib/avbridge/libav', location.href).href;
    abLib = import(new URL('lib/avbridge/element-browser.js', location.href).href).catch((e) => {
      abLib = null;
      throw e;
    });
  }
  return abLib;
}

export const ENGINE_NAMES = { native: 'Navegador', movi: 'MKV (movi)', avbridge: 'avbridge' };

function pbtn(name, title, onClick, cls = '') {
  const b = h('button.pbtn' + (cls ? '.' + cls : ''), { type: 'button', title, 'aria-label': title, tabIndex: -1 });
  b.innerHTML = ICONS[name];
  b.addEventListener('click', (e) => {
    e.stopPropagation();
    onClick(e);
  });
  return b;
}
const setIcon = (b, name) => { b.innerHTML = ICONS[name]; };

function createPlayer(items, startIndex, startMs) {
  const opener = document.activeElement;
  let index = startIndex;
  let engines = { hls: null, ts: null };
  let triedTs = false;
  let hideTimer = null;
  let panel = null; // 'channels' | 'subs' | 'audio'
  let digits = '';
  let digitsTimer = null;
  let capTimer = null;
  let scrubTarget = null;
  let scrubTimer = null;
  let nextDismissed = -1;
  let closed = false;
  // Recovery: big files through the hosting sometimes stop arriving; reconnect at the same point.
  let everPlayed = false;
  let retries = 0;
  let lastPos = 0;
  let stallTimer = null;
  let retryTimer = null;
  // Movies and episodes straight from the provider when its https works (see directVodUrl).
  let usingDirect = false;
  let currentDirect = null;
  let forceProxy = false;
  let pendingDirectFail = null;
  let probeTimer = null;
  /** Tells the hosting a seek just happened, so it answers with a small first piece. */
  const markSeek = () => {
    if (!usingDirect) document.cookie = 'hi_seek=1; path=/; max-age=4; SameSite=Lax';
  };
  let overBar = false;
  let dragging = false;
  const item = () => items[index];
  const isLive = () => item().kind === 'live';

  const makeVideo = () => h('video', { playsInline: true, autoplay: true, preload: 'auto' });
  let video = makeVideo();
  video.volume = Math.max(0, Math.min(1, prefs.volume ?? 1));
  video.muted = !!prefs.muted;
  const spin = h('div.spinwrap', null, h('div.spinner'));
  const errBox = h('div.err', { style: { display: 'none' } });
  const flash = h('div.flash');
  const mosca = h('div.mosca');
  const digitsEl = h('div.digits', { style: { display: 'none' } });
  const top = h('div.ptop');
  const bottom = h('div.pbottom');
  const leftPanel = h('div.side.left.closed', { dataset: { group: 'chlist' } });
  const rightPanel = h('div.side.right.closed', { dataset: { group: 'tracks' } });
  const nextUp = h('div.nextup', { style: { display: 'none' } });
  const root = h('div.player', null, video, spin, flash, mosca, top, bottom, digitsEl, nextUp, errBox, leftPanel, rightPanel);
  document.body.append(root);
  document.body.classList.toggle('subbg', !!prefs.subtitleBackground);
  applySubSize();
  pushLayer(root);

  // ------------------------------------------------------------ fixed controls

  const back = pbtn('back', 'Volver (Esc)', () => close());
  const titleBox = h('div.ptitle');
  const liveTag = h('span.livebadge', null, h('i.dot'), 'EN DIRECTO');
  top.append(back, titleBox);

  const playBtn = pbtn('pause', 'Pausa (Espacio)', () => togglePlay(), 'big');
  const back10 = pbtn('back10', 'Retroceder 10 s (◀)', () => seekBy(-10));
  const fwd10 = pbtn('fwd10', 'Avanzar 10 s (▶)', () => seekBy(10));
  const prevCh = pbtn('prev', 'Canal anterior (▲)', () => goTo(index - 1));
  const nextCh = pbtn('next', 'Canal siguiente (▼)', () => goTo(index + 1));
  const nextEp = pbtn('next', 'Siguiente episodio', () => goTo(index + 1));
  const muteBtn = pbtn('vol', 'Silencio (M)', () => setMuted(!video.muted));
  const volRange = h('input.rng', { type: 'range', min: '0', max: '1', step: '0.05', 'aria-label': 'Volumen' });
  volRange.value = String(video.muted ? 0 : video.volume);
  volRange.addEventListener('input', () => {
    video.volume = parseFloat(volRange.value);
    setMuted(video.volume === 0);
    prefs.volume = video.volume;
  });
  const vol = h('div.vol', null, muteBtn, volRange);
  const time = h('span.ptime');
  const now = h('div.pnow');
  const listBtn = pbtn('list', 'Lista de canales (L)', () => (panel === 'channels' ? closePanel() : openChannels()));
  const ccBtn = pbtn('cc', 'Subtítulos (C)', () => (panel === 'subs' ? closePanel() : openSubs()));
  const audioBtn = pbtn('audio', 'Idioma del audio (A)', () => (panel === 'audio' ? closePanel() : openAudio()));
  const fsBtn = pbtn('fs', 'Pantalla completa (F)', () => toggleFullscreen());
  const seek = h('div.seek', null, h('div.track', null, h('i.buf'), h('i.played'), h('i.knob')), h('div.tip'));
  const row = h('div.prow');
  bottom.append(seek, row);
  [top, bottom].forEach((el) => {
    el.addEventListener('pointerenter', () => { overBar = true; });
    el.addEventListener('pointerleave', () => { overBar = false; showControls(); });
  });

  function setMuted(m) {
    video.muted = m;
    prefs.muted = m;
    setIcon(muteBtn, m || video.volume === 0 ? 'mute' : 'vol');
    volRange.value = String(m ? 0 : video.volume);
  }
  setMuted(video.muted);

  function toggleFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else if (root.requestFullscreen) root.requestFullscreen().catch(() => {});
  }
  const onFs = () => setIcon(fsBtn, document.fullscreenElement ? 'fsExit' : 'fs');
  document.addEventListener('fullscreenchange', onFs);

  // Seek bar: click, drag, hover time.
  const posFromEvent = (e) => {
    const r = seek.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
  };
  seek.addEventListener('pointermove', (e) => {
    const tip = seek.querySelector('.tip');
    const f = posFromEvent(e);
    if (video.duration) {
      tip.textContent = fmt.duration(f * video.duration * 1000);
      tip.style.left = f * 100 + '%';
    }
    if (dragging) {
      scrubTarget = f * (video.duration || 0);
      renderTime();
    }
  });
  seek.addEventListener('pointerdown', (e) => {
    if (!video.duration) return;
    e.stopPropagation();
    dragging = true;
    seek.classList.add('drag');
    seek.setPointerCapture(e.pointerId);
    scrubTarget = posFromEvent(e) * video.duration;
    renderTime();
  });
  const endDrag = () => {
    if (!dragging) return;
    dragging = false;
    seek.classList.remove('drag');
    if (scrubTarget != null) {
      markSeek();
      video.currentTime = scrubTarget;
    }
    scrubTarget = null;
    renderTime();
  };
  seek.addEventListener('pointerup', endDrag);
  seek.addEventListener('pointercancel', endDrag);

  // ------------------------------------------------------------ video events

  let clickTimer = null;
  /** Hooks a picture element (a <video>, or the MKV player) to the controls. */
  function wire(el) {
    el.addEventListener('waiting', () => {
      spin.style.display = 'flex';
      clearTimeout(stallTimer);
      stallTimer = setTimeout(() => { if (!video.paused) recover(); }, 15000);
    });
    el.addEventListener('playing', () => {
      spin.style.display = 'none';
      errBox.style.display = 'none';
      clearTimeout(stallTimer);
      everPlayed = true;
    });
    el.addEventListener('canplay', () => { spin.style.display = 'none'; clearTimeout(stallTimer); });
    el.addEventListener('timeupdate', () => {
      if (video.currentTime > 0.5) {
        lastPos = video.currentTime;
        if (retries && everPlayed && video.currentTime > 0) retries = Math.max(0, retries - 0.01);
      }
      if (!isLive()) renderTime();
      checkNextUp();
    });
    el.addEventListener('progress', () => { if (!isLive()) renderTime(); });
    el.addEventListener('play', () => { setIcon(playBtn, 'pause'); playBtn.title = 'Pausa (Espacio)'; showControls(); });
    el.addEventListener('pause', () => { setIcon(playBtn, 'play'); playBtn.title = 'Reproducir (Espacio)'; showControls(); });
    el.addEventListener('loadedmetadata', () => {
      clearTimeout(probeTimer);
      if (usingDirect && currentDirect) directMemo.set(currentDirect, true);
      if (!usingDirect && pendingDirectFail) {
        directMemo.set(pendingDirectFail, false);
        pendingDirectFail = null;
      }
      updateAudioBtn();
    });
    el.addEventListener('volumechange', () => setIcon(muteBtn, video.muted || video.volume === 0 ? 'mute' : 'vol'));
    el.addEventListener('ended', () => {
      if (item().kind === 'episode' && index < items.length - 1) goTo(index + 1);
      else if (!isLive()) close();
    });
    el.addEventListener('error', () => onFatal());

    // Click: pause/play (or close a panel); double click: full screen.
    el.addEventListener('click', () => {
      if (panel) {
        closePanel();
        return;
      }
      clearTimeout(clickTimer);
      clickTimer = setTimeout(() => togglePlay(true), 220);
    });
    el.addEventListener('dblclick', () => {
      clearTimeout(clickTimer);
      toggleFullscreen();
    });
    el.addEventListener('errordisplay', () => onFatal());
    el.addEventListener('audiotrackchange', () => updateAudioBtn());
    el.addEventListener('trackschange', () => { updateAudioBtn(); applyTrackPrefs(); });
  }
  wire(video);

  /** Puts another picture element in place of the current one. */
  function swapTo(el) {
    const vol = video.volume, muted = video.muted;
    video.replaceWith(el);
    video = el;
    try {
      el.volume = vol;
      el.muted = muted;
    } catch { /* empty */ }
    wire(el);
  }
  const isMoviEl = (el) => el && el.tagName === 'MOVI-PLAYER';
  const isAbEl = (el) => el && el.tagName === 'AVBRIDGE-VIDEO';
  const isCustom = (el) => isMoviEl(el) || isAbEl(el);

  root.addEventListener('pointermove', (e) => {
    if (e.pointerType === 'mouse' && Math.abs(e.movementX) + Math.abs(e.movementY) < 2) return;
    showControls();
  });
  root.addEventListener('wheel', (e) => {
    if (panel) return;
    e.preventDefault();
    changeVolume(e.deltaY < 0 ? 0.05 : -0.05);
  }, { passive: false });

  function changeVolume(d) {
    video.volume = Math.max(0, Math.min(1, Math.round((video.volume + d) * 100) / 100));
    if (video.volume > 0 && video.muted) video.muted = false;
    prefs.volume = video.volume;
    prefs.muted = video.muted;
    volRange.value = String(video.muted ? 0 : video.volume);
    showFlash(video.muted || video.volume === 0 ? 'mute' : 'vol', Math.round(video.volume * 100) + ' %');
    showControls();
  }

  function applySubSize() {
    const sizes = [3.4, 3.9, 4.5, 5.2, 6];
    let st = document.getElementById('cue-size');
    if (!st) {
      st = h('style');
      st.id = 'cue-size';
      document.head.append(st);
    }
    st.textContent = `video::cue{font-size:clamp(15px, ${sizes[prefs.subtitleSize] || 3.4}vh, 72px)}`;
  }

  // ------------------------------------------------------------ MKV player (movi-player)

  let loadToken = 0;
  const moviOff = new Set(); // items where it failed: they use the browser's player
  let moviAudio = null, moviSub = null; // chosen tracks (ids of the MKV player)
  const forceMovi = new Set(); // files the browser couldn't open: try the MKV player (it also reads MP4/HEVC/AC3)
  const nativeTried = new Set();
  const wantsMovi = (it) => it.kind !== 'live' && !moviOff.has(it.id)
    && (forceMovi.has(it.id) || /\.(mkv|avi)(\?|$)/i.test(it.url || ''));
  // Player for movies and episodes: Ajustes → "Reproductor", or the one chosen in the error box.
  const override = new Map();
  let currentEngine = 'native';
  let abAudio = null, abSub = null;
  const vodPref = () => prefs.vodPlayer || (prefs.mkvPlayer === false ? 'native' : 'auto');
  const isAuto = (it) => !override.has(it.id) && vodPref() === 'auto';
  function engineFor(it) {
    if (it.kind === 'live') return 'native';
    if (override.has(it.id)) return override.get(it.id);
    const pref = vodPref();
    if (pref !== 'auto') return pref;
    return wantsMovi(it) ? 'movi' : 'native';
  }

  async function loadAvbridge(it, url, seekMs, token) {
    try {
      await loadAvbridgeLib();
    } catch {
      if (token === loadToken && !closed) showError();
      return;
    }
    if (token !== loadToken || closed) return;
    const el = document.createElement('avbridge-video');
    el.setAttribute('autoplay', '');
    el.setAttribute('playsinline', '');
    el.setAttribute('fit', 'contain');
    abAudio = null;
    abSub = null;
    swapTo(el);
    if (seekMs > 0) {
      const once = () => {
        el.removeEventListener('loadedmetadata', once);
        try { el.currentTime = seekMs / 1000; } catch { /* empty */ }
      };
      el.addEventListener('loadedmetadata', once);
    }
    el.src = viaProxy(url);
    try { const pr = el.play && el.play(); if (pr && pr.catch) pr.catch(() => {}); } catch { /* empty */ }
  }

  function subAttrs(el) {
    el.setAttribute('subtitlesize', String([0.85, 1, 1.15, 1.3, 1.5][prefs.subtitleSize] || 1));
    el.setAttribute('subtitlebg', prefs.subtitleBackground ? '0.63' : '0');
    el.setAttribute('subtitleedge', prefs.subtitleBackground ? 'none' : 'shadow');
  }

  async function loadMovi(it, url, seekMs, token) {
    try {
      await loadMoviLib();
    } catch {
      if (token !== loadToken || closed) return;
      if (isAuto(it)) {
        moviOff.add(it.id);
        load(seekMs, true);
      } else showError();
      return;
    }
    if (token !== loadToken || closed) return;
    const el = document.createElement('movi-player');
    el.setAttribute('wasmurl', new URL('lib/movi/movi.wasm', location.href).href);
    el.setAttribute('buffersize', '48'); // read about a minute ahead, not the whole film
    el.setAttribute('nohotkeys', '');
    el.setAttribute('noerrorscreen', '');
    el.setAttribute('spinnerdelay', '600');
    el.setAttribute('playsinline', '');
    el.setAttribute('objectfit', 'contain');
    el.setAttribute('autoplay', '');
    if (seekMs > 0) el.setAttribute('startat', String(Math.floor(seekMs / 1000)));
    subAttrs(el);
    moviAudio = null;
    moviSub = null;
    swapTo(el);
    styleMoviSubs(el);
    el.setAttribute('src', viaProxy(url));
  }

  /** Subtitles like the TV app: Montserrat Medium with a soft outline (or the dark box). */
  function styleMoviSubs(el, tries = 0) {
    const root = el.shadowRoot;
    if (!root) {
      if (tries < 20) setTimeout(() => styleMoviSubs(el, tries + 1), 50);
      return;
    }
    let st = root.querySelector('style[data-hi]');
    if (!st) {
      st = document.createElement('style');
      st.dataset.hi = '';
      root.append(st);
    }
    const bg = !!prefs.subtitleBackground;
    const shadow = bg ? 'none' : '0 0 3px rgba(0,0,0,.95), 0 1px 2px rgba(0,0,0,.9), 0 2px 10px rgba(0,0,0,.6)';
    st.textContent = `
      .movi-subtitle-line { font-family: Montserrat, system-ui, -apple-system, 'Segoe UI', sans-serif !important; font-weight: 500 !important; letter-spacing: .005em; line-height: 1.25 !important; text-shadow: ${shadow} !important; -webkit-font-smoothing: antialiased; }
      /* Lines appear and leave in one cut, like on the TV: no word fade, no slide. */
      .movi-subtitle-new, .movi-subtitle-static { animation: none !important; opacity: 1 !important; }
      .movi-subtitle-block, .movi-subtitle-block.movi-subtitle-advance { animation: none !important; transform: none !important; }
      /* The caption is not a control here: no dragging it around, no transcript on click. */
      .movi-subtitle-block { pointer-events: none !important; cursor: default !important; }
      ${bg ? `
      /* "Fondo": a dark box around each line, as in the TV app. The library only
         draws one for WebVTT, so ordinary film subtitles (SRT/ASS) need it here. */
      .movi-subtitle-block { background: none !important; padding: 0 !important; }
      .movi-subtitle-line { width: fit-content !important; max-width: 100%; margin-left: auto !important; margin-right: auto !important; padding: .08em .45em !important; border-radius: 4px !important; background: rgba(0, 0, 0, .63) !important; }
      .movi-subtitle-line + .movi-subtitle-line { margin-top: 2px !important; }` : `
      .movi-subtitle-line { background: none !important; }`}`;
  }

  /** Puts back the languages chosen last time (audio and subtitles), if the file has them. */
  function applyTrackPrefs() {
    if (!isMoviEl(video) || !video.player) return;
    const p = video.player;
    const auds = p.getAudioTracks?.() || [];
    if (moviAudio == null && prefs.audioLang) {
      const a = auds.find((t) => (t.language || '').toLowerCase() === prefs.audioLang);
      if (a && auds.length > 1) {
        p.selectAudioTrack(a.id);
        moviAudio = a.id;
      }
    }
    const subs = p.getSubtitleTracks?.() || [];
    if (moviSub == null && prefs.subLang) {
      const t = subs.find((x) => (x.language || '').toLowerCase() === prefs.subLang);
      if (t) {
        p.selectSubtitleTrack(t.id);
        moviSub = t.id;
      }
    }
    updateAudioBtn();
  }

  function load(seekMs = 0, isRetry = false) {
    stopEngines();
    clearTimeout(stallTimer);
    clearTimeout(retryTimer);
    if (!isRetry) {
      retries = 0;
      everPlayed = false;
    }
    lastPos = seekMs / 1000;
    triedTs = false;
    errBox.style.display = 'none';
    spin.style.display = 'flex';
    const it = item();
    const url = it.kind === 'live' ? liveUrl(it.channel) : it.url;
    const token = ++loadToken;
    // MKV/AVI (or the player chosen in Ajustes): the web's own players.
    const eng = engineFor(it);
    currentEngine = eng;
    if (eng === 'movi' || eng === 'avbridge') {
      usingDirect = false;
      currentDirect = null;
      engines = { hls: null, ts: null };
      if (eng === 'movi') loadMovi(it, url, seekMs, token);
      else loadAvbridge(it, url, seekMs, token);
      renderControls();
      showControls();
      return;
    }
    if (isCustom(video)) swapTo(makeVideo());
    const direct = it.kind !== 'live' && !forceProxy ? directVodUrl(url) : null;
    clearTimeout(probeTimer);
    usingDirect = !!(direct && directMemo.allowed(direct));
    currentDirect = usingDirect ? direct : null;
    if (usingDirect) {
      engines = attachStream(video, direct, false, null, onFatal, { direct: true });
      // No answer from the provider in a few seconds: go through the hosting instead.
      probeTimer = setTimeout(() => { if (usingDirect && video.readyState < 1) toProxy(); }, 7000);
    } else {
      if (it.kind !== 'live') markSeek();
      engines = attachStream(video, url, it.kind === 'live', null, onFatal);
    }
    if (engines.hls && window.Hls) engines.hls.on(window.Hls.Events.AUDIO_TRACKS_UPDATED, updateAudioBtn);
    if (seekMs > 0) {
      const once = () => {
        markSeek();
        try { video.currentTime = seekMs / 1000; } catch { /* empty */ }
        video.removeEventListener('loadedmetadata', once);
      };
      video.addEventListener('loadedmetadata', once);
    }
    if (it.kind === 'live') {
      markChannelWatched(it.id);
      clearTimeout(capTimer);
      capTimer = setTimeout(function cap() {
        grab(video, it.id);
        capTimer = setTimeout(cap, 90000);
      }, 8000);
    }
    renderControls();
    showControls();
  }

  function stopEngines() {
    if (isCustom(video)) {
      // The MKV player is thrown away whole (it frees its decoders when removed).
      try { video.pause(); } catch { /* empty */ }
      swapTo(makeVideo());
    }
    try { engines.hls && engines.hls.destroy(); } catch { /* empty */ }
    try { engines.ts && (engines.ts.unload(), engines.ts.detachMediaElement(), engines.ts.destroy()); } catch { /* empty */ }
    engines = { hls: null, ts: null };
    video.removeAttribute('src');
    try { video.load(); } catch { /* empty */ }
  }

  /** Direct playback didn't work: remember it and use the hosting from here on. */
  function toProxy() {
    if (closed) return;
    // Only remembered as "no direct" if the hosting then works (the movie itself may be broken).
    pendingDirectFail = currentDirect;
    forceProxy = true;
    load(lastPos * 1000, true);
  }

  /** The video stopped or failed after playing: reconnect where it was, a few times. */
  function recover() {
    if (closed) return;
    clearTimeout(stallTimer);
    if (usingDirect) forceProxy = true;
    if (retries >= 3) {
      showError();
      return;
    }
    retries = Math.floor(retries) + 1;
    const pos = isLive() ? 0 : lastPos * 1000;
    stopEngines();
    spin.style.display = 'flex';
    errBox.style.display = 'none';
    // A short pause lets the previous connection with the provider close first.
    retryTimer = setTimeout(() => load(pos, true), 1200 * retries);
  }

  function onFatal() {
    if (closed) return;
    const it = item();
    if (usingDirect && !everPlayed) {
      toProxy();
      return;
    }
    // A movie or episode that never started: try the other player before giving up
    // (the browser's for files the MKV player can't read, and the other way round).
    if (it.kind !== 'live' && !everPlayed) {
      if (!isAuto(it)) {
        showError();
        return;
      }
      if (isMoviEl(video)) {
        moviOff.add(it.id);
        if (!nativeTried.has(it.id)) {
          load(lastPos * 1000, true);
          return;
        }
      } else {
        nativeTried.add(it.id);
        if (!moviOff.has(it.id) && !forceMovi.has(it.id)) {
          forceMovi.add(it.id);
          load(lastPos * 1000, true);
          return;
        }
      }
      showError();
      return;
    }
    if (everPlayed || retries > 0) {
      recover();
      return;
    }
    if (it.kind === 'live' && !triedTs && !it.channel.url) {
      // HLS refused: try the plain TS stream.
      triedTs = true;
      stopEngines();
      engines = attachStream(video, liveUrl(it.channel, true), true, null, () => showError());
      return;
    }
    showError();
  }

  function showError() {
    spin.style.display = 'none';
    const it = item();
    clearTimeout(stallTimer);
    let title = 'No se pudo reproducir';
    let hint;
    if (everPlayed) {
      title = 'Se cortó la conexión';
      hint = 'El proveedor dejó de enviar el vídeo. Suele pasar si hay otra conexión abierta con tu cuenta (la tele u otra pestaña). Reintentar sigue donde ibas.';
    } else if (it.kind !== 'live' && /\.(mkv|avi)(\?|$)/i.test(it.url || '')) {
      const safari = /Safari/.test(navigator.userAgent) && !/Chrome|Chromium|CriOS|Edg|OPR|Android/.test(navigator.userAgent);
      hint = safari
        ? 'Safari no reproduce archivos MKV. Ábrelo en Chrome o Edge, o en la app de la tele.'
        : 'Este archivo (MKV/AVI) puede no reproducirse en el navegador. En la app de la tele sí funciona.';
    } else if (it.kind !== 'live') {
      hint = isAuto(it) ? 'Se probó con el reproductor del navegador y con el de MKV de la web.' : `Con el reproductor «${ENGINE_NAMES[currentEngine]}».`;
    } else {
      hint = 'Puede que la emisión esté caída o que haya demasiadas conexiones a la vez con tu cuenta.';
    }
    const retry = h('button.pill.primary', { type: 'button' }, 'Reintentar');
    retry.addEventListener('click', () => load(isLive() ? 0 : lastPos * 1000));
    const btns = h('div.errbtns', null, retry);
    if (isLive() && items.length > 1) {
      const nx = h('button.pill', { type: 'button' }, 'Siguiente canal');
      nx.addEventListener('click', () => goTo(index + 1));
      btns.append(nx);
    }
    const mediaErr = !isCustom(video) && video.error ? video.error.code : 0;
    const diag = h('div.diag', null, 'Comprobando qué pasa…');
    // Movies and episodes: try the same file with another of the three players.
    let engRow = null;
    if (!isLive()) {
      engRow = h('div.engrow', null, h('span', null, 'Probar con:'));
      ['native', 'movi', 'avbridge'].filter((e) => e !== currentEngine).forEach((e) => {
        const b = h('button.pill', { type: 'button' }, ENGINE_NAMES[e]);
        b.addEventListener('click', () => {
          override.set(it.id, e);
          load(lastPos * 1000);
        });
        engRow.append(b);
      });
    }
    errBox.replaceChildren(h('div.et', null, title), h('div.dim', null, hint), diag, btns, engRow);
    // Asks the provider for the first bytes of the file, to say what really went wrong.
    (async () => {
      const url = isLive() ? liveUrl(it.channel) : it.url;
      try {
        const r = await fetch(viaProxy(url), { credentials: 'same-origin', headers: { Range: 'bytes=0-1' } });
        if (!r.ok) diag.textContent = 'Detalle: ' + await failureOf(r);
        else if (mediaErr === 4 || mediaErr === 3 || !everPlayed) {
          const ct = (r.headers.get('content-type') || '').split(';')[0];
          diag.textContent = `Detalle: el archivo llega bien${ct ? ` (${ct})` : ''}, pero ningún reproductor de la web pudo leer su formato${mediaErr ? ` (código ${mediaErr})` : ''}.`;
        } else diag.textContent = 'Detalle: el proveedor responde; la conexión se cortó mientras se veía.';
      } catch (e) {
        diag.textContent = 'Detalle: no se pudo contactar con tu hosting (' + e.message + ').';
      }
    })();
    errBox.style.display = 'block';
    showControls();
  }

  // ------------------------------------------------------------ controls

  /** MKV subtitles move up while the controls are on screen, so they never sit under them. */
  function liftSubs(on) {
    if (!isMoviEl(video)) return;
    try { video.player?.setSubtitleControlsPadding?.(on ? 96 : 0); } catch { /* empty */ }
  }

  function showControls() {
    root.classList.remove('hidectl', 'idle');
    mosca.classList.remove('on');
    liftSubs(true);
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      if (panel || overBar || dragging || (!isLive() && video.paused) || errBox.style.display !== 'none') return;
      root.classList.add('hidectl', 'idle');
      liftSubs(false);
      if (isLive() && prefs.watermark) mosca.classList.add('on');
    }, 2800);
  }

  function renderControls() {
    const it = item();
    const live = isLive();
    root.classList.toggle('islive', live);
    if (live) {
      const c = it.channel;
      titleBox.replaceChildren(
        h('div.plogo', null, logoTile(c.logo, c.name.slice(0, 2).toUpperCase())),
        h('div.ptx', null,
          h('div.a.clamp1', null, c.num ? h('span.num', null, String(c.num)) : null, c.name),
          h('div.b', null, liveTag)));
      row.replaceChildren(...[items.length > 1 ? prevCh : null, playBtn, items.length > 1 ? nextCh : null, vol, now, listBtn, audioBtn, ccBtn, fsBtn].filter(Boolean));
      const fill = (nn) => {
        now.replaceChildren();
        if (nn?.now) {
          now.append(h('div.t.clamp1', null, h('span.tm', null, `${fmt.clock(nn.now.s)} – ${fmt.clock(nn.now.e)}`), nn.now.t));
          now.append(progressBar((Date.now() - nn.now.s) / (nn.now.e - nn.now.s)));
          if (nn.next) now.append(h('div.n.clamp1', null, `Después ${fmt.clock(nn.next.s)} · ${nn.next.t}`));
        } else now.append(h('div.n', null, 'Sin información de programación'));
      };
      const local = nowNext(c);
      fill(local);
      if (!local) shortEpg(c).then((nn) => { if (item() === it) fill(nn); });
    } else {
      titleBox.replaceChildren(h('div.ptx', null,
        h('div.a.clamp1', null, it.kind === 'episode' && it.seriesTitle ? it.seriesTitle : it.title, h('span.engchip', { title: 'Reproductor en uso (se cambia en Ajustes) y por dónde llega el vídeo' }, `${ENGINE_NAMES[currentEngine]} · ${usingDirect ? 'directo' : 'por tu hosting'}`)),
        it.kind === 'episode' ? h('div.b.clamp1', null, [it.subtitle, it.title].filter(Boolean).join('  ·  ')) : it.subtitle ? h('div.b', null, it.subtitle) : null));
      const hasNext = it.kind === 'episode' && index < items.length - 1;
      row.replaceChildren(...[back10, playBtn, fwd10, vol, time, h('div.pgrow'), hasNext ? nextEp : null, audioBtn, ccBtn, fsBtn].filter(Boolean));
      renderTime();
    }
    updateAudioBtn();
  }

  function renderTime() {
    const dur = video.duration || 0;
    const pos = scrubTarget ?? video.currentTime;
    const f = dur ? pos / dur : 0;
    seek.querySelector('.played').style.width = f * 100 + '%';
    seek.querySelector('.knob').style.left = f * 100 + '%';
    let buf = 0;
    try {
      const b = video.buffered;
      for (let i = 0; i < b.length; i++) if (b.start(i) <= video.currentTime + 1) buf = Math.max(buf, b.end(i));
    } catch { /* empty */ }
    seek.querySelector('.buf').style.width = dur ? Math.min(100, (buf / dur) * 100) + '%' : '0';
    time.textContent = dur && isFinite(dur) ? `${fmt.duration(pos * 1000)} / ${fmt.duration(dur * 1000)}` : fmt.duration(pos * 1000);
  }

  function showFlash(name, text) {
    flash.innerHTML = ICONS[name] || '';
    if (text) flash.append(h('span', null, text));
    flash.classList.remove('on');
    void flash.offsetWidth;
    flash.classList.add('on');
  }

  function togglePlay(fromClick) {
    if (video.paused) {
      video.play().catch(() => {});
      if (fromClick) showFlash('play');
    } else {
      video.pause();
      if (fromClick) showFlash('pause');
    }
  }

  function seekBy(delta) {
    if (!video.duration || !isFinite(video.duration)) return;
    const base = scrubTarget ?? video.currentTime;
    scrubTarget = Math.max(0, Math.min(video.duration - 1, base + delta));
    renderTime();
    showFlash(delta < 0 ? 'back10' : 'fwd10');
    clearTimeout(scrubTimer);
    scrubTimer = setTimeout(() => {
      markSeek();
      video.currentTime = scrubTarget;
      scrubTarget = null;
    }, 450);
  }

  function goTo(i) {
    const n = items.length;
    const target = ((i % n) + n) % n;
    if (target === index) return;
    saveNow();
    if (isLive()) grab(video, item().id);
    index = target;
    forceProxy = false;
    nextUp.style.display = 'none';
    load(0);
    if (panel === 'channels') openChannels();
  }

  // ------------------------------------------------------------ next episode

  function checkNextUp() {
    const it = item();
    const next = it.kind === 'episode' ? items[index + 1] : null;
    const remaining = (video.duration || 0) - video.currentTime;
    const show = next && video.duration > 120 && remaining > 0 && remaining <= 30 && nextDismissed !== index && !panel;
    if (!show) {
      if (nextUp.style.display !== 'none') nextUp.style.display = 'none';
      return;
    }
    if (nextUp.style.display === 'none') {
      const btn = h('button.pill.primary.f', { tabIndex: -1, type: 'button' }, 'Ver ahora');
      btn.addEventListener('click', () => goTo(index + 1));
      const no = h('button.pill.f', { tabIndex: -1, type: 'button' }, 'Seguir viendo');
      no.addEventListener('click', () => {
        nextDismissed = index;
        nextUp.style.display = 'none';
      });
      nextUp.replaceChildren(
        picture(next.image, 'thumb'),
        h('div.nx', null,
          h('div.t-caption.accent', null, 'SIGUIENTE EPISODIO'),
          h('div.nt.clamp2', null, [next.subtitle, next.title].filter(Boolean).join('  ·  ')),
          h('div.nb', null, btn, no),
          h('div.t-caption.dim.secs', null, '')),
      );
      nextUp.style.display = 'flex';
      if (document.body.classList.contains('kbd')) focus(btn);
    }
    const secs = nextUp.querySelector('.secs');
    if (secs) secs.textContent = `Empieza en ${Math.ceil(remaining)} s`;
  }

  // ------------------------------------------------------------ panels

  function openChannels() {
    panel = 'channels';
    root.classList.remove('hidectl', 'idle');
    const list = h('div.optlist');
    const filter = h('input.f', { type: 'search', placeholder: 'Buscar canal', autocomplete: 'off', spellcheck: false });
    let matches = [], shown = 0;
    const row = (i) => {
      const it = items[i];
      const nn = it.channel ? nowNext(it.channel) : null;
      const r = h('div.opt.ch.f' + (i === index ? '.cur' : ''), { tabIndex: -1, dataset: { i: String(i) } },
        h('span.num', null, it.channel?.num ? String(it.channel.num) : ''),
        h('div.lg', null, logoTile(it.channel?.logo, '', { hideMissing: true })),
        h('div.tx', null, h('div.clamp1', null, it.title), nn?.now ? h('div.p.clamp1', null, nn.now.t) : null));
      r.addEventListener('click', () => goTo(i));
      return r;
    };
    const more = () => {
      const end = Math.min(matches.length, shown + 80);
      const frag = document.createDocumentFragment();
      for (; shown < end; shown++) frag.append(row(matches[shown]));
      list.append(frag);
    };
    const draw = () => {
      const k = filter.value.trim().toLowerCase();
      matches = [];
      items.forEach((it, i) => {
        if (!k || it.title.toLowerCase().includes(k) || String(it.channel?.num) === k) matches.push(i);
      });
      // Start the list around the channel playing.
      const at = k ? 0 : Math.max(0, matches.indexOf(index) - 30);
      matches = k ? matches : matches.slice(at).concat(matches.slice(0, at));
      shown = 0;
      list.replaceChildren();
      more();
      if (!matches.length) list.append(h('div.dim', { style: { padding: '12px 14px' } }, 'Ningún canal coincide.'));
    };
    filter.addEventListener('input', debounce(draw, 150));
    list.addEventListener('scroll', () => {
      if (list.scrollTop + list.clientHeight > list.scrollHeight - 400) more();
    }, { passive: true });
    leftPanel.replaceChildren(
      h('div.sideh', null, h('h3', null, 'Canales'), pbtn('close', 'Cerrar', () => closePanel())),
      h('label.filterbox', null, h('span.ic', { html: ICONS.search }), filter),
      list);
    draw();
    leftPanel.classList.remove('closed');
    rightPanel.classList.add('closed');
    setTimeout(() => {
      const cur = list.querySelector('.opt.cur') || list.querySelector('.opt');
      if (cur) {
        cur.scrollIntoView({ block: 'center' });
        if (document.body.classList.contains('kbd')) focus(cur, { instant: true });
      }
    }, 30);
  }

  function panelOpt(label, on, fn) {
    const o = h('div.opt.f' + (on ? '.cur' : ''), { tabIndex: -1 }, h('span.ck', null, on ? '✓' : ''), h('span.clamp1', null, label));
    o.addEventListener('click', fn);
    return o;
  }
  function showPanel(title, content) {
    rightPanel.replaceChildren(h('div.sideh', null, h('h3', null, title), pbtn('close', 'Cerrar', () => closePanel())), content);
    rightPanel.classList.remove('closed');
    leftPanel.classList.add('closed');
    root.classList.remove('hidectl', 'idle');
    if (document.body.classList.contains('kbd')) setTimeout(() => focus(rightPanel.querySelector('.opt.cur') || rightPanel.querySelector('.f'), { instant: true }), 30);
  }

  /** Audio tracks the browser lets us choose (HLS streams, or files in Safari). */
  function audioTracks() {
    if (isAbEl(video)) {
      const list = video.audioTracks || [];
      const cur = abAudio ?? list[0]?.id;
      return list.map((t, i) => ({
        label: trackLabel(t, i, 'Pista'), on: t.id === cur,
        pick: () => {
          const pr = video.setAudioTrack(t.id);
          if (pr && pr.catch) pr.catch(() => {});
          abAudio = t.id;
          if (t.language) prefs.audioLang = t.language.toLowerCase();
        },
      }));
    }
    if (isMoviEl(video)) {
      const p = video.player;
      const list = (p && p.getAudioTracks && p.getAudioTracks()) || [];
      let cur = moviAudio;
      if (cur == null) {
        const nat = video.audioTracks ? [...video.audioTracks] : [];
        const i = nat.findIndex((t) => t.enabled);
        cur = list[Math.max(0, i)]?.id;
      }
      return list.map((t, i) => ({
        label: trackLabel(t, i, 'Pista'), on: t.id === cur,
        pick: () => {
          p.selectAudioTrack(t.id);
          moviAudio = t.id;
          if (t.language) prefs.audioLang = t.language.toLowerCase();
        },
      }));
    }
    const hls = engines.hls;
    if (hls && hls.audioTracks && hls.audioTracks.length) {
      return hls.audioTracks.map((t, i) => ({ label: t.name || t.lang || `Pista ${i + 1}`, on: hls.audioTrack === i, pick: () => { hls.audioTrack = i; } }));
    }
    const native = video.audioTracks;
    if (native && native.length) {
      return [...native].map((t, i) => ({ label: t.label || t.language || `Pista ${i + 1}`, on: t.enabled, pick: () => { [...native].forEach((x, j) => (x.enabled = j === i)); } }));
    }
    return [];
  }
  function updateAudioBtn() {
    audioBtn.classList.toggle('dim', audioTracks().length < 2);
  }

  /** Subtitles of an MKV (read by the web's MKV player). */
  function openMoviSubs(content) {
    const p = video.player;
    const subs = p.getSubtitleTracks?.() || [];
    content.append(h('h4', null, 'Subtítulos'));
    content.append(panelOpt('Desactivados', moviSub == null, async () => {
      await p.selectSubtitleTrack(null);
      moviSub = null;
      prefs.subLang = '';
      openSubs();
    }));
    subs.forEach((t, i) => content.append(panelOpt(trackLabel(t, i, 'Subtítulos'), t.id === moviSub, async () => {
      await p.selectSubtitleTrack(t.id);
      moviSub = t.id;
      if (t.language) prefs.subLang = t.language.toLowerCase();
      openSubs();
    })));
    if (!subs.length) content.append(h('div.dim.note', null, 'Este archivo no trae subtítulos.'));
    subStyleOptions(content);
    showPanel('Subtítulos', content);
  }

  /** Subtitles of the file in avbridge. */
  function openAbSubs(content) {
    const subs = video.subtitleTracks || [];
    content.append(h('h4', null, 'Subtítulos'));
    content.append(panelOpt('Desactivados', abSub == null, async () => {
      try { await video.setSubtitleTrack(null); } catch { /* empty */ }
      abSub = null;
      openSubs();
    }));
    subs.forEach((t, i) => content.append(panelOpt(trackLabel(t, i, 'Subtítulos'), t.id === abSub, async () => {
      try { await video.setSubtitleTrack(t.id); } catch { /* empty */ }
      abSub = t.id;
      openSubs();
    })));
    if (!subs.length) content.append(h('div.dim.note', null, 'avbridge no encuentra subtítulos en este archivo.'));
    subStyleOptions(content);
    showPanel('Subtítulos', content);
  }

  function subStyleOptions(content) {
    content.append(h('h4', null, 'Tamaño'));
    const sizes = h('div.chipset');
    ['XS', 'S', 'M', 'L', 'XL'].forEach((l, i) => {
      const b = h('button.chipbtn.f' + (prefs.subtitleSize === i ? '.on' : ''), { type: 'button', tabIndex: -1 }, l);
      b.addEventListener('click', () => { prefs.subtitleSize = i; applySubSize(); if (isMoviEl(video)) subAttrs(video); openSubs(); });
      sizes.append(b);
    });
    content.append(sizes);
    content.append(h('h4', null, 'Estilo'));
    const style = (bg) => () => {
      prefs.subtitleBackground = bg;
      document.body.classList.toggle('subbg', bg);
      if (isMoviEl(video)) {
        subAttrs(video);
        styleMoviSubs(video);
      }
      openSubs();
    };
    content.append(panelOpt('Sin fondo', !prefs.subtitleBackground, style(false)));
    content.append(panelOpt('Fondo', !!prefs.subtitleBackground, style(true)));
  }

  function openAudio() {
    panel = 'audio';
    const content = h('div.optlist');
    const list = audioTracks();
    if (list.length > 1) {
      content.append(h('h4', null, 'Idioma del audio'));
      list.forEach((t) => content.append(panelOpt(t.label, t.on, () => { t.pick(); openAudio(); })));
    } else {
      const isFile = !isLive() && !engines.hls && !isCustom(video);
      content.append(h('div.dim.note', null, isCustom(video) ? 'Este archivo trae un solo idioma de audio.' : isFile
        ? 'El navegador no deja elegir el idioma de este archivo. Con archivos MP4, Safari sí muestra los idiomas; los MKV solo cambian de idioma en la app de la tele.'
        : 'Esta emisión trae un solo idioma de audio.'));
    }
    showPanel('Audio', content);
  }

  function openSubs() {
    panel = 'subs';
    const content = h('div.optlist');
    if (isMoviEl(video) && video.player) return openMoviSubs(content);
    if (isAbEl(video)) return openAbSubs(content);
    const tracks = [...(video.textTracks || [])].filter((t) => t.kind === 'subtitles' || t.kind === 'captions');
    content.append(h('h4', null, 'Subtítulos'));
    content.append(panelOpt('Desactivados', !tracks.some((t) => t.mode === 'showing'), () => { tracks.forEach((t) => (t.mode = 'disabled')); openSubs(); }));
    tracks.forEach((t, i) => content.append(panelOpt(t.label || t.language || `Subtítulos ${i + 1}`, t.mode === 'showing', () => {
      tracks.forEach((x) => (x.mode = 'disabled'));
      t.mode = 'showing';
      openSubs();
    })));
    if (!tracks.length) {
      const isFile = !isLive() && !engines.hls;
      content.append(h('div.dim.note', null, isFile
        ? 'Los subtítulos que van dentro del archivo no se pueden mostrar en Chrome, Edge ni Firefox. Con archivos MP4, Safari sí los muestra; con MKV, solo la app de la tele.'
        : 'Esta emisión no trae subtítulos que el navegador pueda mostrar.'));
    }
    subStyleOptions(content);
    showPanel('Subtítulos', content);
  }

  function closePanel() {
    panel = null;
    leftPanel.classList.add('closed');
    rightPanel.classList.add('closed');
    root.focus({ preventScroll: true });
    showControls();
  }

  // ------------------------------------------------------------ keys

  root.tabIndex = -1;
  const offKey = onKey((e) => {
    if (closed) return false;
    const k = e.key;
    const typing = document.activeElement && document.activeElement.tagName === 'INPUT';
    if (k === 'Escape' || (k === 'Backspace' && !typing) || k === 'BrowserBack' || k === 'GoBack') {
      if (panel) closePanel();
      else if (nextUp.style.display !== 'none') {
        nextDismissed = index;
        nextUp.style.display = 'none';
        root.focus();
      } else if (digits) {
        digits = '';
        digitsEl.style.display = 'none';
      } else close();
      return true;
    }
    if (typing) return false;
    if (panel) return false; // arrows move inside the panel
    if (nextUp.style.display !== 'none' && (k === 'Enter' || k === ' ')) return false;
    // A focused control button: Enter presses it.
    if (k === 'Enter' && document.activeElement && document.activeElement.closest('.pbottom, .ptop, .err')) return false;
    showControls();
    const live = isLive();
    switch (k) {
      case 'Enter':
        if (errBox.style.display !== 'none') load(live ? 0 : lastPos * 1000);
        else if (live) openChannels();
        else togglePlay(true);
        return true;
      case ' ':
      case 'k':
      case 'MediaPlayPause':
        togglePlay(true);
        return true;
      case 'ArrowLeft':
        if (live) openChannels();
        else seekBy(-(e.repeat ? 30 : 10));
        return true;
      case 'ArrowRight':
        if (live) openSubs();
        else seekBy(e.repeat ? 30 : 10);
        return true;
      case 'ArrowUp':
        if (live) goTo(index - 1);
        else changeVolume(0.05);
        return true;
      case 'ArrowDown':
        if (live) goTo(index + 1);
        else changeVolume(-0.05);
        return true;
      case 'PageUp':
      case 'ChannelUp':
        goTo(index + 1);
        return true;
      case 'PageDown':
      case 'ChannelDown':
        goTo(index - 1);
        return true;
      case 'f':
      case 'F':
        toggleFullscreen();
        return true;
      case 'm':
      case 'M':
        setMuted(!video.muted);
        showFlash(video.muted ? 'mute' : 'vol');
        return true;
      case 'c':
      case 'C':
        if (panel === 'subs') closePanel();
        else openSubs();
        return true;
      case 'a':
      case 'A':
        if (panel === 'audio') closePanel();
        else openAudio();
        return true;
      case 'l':
      case 'L':
        if (live) openChannels();
        return true;
      default:
        if (live && /^[0-9]$/.test(k)) {
          digits = (digits + k).slice(-4);
          digitsEl.textContent = digits;
          digitsEl.style.display = 'block';
          clearTimeout(digitsTimer);
          digitsTimer = setTimeout(() => {
            const n = parseInt(digits, 10);
            digits = '';
            digitsEl.style.display = 'none';
            const i = items.findIndex((x) => x.channel?.num === n);
            if (i >= 0) goTo(i);
            else toast(`No hay canal ${n} en esta lista`);
          }, 1300);
          return true;
        }
    }
    return false;
  });

  // ------------------------------------------------------------ progress

  function saveNow() {
    const it = item();
    if (it.kind === 'live' || !video.duration || !isFinite(video.duration)) return;
    saveProgress({
      uid: `${it.kind}:${it.id}`, kind: it.kind, id: it.id, title: it.title, subtitle: it.subtitle, image: it.image, url: it.url,
      pos: Math.floor(video.currentTime * 1000), dur: Math.floor(video.duration * 1000),
      seriesId: it.seriesId, seriesTitle: it.seriesTitle, seriesImage: it.seriesImage,
      hasNext: it.kind === 'episode' && index < items.length - 1, ext: it.ext,
    });
  }
  const saveTimer = setInterval(() => { if (!video.paused) saveNow(); }, 15000);
  const onHide = () => { if (document.hidden) saveNow(); };
  document.addEventListener('visibilitychange', onHide);
  window.addEventListener('pagehide', saveNow);

  function close(silent) {
    if (closed) return;
    closed = true;
    saveNow();
    if (isLive()) grab(video, item().id);
    clearInterval(saveTimer);
    clearTimeout(capTimer);
    clearTimeout(hideTimer);
    clearTimeout(clickTimer);
    clearTimeout(stallTimer);
    clearTimeout(retryTimer);
    clearTimeout(probeTimer);
    offKey();
    document.removeEventListener('fullscreenchange', onFs);
    document.removeEventListener('visibilitychange', onHide);
    window.removeEventListener('pagehide', saveNow);
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    stopEngines();
    popLayer(root);
    root.remove();
    if (active && active.root === root) active = null;
    if (opener && opener.isConnected && opener !== document.body) opener.focus({ preventScroll: true });
    if (!silent) {
      notifyChanged();
      document.dispatchEvent(new Event('hi:playerclosed'));
    }
  }

  const api = { root, close };
  load(startMs);
  setTimeout(() => root.focus({ preventScroll: true }), 30);
  return api;
}

export { S };

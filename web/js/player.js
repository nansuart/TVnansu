// Full-screen player: live TV with zapping and the nansu tv watermark, movies and
// episodes with seeking, progress and "next episode". Works with the mouse
// (controls appear when it moves) and with the keyboard (shortcuts as on the TV).
import { h, fmt, toast, debounce } from './util.js';
import { pushLayer, popLayer, onKey, focus } from './nav.js';
import { attachStream, grab, logoTile, progressBar, Preview, ICONS, picture } from './ui.js';
import { prefs, markChannelWatched, saveProgress, S, liveUrl, nowNext, shortEpg, notifyChanged } from './data.js';

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
  let panel = null; // 'channels' | 'tracks'
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
  let overBar = false;
  let dragging = false;
  const item = () => items[index];
  const isLive = () => item().kind === 'live';

  const video = h('video', { playsInline: true, autoplay: true, preload: 'auto' });
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
  const ccBtn = pbtn('cc', 'Subtítulos y audio (C)', () => (panel === 'tracks' ? closePanel() : openTracks()));
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
    if (scrubTarget != null) video.currentTime = scrubTarget;
    scrubTarget = null;
    renderTime();
  };
  seek.addEventListener('pointerup', endDrag);
  seek.addEventListener('pointercancel', endDrag);

  // ------------------------------------------------------------ video events

  video.addEventListener('waiting', () => {
    spin.style.display = 'flex';
    clearTimeout(stallTimer);
    stallTimer = setTimeout(() => { if (!video.paused) recover(); }, 15000);
  });
  video.addEventListener('playing', () => {
    spin.style.display = 'none';
    errBox.style.display = 'none';
    clearTimeout(stallTimer);
    everPlayed = true;
  });
  video.addEventListener('canplay', () => { spin.style.display = 'none'; clearTimeout(stallTimer); });
  video.addEventListener('timeupdate', () => {
    if (video.currentTime > 0.5) {
      lastPos = video.currentTime;
      if (retries && everPlayed && video.currentTime > 0) retries = Math.max(0, retries - 0.01);
    }
    if (!isLive()) renderTime();
    checkNextUp();
  });
  video.addEventListener('progress', () => { if (!isLive()) renderTime(); });
  video.addEventListener('play', () => { setIcon(playBtn, 'pause'); playBtn.title = 'Pausa (Espacio)'; showControls(); });
  video.addEventListener('pause', () => { setIcon(playBtn, 'play'); playBtn.title = 'Reproducir (Espacio)'; showControls(); });
  video.addEventListener('volumechange', () => setIcon(muteBtn, video.muted || video.volume === 0 ? 'mute' : 'vol'));
  video.addEventListener('ended', () => {
    if (item().kind === 'episode' && index < items.length - 1) goTo(index + 1);
    else if (!isLive()) close();
  });
  video.addEventListener('error', () => onFatal());

  // Click: pause/play (or close a panel); double click: full screen.
  let clickTimer = null;
  video.addEventListener('click', () => {
    if (panel) {
      closePanel();
      return;
    }
    clearTimeout(clickTimer);
    clickTimer = setTimeout(() => togglePlay(true), 220);
  });
  video.addEventListener('dblclick', () => {
    clearTimeout(clickTimer);
    toggleFullscreen();
  });
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
    engines = attachStream(video, url, it.kind === 'live', null, onFatal);
    if (seekMs > 0) {
      const once = () => {
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
    try { engines.hls && engines.hls.destroy(); } catch { /* empty */ }
    try { engines.ts && (engines.ts.unload(), engines.ts.detachMediaElement(), engines.ts.destroy()); } catch { /* empty */ }
    engines = { hls: null, ts: null };
    video.removeAttribute('src');
    try { video.load(); } catch { /* empty */ }
  }

  /** The video stopped or failed after playing: reconnect where it was, a few times. */
  function recover() {
    if (closed) return;
    clearTimeout(stallTimer);
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
      hint = 'Este archivo (MKV/AVI) puede no reproducirse en el navegador. En la app de la tele sí funciona.';
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
    errBox.replaceChildren(h('div.et', null, title), h('div.dim', null, hint), btns);
    errBox.style.display = 'block';
    showControls();
  }

  // ------------------------------------------------------------ controls

  function showControls() {
    root.classList.remove('hidectl', 'idle');
    mosca.classList.remove('on');
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      if (panel || overBar || dragging || (!isLive() && video.paused) || errBox.style.display !== 'none') return;
      root.classList.add('hidectl', 'idle');
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
      row.replaceChildren(...[items.length > 1 ? prevCh : null, playBtn, items.length > 1 ? nextCh : null, vol, now, listBtn, ccBtn, fsBtn].filter(Boolean));
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
        h('div.a.clamp1', null, it.kind === 'episode' && it.seriesTitle ? it.seriesTitle : it.title),
        it.kind === 'episode' ? h('div.b.clamp1', null, [it.subtitle, it.title].filter(Boolean).join('  ·  ')) : it.subtitle ? h('div.b', null, it.subtitle) : null));
      const hasNext = it.kind === 'episode' && index < items.length - 1;
      row.replaceChildren(...[back10, playBtn, fwd10, vol, time, h('div.pgrow'), hasNext ? nextEp : null, ccBtn, fsBtn].filter(Boolean));
      renderTime();
    }
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

  function openTracks() {
    panel = 'tracks';
    root.classList.remove('hidectl', 'idle');
    const content = h('div.optlist');
    const opt = (label, on, fn) => {
      const o = h('div.opt.f' + (on ? '.cur' : ''), { tabIndex: -1 }, h('span.ck', null, on ? '✓' : ''), h('span.clamp1', null, label));
      o.addEventListener('click', fn);
      return o;
    };
    const hls = engines.hls;
    const native = video.audioTracks; // only some browsers (Safari) expose a file's audio tracks
    const isFile = !isLive() && !hls;
    if (hls && hls.audioTracks && hls.audioTracks.length > 1) {
      content.append(h('h4', null, 'Audio'));
      hls.audioTracks.forEach((t, i) => content.append(opt(t.name || t.lang || `Pista ${i + 1}`, hls.audioTrack === i, () => { hls.audioTrack = i; openTracks(); })));
    } else if (!hls && native && native.length > 1) {
      content.append(h('h4', null, 'Audio'));
      [...native].forEach((t, i) => content.append(opt(t.label || t.language || `Pista ${i + 1}`, t.enabled, () => {
        [...native].forEach((x, j) => (x.enabled = j === i));
        openTracks();
      })));
    }
    const tracks = [...(video.textTracks || [])].filter((t) => t.kind === 'subtitles' || t.kind === 'captions');
    content.append(h('h4', null, 'Subtítulos'));
    content.append(opt('Desactivados', !tracks.some((t) => t.mode === 'showing'), () => { tracks.forEach((t) => (t.mode = 'disabled')); openTracks(); }));
    tracks.forEach((t, i) => content.append(opt(t.label || t.language || `Subtítulos ${i + 1}`, t.mode === 'showing', () => {
      tracks.forEach((x) => (x.mode = 'disabled'));
      t.mode = 'showing';
      openTracks();
    })));
    if (!tracks.length) {
      content.append(h('div.dim.note', null, isFile
        ? 'Los subtítulos e idiomas que van dentro del archivo de vídeo (MKV o MP4) no se pueden elegir desde el navegador: Chrome, Edge y Firefox no los muestran. En la app de la tele sí.'
        : 'Esta emisión no trae subtítulos que el navegador pueda mostrar.'));
    }
    content.append(h('h4', null, 'Tamaño'));
    const sizes = h('div.chipset');
    ['XS', 'S', 'M', 'L', 'XL'].forEach((l, i) => {
      const b = h('button.chipbtn.f' + (prefs.subtitleSize === i ? '.on' : ''), { type: 'button', tabIndex: -1 }, l);
      b.addEventListener('click', () => { prefs.subtitleSize = i; applySubSize(); openTracks(); });
      sizes.append(b);
    });
    content.append(sizes);
    content.append(h('h4', null, 'Estilo'));
    content.append(opt('Sin fondo', !prefs.subtitleBackground, () => { prefs.subtitleBackground = false; document.body.classList.remove('subbg'); openTracks(); }));
    content.append(opt('Fondo', !!prefs.subtitleBackground, () => { prefs.subtitleBackground = true; document.body.classList.add('subbg'); openTracks(); }));
    rightPanel.replaceChildren(h('div.sideh', null, h('h3', null, 'Subtítulos y audio'), pbtn('close', 'Cerrar', () => closePanel())), content);
    rightPanel.classList.remove('closed');
    leftPanel.classList.add('closed');
    if (document.body.classList.contains('kbd')) setTimeout(() => focus(rightPanel.querySelector('.opt.cur') || rightPanel.querySelector('.f'), { instant: true }), 30);
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
        if (live) openTracks();
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
        openTracks();
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
      document.dispatchEvent(new Event('nt:playerclosed'));
    }
  }

  const api = { root, close };
  load(startMs);
  setTimeout(() => root.focus({ preventScroll: true }), 30);
  return api;
}

export { S };

// Full-screen player: live TV with zapping and the nansu tv watermark, movies and
// episodes with seeking, progress and "next episode".
import { h, fmt, toast } from './util.js';
import { pushLayer, popLayer, onKey, focus } from './nav.js';
import { attachStream, grab, logoTile, progressBar, Preview } from './ui.js';
import { prefs, markChannelWatched, saveProgress, S, liveUrl, nowNext, shortEpg, notifyChanged } from './data.js';

let active = null;

export function isPlaying() {
  return !!active;
}

/**
 * items: [{kind:'live'|'movie'|'episode', id, title, subtitle, image, url, channel, num,
 *          seriesId, seriesTitle, seriesImage}]
 */
export function play(items, index = 0, startMs = 0) {
  if (!items.length) return;
  if (active) active.close(true);
  Preview.stop();
  active = createPlayer(items, Math.max(0, Math.min(index, items.length - 1)), startMs);
}

function createPlayer(items, startIndex, startMs) {
  let index = startIndex;
  let engines = { hls: null, ts: null };
  let triedTs = false;
  let controlsTimer = null;
  let panel = null; // 'channels' | 'tracks'
  let digits = '';
  let digitsTimer = null;
  let saveTimer = null;
  let capTimer = null;
  let scrubTarget = null;
  let scrubTimer = null;
  let nextDismissed = -1;
  let closed = false;
  const item = () => items[index];
  const isLive = () => item().kind === 'live';

  const video = h('video', { playsInline: true, autoplay: true, preload: 'auto' });
  const spin = h('div.spinwrap', null, h('div.spinner'));
  const errBox = h('div.err', { style: { display: 'none' } });
  const ctrl = h('div.ctrl');
  const mosca = h('div.mosca');
  const digitsEl = h('div.digits', { style: { display: 'none' } });
  const leftPanel = h('div.side.left.closed', { dataset: { group: 'chlist' } });
  const rightPanel = h('div.side.right.closed', { dataset: { group: 'tracks' } });
  const nextUp = h('div.nextup', { style: { display: 'none' } });
  const root = h('div.player', null, video, spin, mosca, ctrl, digitsEl, nextUp, errBox, leftPanel, rightPanel);
  document.body.append(root);
  document.body.classList.toggle('subbg', !!prefs.subtitleBackground);
  applySubSize();
  pushLayer(root);

  video.addEventListener('waiting', () => (spin.style.display = 'flex'));
  video.addEventListener('playing', () => {
    spin.style.display = 'none';
    errBox.style.display = 'none';
  });
  video.addEventListener('canplay', () => (spin.style.display = 'none'));
  video.addEventListener('timeupdate', () => {
    if (!isLive()) renderVodBar();
    checkNextUp();
  });
  video.addEventListener('ended', () => {
    if (item().kind === 'episode' && index < items.length - 1) goTo(index + 1);
    else if (!isLive()) close();
  });
  video.addEventListener('error', () => onFatal());
  video.addEventListener('click', () => {
    if (!isLive()) togglePlay();
    showControls();
  });
  root.addEventListener('mousemove', () => showControls());

  function applySubSize() {
    const sizes = [1.9, 2.2, 2.5, 2.9, 3.4];
    let st = document.getElementById('cue-size');
    if (!st) {
      st = h('style');
      st.id = 'cue-size';
      document.head.append(st);
    }
    st.textContent = `video::cue{font-size:${sizes[prefs.subtitleSize] || 1.9}rem}`;
  }

  function load(seekMs = 0) {
    stopEngines();
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

  function onFatal() {
    if (closed) return;
    const it = item();
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
    const vodHint = it.kind !== 'live' && /\.(mkv|avi)(\?|$)/i.test(it.url || '')
      ? 'Este formato (MKV/AVI) puede no reproducirse en el navegador. En la app de la tele sí funciona.'
      : 'Puede que la emisión esté caída o que haya demasiadas conexiones a la vez.';
    errBox.replaceChildren(h('div', null, 'No se pudo reproducir'), h('div.dim', { style: { fontSize: '1.25rem', marginTop: '.8rem' } }, vodHint), h('div.faint', { style: { fontSize: '1.2rem', marginTop: '.8rem' } }, 'Pulsa OK para reintentar'));
    errBox.style.display = 'block';
  }

  // ------------------------------------------------------------ controls

  function showControls() {
    ctrl.classList.remove('hide');
    mosca.classList.remove('on');
    clearTimeout(controlsTimer);
    controlsTimer = setTimeout(() => {
      if (panel || (!isLive() && video.paused)) return;
      ctrl.classList.add('hide');
      if (isLive() && prefs.watermark) mosca.classList.add('on');
    }, isLive() ? 5000 : 4500);
  }

  function renderControls() {
    const it = item();
    if (it.kind === 'live') {
      const c = it.channel;
      const info = h('div', { style: { flex: '1', minWidth: '0' } });
      const titleRow = h('div', { style: { display: 'flex', alignItems: 'center', gap: '1.2rem' } },
        c.num ? h('span.t-title.accent', null, String(c.num)) : null,
        h('span.t-title.clamp1', null, c.name),
        h('i.dot'));
      info.append(titleRow);
      const epgLine = h('div', { style: { marginTop: '.8rem' } });
      info.append(epgLine);
      info.append(h('div.hint', { style: { marginTop: '1rem' } }, '▲ ▼ cambiar canal   ·   OK lista de canales   ·   ▶ subtítulos   ·   números para ir a un canal   ·   Esc salir'));
      ctrl.replaceChildren(h('div.livebar', null, h('div.lg', null, logoTile(c.logo, c.name)), info));
      const fill = (nn) => {
        epgLine.replaceChildren();
        if (nn?.now) {
          epgLine.append(h('div.t-section.clamp1', null, `${fmt.clock(nn.now.s)} – ${fmt.clock(nn.now.e)}   ${nn.now.t}`));
          const bar = progressBar((Date.now() - nn.now.s) / (nn.now.e - nn.now.s));
          bar.style.width = '70%';
          bar.style.marginTop = '.8rem';
          epgLine.append(bar);
        } else epgLine.append(h('div.dim', null, 'Sin información de programación'));
        if (nn?.next) epgLine.append(h('div.t-label.dim.clamp1', { style: { marginTop: '.8rem' } }, `Después  ${fmt.clock(nn.next.s)}   ${nn.next.t}`));
      };
      const local = nowNext(c);
      fill(local);
      if (!local) shortEpg(c).then((nn) => { if (item() === it) fill(nn); });
    } else {
      const top = it.kind === 'episode' && it.seriesTitle ? h('div.t-label.dim', null, `${it.seriesTitle}  ·  ${it.subtitle || ''}`) : null;
      const bar = h('div.bar', null, h('i'));
      bar.addEventListener('click', (e) => {
        const r = bar.getBoundingClientRect();
        if (video.duration) video.currentTime = ((e.clientX - r.left) / r.width) * video.duration;
      });
      ctrl.replaceChildren(...[
        h('div', { style: { paddingTop: '8rem' } }),
        top,
        h('div', { style: { display: 'flex', alignItems: 'center', gap: '1.4rem' } }, h('span.t-title.playstate', null, '❚❚'), h('span.t-title.clamp1', null, it.title)),
        bar,
        h('div.times', null, h('span.cur', null, '0:00'), h('span.hint', null, '◀ ▶ avanzar   ·   OK pausa   ·   ▲ subtítulos   ·   Esc salir'), h('span.left.dim', null, '')),
      ].filter(Boolean));
      renderVodBar();
    }
  }

  function renderVodBar() {
    const bar = ctrl.querySelector('.bar i');
    if (!bar) return;
    const dur = video.duration || 0;
    const pos = scrubTarget ?? video.currentTime;
    bar.style.width = dur ? (pos / dur) * 100 + '%' : '0';
    const cur = ctrl.querySelector('.cur');
    if (cur) {
      cur.textContent = fmt.duration(pos * 1000);
      cur.style.color = scrubTarget != null ? 'var(--accent)' : '';
    }
    const left = ctrl.querySelector('.left');
    if (left) left.textContent = dur ? '-' + fmt.duration((dur - pos) * 1000) : '';
    const ps = ctrl.querySelector('.playstate');
    if (ps) ps.textContent = video.paused ? '▶' : '❚❚';
  }

  function togglePlay() {
    if (video.paused) video.play().catch(() => {});
    else video.pause();
    renderVodBar();
  }

  function scrub(delta) {
    if (!video.duration) return;
    const base = scrubTarget ?? video.currentTime;
    scrubTarget = Math.max(0, Math.min(video.duration - 1, base + delta));
    renderVodBar();
    clearTimeout(scrubTimer);
    scrubTimer = setTimeout(() => {
      video.currentTime = scrubTarget;
      scrubTarget = null;
    }, 650);
  }

  function goTo(i) {
    const n = items.length;
    const target = ((i % n) + n) % n;
    if (target === index) return;
    saveNow();
    if (isLive()) grab(video, item().id);
    index = target;
    load(0);
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
      const btn = h('button.pill.f', { tabIndex: -1 }, '▶  Ver ahora');
      btn.addEventListener('click', () => goTo(index + 1));
      nextUp.replaceChildren(
        h('img', { src: next.image || '' }),
        h('div', { style: { flex: '1' } },
          h('div.t-caption.accent', null, 'SIGUIENTE EPISODIO'),
          h('div.t-label.clamp2', { style: { margin: '.4rem 0 1.2rem' } }, [next.subtitle, next.title].filter(Boolean).join('  ·  ')),
          btn,
          h('div.t-caption.dim.secs', { style: { marginTop: '.8rem' } }, '')),
      );
      nextUp.style.display = 'flex';
      focus(btn);
    }
    const secs = nextUp.querySelector('.secs');
    if (secs) secs.textContent = `Empieza en ${Math.ceil(remaining)} s  ·  Atrás para seguir viendo`;
  }

  // ------------------------------------------------------------ panels

  function openChannels() {
    panel = 'channels';
    const list = h('div');
    let cur = null;
    items.forEach((it, i) => {
      const row = h('div.opt.f' + (i === index ? '.cur' : ''), { tabIndex: -1 },
        h('span.faint', { style: { width: '3.6rem' } }, it.channel?.num ? String(it.channel.num) : ''),
        h('div', { style: { width: '5.2rem', height: '3rem', position: 'relative', borderRadius: '.6rem', overflow: 'hidden', flex: 'none' } }, logoTile(it.channel?.logo, '', { hideMissing: true })),
        h('span.clamp1', null, it.title));
      row.addEventListener('click', () => {
        closePanel();
        goTo(i);
      });
      if (i === index) cur = row;
      list.append(row);
    });
    leftPanel.replaceChildren(h('h3', null, 'Canales'), list);
    leftPanel.classList.remove('closed');
    ctrl.classList.add('hide');
    setTimeout(() => {
      focus(cur || list.firstChild, { instant: true });
      cur && cur.scrollIntoView({ block: 'center' });
    }, 30);
  }

  function openTracks() {
    panel = 'tracks';
    const content = h('div');
    const opt = (label, on, fn) => {
      const o = h('div.opt.f' + (on ? '.cur' : ''), { tabIndex: -1 }, h('span', { style: { width: '2.6rem' } }, on ? '✓' : ''), h('span.clamp1', null, label));
      o.addEventListener('click', fn);
      return o;
    };
    const hls = engines.hls;
    if (hls && hls.audioTracks && hls.audioTracks.length > 1) {
      content.append(h('h3', null, 'Audio'));
      hls.audioTracks.forEach((t, i) => content.append(opt(t.name || t.lang || `Pista ${i + 1}`, hls.audioTrack === i, () => { hls.audioTrack = i; closePanel(); })));
    }
    const tracks = [...(video.textTracks || [])].filter((t) => t.kind === 'subtitles' || t.kind === 'captions');
    content.append(h('h3', { style: { marginTop: '1.6rem' } }, 'Subtítulos'));
    content.append(opt('Desactivados', !tracks.some((t) => t.mode === 'showing'), () => { tracks.forEach((t) => (t.mode = 'disabled')); closePanel(); }));
    tracks.forEach((t, i) => content.append(opt(t.label || t.language || `Subtítulos ${i + 1}`, t.mode === 'showing', () => {
      tracks.forEach((x) => (x.mode = 'disabled'));
      t.mode = 'showing';
      closePanel();
    })));
    if (!tracks.length) content.append(h('div.dim', { style: { padding: '0 1.4rem', fontSize: '1.2rem' } }, 'Esta emisión no trae subtítulos que el navegador pueda mostrar.'));
    content.append(h('h3', { style: { marginTop: '1.6rem' } }, 'Tamaño'));
    ['XS', 'S', 'M', 'L', 'XL'].forEach((l, i) => content.append(opt(l, prefs.subtitleSize === i, () => { prefs.subtitleSize = i; applySubSize(); openTracks(); })));
    content.append(h('h3', { style: { marginTop: '1.6rem' } }, 'Estilo'));
    content.append(opt('Sin fondo', !prefs.subtitleBackground, () => { prefs.subtitleBackground = false; document.body.classList.remove('subbg'); openTracks(); }));
    content.append(opt('Fondo', !!prefs.subtitleBackground, () => { prefs.subtitleBackground = true; document.body.classList.add('subbg'); openTracks(); }));
    rightPanel.replaceChildren(content);
    rightPanel.classList.remove('closed');
    ctrl.classList.add('hide');
    setTimeout(() => focus(rightPanel.querySelector('.opt.cur') || rightPanel.querySelector('.f'), { instant: true }), 30);
  }

  function closePanel() {
    panel = null;
    leftPanel.classList.add('closed');
    rightPanel.classList.add('closed');
    root.focus();
    showControls();
  }

  // ------------------------------------------------------------ keys

  root.tabIndex = -1;
  const offKey = onKey((e) => {
    if (closed) return false;
    const k = e.key;
    if (k === 'Escape' || k === 'Backspace' || k === 'BrowserBack' || k === 'GoBack') {
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
    if (panel) return false; // spatial navigation inside the panel
    if (nextUp.style.display !== 'none' && (k === 'Enter' || k === ' ')) return false;
    showControls();
    const live = isLive();
    switch (k) {
      case 'Enter':
      case ' ':
        if (errBox.style.display !== 'none') load(live ? 0 : video.currentTime * 1000);
        else if (live) openChannels();
        else togglePlay();
        return true;
      case 'ArrowLeft':
        if (live) openChannels();
        else scrub(-(e.repeat ? 30 : 10));
        return true;
      case 'ArrowRight':
        if (live) openTracks();
        else scrub(e.repeat ? 30 : 10);
        return true;
      case 'ArrowUp':
        if (live) goTo(index - 1);
        else openTracks();
        return true;
      case 'ArrowDown':
        if (live) goTo(index + 1);
        return true;
      case 'PageUp':
      case 'ChannelUp':
        goTo(index + 1);
        return true;
      case 'PageDown':
      case 'ChannelDown':
        goTo(index - 1);
        return true;
      case 'MediaPlayPause':
        togglePlay();
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
          }, 1500);
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
  saveTimer = setInterval(() => { if (!video.paused) saveNow(); }, 15000);

  function close(silent) {
    if (closed) return;
    closed = true;
    saveNow();
    if (isLive()) grab(video, item().id);
    clearInterval(saveTimer);
    clearTimeout(capTimer);
    clearTimeout(controlsTimer);
    offKey();
    stopEngines();
    popLayer(root);
    root.remove();
    if (active && active.root === root) active = null;
    if (!silent) {
      notifyChanged();
      document.dispatchEvent(new Event('nt:playerclosed'));
    }
  }

  const api = { root, close, onClose: null };
  load(startMs);
  setTimeout(() => root.focus(), 30);
  return api;
}

export function setOnPlayerClose(fn) {
  if (active) active.onClose = fn;
}

export { S };

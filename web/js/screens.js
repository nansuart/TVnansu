// Screens: Inicio, En vivo, Películas, Series, Buscar, Ajustes, plus the pages
// they open (movie, series, guide, full category).
import { h, img, fmt, toast, cleanTitle, metaLine, debounce, put } from './util.js';
import { focus } from './nav.js';
import * as D from './data.js';
import {
  posterCard, channelCard, eventCard, themeTile, shelf, skeletonShelf, logoTile, progressBar, button, segmented,
  dots, livePreview, Preview, eventSubtitle, TINT, emptyState, loading, bindClick, coverImg,
} from './ui.js';
import { play } from './player.js';

const S = D.S;
const app = () => window.__nt;

// ------------------------------------------------------------------ actions

function playChannels(list, i) {
  const items = list.map((c) => ({ kind: 'live', id: c.id, title: c.name, image: c.logo, channel: c }));
  play(items, i);
}
function movieItem(m) {
  return { kind: 'movie', id: m.id, title: cleanTitle(m.name), subtitle: m.year, image: m.poster, url: D.movieUrl(m), ext: m.ext };
}
function openMovie(ctx, m) {
  ctx.push((c) => moviePage(c, m));
}
function openSeries(ctx, s) {
  ctx.push((c) => seriesPage(c, s));
}
function openProgress(ctx, p) {
  if (p.kind === 'movie') play([{ kind: 'movie', id: p.id, title: p.title, subtitle: p.subtitle, image: p.image, url: p.url }], 0, p.pos);
  else if (p.kind === 'series') {
    const s = D.seriesById(p.id);
    if (s) openSeries(ctx, s);
  }
}
async function toggleFav(kind, id, title, image) {
  const added = await D.toggleFavorite(kind, id, title, image);
  toast(added ? 'Añadido a Mi lista' : 'Quitado de Mi lista');
}

function movieCard(ctx, m, extra = {}) {
  return posterCard({
    title: cleanTitle(m.name), image: m.poster, rating: m.rating, subtitle: extra.subtitle ?? m.year, key: 'm' + m.id,
    onClick: () => openMovie(ctx, m),
    onLong: () => toggleFav('movie', m.id, m.name, m.poster),
  });
}
function seriesCard(ctx, s, extra = {}) {
  return posterCard({
    title: cleanTitle(s.name), image: s.poster, rating: s.rating, subtitle: extra.subtitle ?? s.year, key: 's' + s.id,
    onClick: () => openSeries(ctx, s),
    onLong: () => toggleFav('series', s.id, s.name, s.poster),
  });
}
function chCard(list, c, i, opts = {}) {
  return channelCard(c, {
    ...opts,
    onClick: () => playChannels(list, i),
    onLong: () => toggleFav('live', c.id, c.name, c.logo),
  });
}

/** Header hides while the screen is scrolled. */
function watchScroll(ctx, scroller) {
  scroller.addEventListener('scroll', () => ctx.setHeaderHidden(scroller.scrollTop > 24), { passive: true });
}

/** Rotates a carousel; stops when detached. */
function rotator(ctx, count, delayOf, onNext) {
  let t = null;
  const schedule = (i) => {
    clearTimeout(t);
    if (count < 2 || D.prefs.reduceMotion) return;
    t = setTimeout(() => onNext(), delayOf(i));
  };
  ctx.cleanup(() => clearTimeout(t));
  return { schedule, stop: () => clearTimeout(t) };
}

// ------------------------------------------------------------------ Inicio

function slidesForHome(events) {
  const sports = events.filter((e) => e.label !== 'NOTICIAS');
  const news = events.filter((e) => e.label === 'NOTICIAS');
  const lead = sports[0] || news[0];
  const second = sports[1] || (news[0] !== lead ? news[0] : null);
  const ms = D.recentMovies(6), ss = D.recentSeries(6);
  const vod = [];
  for (let i = 0; i < Math.max(ms.length, ss.length); i++) {
    if (ms[i]) vod.push({ kind: 'movie', item: ms[i] });
    if (ss[i]) vod.push({ kind: 'series', item: ss[i] });
  }
  const sorted = vod.sort((a, b) => !!b.item.backdrop - !!a.item.backdrop).slice(0, 6);
  if (lead) sorted.unshift({ kind: 'live', event: lead });
  if (second && second !== lead) sorted.splice(Math.min(3, sorted.length), 0, { kind: 'live', event: second });
  return sorted;
}

function slideKey(s) {
  return s.kind === 'live' ? 'e' + s.event.channel.id : s.kind[0] + s.item.id;
}

/** Big featured block: a live channel playing, or a movie/series with its artwork. */
function featuredBox(ctx, slides, { onOpen, onLong }) {
  const box = h('div.box', { dataset: { top: '' } });
  const stage = h('div', { style: { position: 'absolute', inset: '0' } });
  const eyebrow = h('div');
  const title = h('div.title.clamp2', { style: { fontSize: '2.6rem' } });
  const meta = h('div.meta.clamp1');
  const desc = h('div.desc.clamp2');
  const openBtn = button('', () => onOpen(current()), { cls: 'glass', primary: true });
  bindClick(openBtn, null, () => onLong(current()));
  const prev = button('‹', () => move(-1), { cls: 'glass.round' });
  const next = button('›', () => move(1), { cls: 'glass.round' });
  const btns = h('div.btns', { dataset: { group: 'featbtns' } }, openBtn, h('div.gap'), prev, next);
  const dotsWrap = h('div');
  box.append(stage, h('div.grad-l'), h('div.grad-b'), h('div.info', { style: { width: '72%' } }, eyebrow, title, meta, desc, btns), dotsWrap);
  let index = 0, pinned = null, layer = null, preview = null;
  const current = () => pinned || slides[index % Math.max(1, slides.length)];
  const rot = rotator(ctx, slides.length, (i) => (slides[i]?.kind === 'live' ? 20000 : 8000), () => move(1));

  function move(d) {
    if (!slides.length) return;
    index = (index + d + slides.length) % slides.length;
    show();
  }
  function show() {
    const s = current();
    if (!s) return;
    const old = layer;
    if (preview) preview.stop();
    preview = null;
    layer = h('div.layer.out');
    if (s.kind === 'live') {
      const ev = s.event;
      layer.style.background = `linear-gradient(135deg, #17171c, ${TINT[ev.label] || '#2e2e38'})`;
      preview = livePreview(ev.channel);
      layer.append(preview.el);
      eyebrow.replaceChildren(h('span.livechip', null, h('i.dot'), 'EN DIRECTO · ' + ev.label));
      title.textContent = ev.title;
      meta.textContent = eventSubtitle(ev);
      desc.textContent = '';
      openBtn.textContent = '▶   Ver en directo';
    } else {
      const it = s.item;
      if (it.backdrop) layer.append(coverImg(it.backdrop, 'bg kb'));
      else layer.append(coverImg(it.poster, 'blur'), coverImg(it.poster, 'poster'));
      eyebrow.replaceChildren(h('div.eyebrow', null, s.kind === 'series' ? 'SERIE' : 'PELÍCULA'));
      title.textContent = cleanTitle(it.name);
      meta.textContent = metaLine(it.year, it.rating, it.genre);
      desc.textContent = it.plot || '';
      openBtn.textContent = s.kind === 'series' ? '▶   Ver serie' : '▶   Ver película';
    }
    stage.append(layer);
    requestAnimationFrame(() => requestAnimationFrame(() => layer.classList.remove('out')));
    if (old) {
      old.classList.add('out');
      setTimeout(() => old.remove(), 750);
    }
    prev.style.display = next.style.display = slides.length > 1 && !pinned ? '' : 'none';
    dotsWrap.replaceChildren(slides.length > 1 && !pinned ? dots(slides.length, index) : '');
    if (!pinned) rot.schedule(index);
  }
  ctx.cleanup(() => preview && preview.stop());
  show();
  return {
    el: box,
    setPinned(s) {
      const same = (pinned && s && slideKey(pinned) === slideKey(s)) || (!pinned && !s);
      if (same) return;
      pinned = s;
      if (s) rot.stop();
      show();
    },
  };
}

function homeScreen(ctx) {
  const scroller = h('div.scroll');
  watchScroll(ctx, scroller);
  const events = D.liveNow();
  const slides = slidesForHome(events);
  const sports = events.filter((e) => e.label !== 'NOTICIAS');
  const news = events.filter((e) => e.label === 'NOTICIAS');
  const recent = D.recentChannels();
  const favCh = S.favorites.filter((f) => f.kind === 'live').map((f) => D.channelById(f.id)).filter(Boolean);
  const myList = S.favorites.filter((f) => f.kind !== 'live');
  const cont = D.continueWatching();
  D.loadCaptures([...recent, ...events.map((e) => e.channel), ...favCh].map((c) => c.id)).then((n) => {
    if (n > 0 && scroller.isConnected) ctx.refresh();
  });

  if (!S.live.length && !S.vod.length && !S.series.length) return emptyState('Todavía no hay nada aquí', 'Añade una lista en Ajustes.');

  // Bento: featured + live now.
  const featured = featuredBox(ctx, slides, {
    onOpen: (s) => {
      if (!s) return;
      if (s.kind === 'live') {
        const list = events.map((e) => e.channel);
        playChannels(list.length ? list : [s.event.channel], Math.max(0, list.findIndex((c) => c.id === s.event.channel.id)));
      } else if (s.kind === 'series') openSeries(ctx, s.item);
      else openMovie(ctx, s.item);
    },
    onLong: (s) => s && s.kind !== 'live' && toggleFav(s.kind, s.item.id, s.item.name, s.item.poster),
  });
  const panel = h('div.livepanel', { dataset: { group: 'livenow', top: '' } }, h('h4', null, h('i.dot'), 'En directo ahora'));
  if (!events.length) panel.append(h('div.t-caption.faint', { style: { padding: '.6rem .8rem' } }, 'Cuando la guía tenga partidos, eventos o informativos en emisión aparecerán aquí.'));
  events.slice(0, 4).forEach((ev, i) => {
    const r = h('div.lnr.f', { tabIndex: -1, dataset: { key: 'n' + ev.channel.id } },
      h('div.lg', null, logoTile(ev.channel.logo, ev.channel.name.slice(0, 2).toUpperCase())),
      h('div.tx', null, h('div.t.clamp1', null, ev.title),
        h('div.s.clamp1', null, h('b', null, ev.label), ev.channel.name.toLowerCase() !== ev.title.toLowerCase() ? '  ·  ' + ev.channel.name : '')),
      ev.start ? progressBar((Date.now() - ev.start) / (ev.stop - ev.start)) : null);
    r.addEventListener('focus', () => featured.setPinned({ kind: 'live', event: ev }));
    r.addEventListener('click', () => playChannels(events.map((e) => e.channel), i));
    panel.append(r);
  });
  panel.addEventListener('focusout', () => setTimeout(() => { if (!panel.contains(document.activeElement)) featured.setPinned(null); }, 0));
  scroller.append(h('div.bento', { dataset: { top: '' } }, featured.el, panel));

  if (cont.length) {
    scroller.append(shelf('Seguir viendo', cont, (p) => posterCard({
      title: p.title, image: p.image, key: p.uid,
      subtitle: p.kind === 'series' ? p.subtitle : fmt.minutesLeft(p.dur - p.pos),
      progress: p.dur ? p.pos / p.dur : 0,
      onClick: () => openProgress(ctx, p),
      onLong: async () => { await D.removeProgress(p.uid); toast('Quitado de Seguir viendo'); },
    })));
  }
  if (recent.length) scroller.append(shelf('Últimos canales', recent, (c, i) => chCard(recent, c, i, { showAge: true }), { live: true }));
  const rm = D.recommendedMovies();
  if (rm.length) scroller.append(shelf('Películas recomendadas', rm, (m) => movieCard(ctx, m)));
  if (sports.length) scroller.append(shelf('Deportes y eventos en directo', sports, (ev, i) => eventCard(ev, { onClick: () => playChannels(sports.map((e) => e.channel), i) }), { live: true }));
  const rs = D.recommendedSeries();
  if (rs.length) scroller.append(shelf('Series recomendadas', rs, (s) => seriesCard(ctx, s)));
  if (news.length) scroller.append(shelf('Noticias en directo', news, (ev, i) => eventCard(ev, { onClick: () => playChannels(news.map((e) => e.channel), i) }), { live: true }));
  if (myList.length) {
    scroller.append(shelf('Mi lista', myList, (f) => posterCard({
      title: f.title, image: f.image, key: f.kind + f.id,
      onClick: () => {
        const x = f.kind === 'series' ? D.seriesById(f.id) : D.movieById(f.id);
        if (x) (f.kind === 'series' ? openSeries : openMovie)(ctx, x);
      },
      onLong: () => toggleFav(f.kind, f.id, f.title, f.image),
    })));
  }
  if (favCh.length) scroller.append(shelf('Canales favoritos', favCh, (c, i) => chCard(favCh, c, i), { live: true }));
  return scroller;
}

// ------------------------------------------------------------------ En vivo

/** En vivo top: text on black on the left, the channel playing in a rounded box on the right. */
function liveBand(ctx, slides, playAll) {
  const band = h('div.liveband', { dataset: { top: '' } });
  const chip = h('span.livechip');
  const title = h('div.title.clamp3');
  const sub = h('div', { style: { display: 'flex', alignItems: 'center', gap: '1rem' } });
  const bar = h('div', { style: { width: '75%', marginTop: '.8rem' } });
  const desc = h('div.t-caption.clamp3', { style: { color: 'rgba(255,255,255,.6)', marginTop: '.8rem' } });
  const txt = h('div.txtcol', null, h('div', null, chip), title, sub, bar, desc);
  const vid = h('div.vid.box');
  const stage = h('div', { style: { position: 'absolute', inset: '0' } });
  const dotsWrap = h('div');
  vid.append(stage, dotsWrap);
  const openBtn = button('▶   Ver en directo', () => playAll(slides[index]), { cls: 'glass', primary: true });
  const prev = button('‹', () => move(-1), { cls: 'glass.round' });
  const next = button('›', () => move(1), { cls: 'glass.round' });
  const btns = h('div.btns', { dataset: { group: 'bandbtns' } }, openBtn, h('div', { style: { width: '.6rem' } }), prev, next);
  band.append(txt, vid, btns);
  band.addEventListener('focusin', () => vid.classList.add('focus-within'));
  band.addEventListener('focusout', () => setTimeout(() => vid.classList.toggle('focus-within', band.contains(document.activeElement)), 0));
  let index = 0, layer = null, preview = null;
  const rot = rotator(ctx, slides.length, () => 14000, () => move(1));
  function move(d) {
    index = (index + d + slides.length) % slides.length;
    show();
  }
  function show() {
    const ev = slides[index];
    if (preview) preview.stop();
    const old = layer;
    layer = h('div.layer.out', { style: { background: `linear-gradient(135deg, #17171c, ${TINT[ev.label] || '#2e2e38'})` } });
    preview = livePreview(ev.channel);
    layer.append(preview.el);
    stage.append(layer);
    requestAnimationFrame(() => requestAnimationFrame(() => layer.classList.remove('out')));
    if (old) {
      old.classList.add('out');
      setTimeout(() => old.remove(), 750);
    }
    chip.replaceChildren(h('i.dot'), 'EN DIRECTO · ' + ev.label);
    title.textContent = ev.title;
    const lg = h('div', { style: { width: '4.4rem', height: '2.5rem', position: 'relative', borderRadius: '.5rem', overflow: 'hidden', flex: 'none' } }, logoTile(ev.channel.logo, '', { hideMissing: true }));
    sub.replaceChildren(lg, h('span.t-label.clamp1', { style: { color: 'rgba(255,255,255,.75)' } }, eventSubtitle(ev)));
    bar.replaceChildren(ev.start ? progressBar((Date.now() - ev.start) / (ev.stop - ev.start)) : '');
    const nn = D.nowNext(ev.channel);
    desc.textContent = nn?.now?.d || '';
    prev.style.display = next.style.display = slides.length > 1 ? '' : 'none';
    dotsWrap.replaceChildren(slides.length > 1 ? dots(slides.length, index) : '');
    rot.schedule(index);
  }
  ctx.cleanup(() => preview && preview.stop());
  show();
  return band;
}

function liveScreen(ctx) {
  const scroller = h('div.scroll');
  watchScroll(ctx, scroller);
  const events = D.liveNow();
  const sports = events.filter((e) => e.label !== 'NOTICIAS');
  const news = events.filter((e) => e.label === 'NOTICIAS');
  const slides = [...sports.slice(0, 6), ...news.slice(0, 2)];
  const shown = new Set(slides.map((e) => e.channel.id));
  const more = sports.filter((e) => !shown.has(e.channel.id));
  const favCh = S.favorites.filter((f) => f.kind === 'live').map((f) => D.channelById(f.id)).filter(Boolean);
  const recent = D.recentChannels();
  D.loadCaptures([...recent, ...events.map((e) => e.channel), ...favCh].map((c) => c.id)).then((n) => {
    if (n > 0 && scroller.isConnected) ctx.refresh();
  });

  if (slides.length) {
    scroller.append(liveBand(ctx, slides, (ev) => playChannels(events.map((e) => e.channel), Math.max(0, events.indexOf(ev)))));
  }
  const themes = [{ key: null, title: 'Todos los canales', count: S.live.length, colors: ['#3A3A46', '#1C1C24'], sub: 'Guía completa' }, ...D.liveThemes()];
  scroller.append(shelf('Explorar por temas', themes, (t) => themeTile({
    title: t.title, subtitle: t.sub || `${t.count} canales`, colors: t.colors,
    onClick: () => ctx.push((c) => guidePage(c, t.key ? 'theme:' + t.key : null)),
  })));
  if (more.length) scroller.append(shelf('Más deportes y eventos ahora', more, (ev, i) => eventCard(ev, { onClick: () => playChannels(more.map((e) => e.channel), i) }), { live: true }));
  if (favCh.length) scroller.append(shelf('Canales favoritos', favCh, (c, i) => chCard(favCh, c, i, { width: 17.6 }), { live: true }));
  if (recent.length) scroller.append(shelf('Últimos canales', recent, (c, i) => chCard(recent, c, i, { width: 17.6, showAge: true }), { live: true }));
  if (!S.live.length) scroller.append(emptyState('No hay canales en esta lista'));
  return scroller;
}

// ------------------------------------------------------------------ Películas / Series

/** Full-width carousel with wide artwork; text on the left over a dark gradient. */
function showcase(ctx, items, kind) {
  const box = h('div.box', { dataset: { top: '' }, style: { height: '26.8rem', margin: '0 3.6rem 2rem var(--edge)' } });
  const stage = h('div', { style: { position: 'absolute', inset: '0' } });
  const eyebrow = h('div.eyebrow');
  const title = h('div.title.clamp2');
  const meta = h('div.meta.clamp1');
  const desc = h('div.desc.clamp2');
  const openBtn = button('', () => open(), { cls: 'glass', primary: true });
  const listBtn = button('', async () => {
    const it = items[index].item;
    await toggleFav(kind, it.id, it.name, it.poster);
  }, { cls: 'glass' });
  const prev = button('‹', () => move(-1), { cls: 'glass.round' });
  const next = button('›', () => move(1), { cls: 'glass.round' });
  const btns = h('div.btns', { dataset: { group: 'showbtns' } }, openBtn, listBtn, h('div.gap'), prev, next);
  const dotsWrap = h('div');
  box.append(stage, h('div.grad-l'), h('div.grad-b'), h('div.info', null, eyebrow, title, meta, desc, btns), dotsWrap);
  let index = 0, layer = null;
  const rot = rotator(ctx, items.length, () => 9000, () => move(1));
  const open = () => {
    const it = items[index].item;
    kind === 'series' ? openSeries(ctx, it) : openMovie(ctx, it);
  };
  function move(d) {
    index = (index + d + items.length) % items.length;
    show();
  }
  function show() {
    const { item: it, eyebrow: eb } = items[index];
    const old = layer;
    layer = h('div.layer.out');
    if (it.backdrop) layer.append(coverImg(it.backdrop, 'bg kb'));
    else layer.append(coverImg(it.poster, 'blur'), coverImg(it.poster, 'poster'));
    stage.append(layer);
    requestAnimationFrame(() => requestAnimationFrame(() => layer.classList.remove('out')));
    if (old) {
      old.classList.add('out');
      setTimeout(() => old.remove(), 750);
    }
    eyebrow.textContent = eb;
    title.textContent = cleanTitle(it.name);
    meta.textContent = metaLine(it.year, it.rating, it.genre);
    desc.textContent = it.plot || '';
    openBtn.textContent = kind === 'series' ? '▶   Ver serie' : '▶   Ver película';
    listBtn.textContent = D.isFavorite(kind, it.id) ? '✓  En Mi lista' : '+  Mi lista';
    prev.style.display = next.style.display = items.length > 1 ? '' : 'none';
    dotsWrap.replaceChildren(items.length > 1 ? dots(items.length, index) : '');
    rot.schedule(index);
  }
  show();
  return box;
}

function browseScreen(kind) {
  return (ctx) => {
    const scroller = h('div.scroll');
    watchScroll(ctx, scroller);
    const isSeries = kind === 'series';
    const all = isSeries ? S.series : S.vod;
    if (!all.length) return emptyState(isSeries ? 'No hay series en esta lista' : 'No hay películas en esta lista', 'Si tu proveedor las incluye, prueba a actualizar la lista en Ajustes.');
    const recent = isSeries ? D.recentSeries(40) : D.recentMovies(40);
    const rated = isSeries ? D.recommendedSeries(12) : D.recommendedMovies(12);
    const items = [];
    recent.slice(0, 10).sort((a, b) => !!b.backdrop - !!a.backdrop).slice(0, 5)
      .forEach((x) => items.push({ item: x, eyebrow: isSeries ? 'SERIE · ACTUALIZADA' : 'NUEVA EN TU LISTA' }));
    rated.filter((x) => !items.some((y) => y.item.id === x.id)).sort((a, b) => b.rating - a.rating).slice(0, 2)
      .forEach((x) => items.push({ item: x, eyebrow: isSeries ? 'SERIE · MEJOR VALORADA' : 'MEJOR VALORADA' }));
    if (items.length) scroller.append(showcase(ctx, items, kind));
    if (!isSeries) {
      D.prefetchBackdrops(recent.slice(0, 8)).then((changed) => {
        if (changed && scroller.isConnected && !scroller.dataset.refetched) {
          scroller.dataset.refetched = '1';
          ctx.refresh();
        }
      });
    }
    const card = (x) => (isSeries ? seriesCard(ctx, x) : movieCard(ctx, x));
    scroller.append(shelf(isSeries ? 'Actualizadas recientemente' : 'Añadidas recientemente', recent, card));
    const favs = S.favorites.filter((f) => f.kind === kind).map((f) => (isSeries ? D.seriesById(f.id) : D.movieById(f.id))).filter(Boolean);
    if (favs.length) scroller.append(shelf('Mi lista', favs, card));
    // Category rows, added as the user scrolls down.
    const cats = (isSeries ? S.cats.series : S.cats.vod).filter((c) => all.some((x) => x.cat === c.id));
    let next = 0;
    const sentinel = h('div', { style: { height: '1px' } });
    const addMore = (n) => {
      const end = Math.min(cats.length, next + n);
      for (; next < end; next++) {
        const c = cats[next];
        const list = D.inCategory(kind, c.id, 40);
        if (!list.length) continue;
        const sec = shelf(c.name, list, card);
        if (list.length >= 40) {
          sec.querySelector('.row').append(viewAllCard(() => ctx.push((cx) => gridPage(cx, kind, c))));
        }
        scroller.insertBefore(sec, sentinel);
      }
    };
    scroller.append(sentinel);
    addMore(8);
    const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting) && next < cats.length) addMore(6); }, { root: scroller, rootMargin: '600px' });
    io.observe(sentinel);
    ctx.cleanup(() => io.disconnect());
    return scroller;
  };
}

function viewAllCard(onClick) {
  const el = h('div.card.f', { tabIndex: -1 }, h('div.art', { style: { display: 'flex', alignItems: 'center', justifyContent: 'center' } }, h('span.t-section.dim', null, 'Ver todo →')));
  bindClick(el, onClick);
  return el;
}

function gridPage(ctx, kind, cat) {
  const isSeries = kind === 'series';
  const list = (isSeries ? S.series : S.vod).filter((x) => x.cat === cat.id).sort((a, b) => b.added - a.added);
  const grid = h('div', { style: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, 10.6rem)', gap: '2.4rem 1.6rem', padding: '1.6rem 4.8rem 6rem 5.6rem' } });
  let shown = 0;
  const more = () => {
    const end = Math.min(list.length, shown + 120);
    for (; shown < end; shown++) grid.append(isSeries ? seriesCard(ctx, list[shown]) : movieCard(ctx, list[shown]));
  };
  more();
  const scroller = h('div.scroll', { style: { paddingTop: '2rem' } }, h('div.t-title', { style: { margin: '1.6rem 5.6rem 0' } }, cat.name), h('div.dim', { style: { margin: '0 5.6rem' } }, `${list.length} títulos`), grid);
  scroller.addEventListener('scroll', () => {
    if (scroller.scrollTop + scroller.clientHeight > scroller.scrollHeight - 800) more();
  }, { passive: true });
  return scroller;
}

// ------------------------------------------------------------------ detail pages

function detailBg(image, wide) {
  return h('div.detail-bg' + (wide ? '' : '.blur'), null, coverImg(image, 'x'));
}

function moviePage(ctx, m) {
  const root = h('div.scroll', { style: { padding: '0' } });
  const bgWrap = h('div');
  const body = h('div.detail');
  root.append(bgWrap, body);
  const draw = (d) => {
    const backdrop = d?.backdrop || m.backdrop;
    bgWrap.replaceChildren(detailBg(backdrop || d?.poster || m.poster, !!backdrop));
    const prog = S.progress['movie:' + m.id];
    const fav = D.isFavorite('movie', m.id);
    const meta = [metaLine(d?.year || m.year, d?.rating || m.rating, d?.genre), d?.duration].filter(Boolean).join('  ·  ');
    const btns = h('div.btnrow', { dataset: { group: 'mbtns' } });
    if (prog && prog.pos > 0) {
      btns.append(button(`▶  Reanudar · ${fmt.duration(prog.pos)}`, () => play([movieItem(m)], 0, prog.pos), { primary: true }));
      btns.append(button('Desde el principio', () => play([movieItem(m)], 0, 0)));
    } else btns.append(button('▶  Reproducir', () => play([movieItem(m)], 0, 0), { primary: true }));
    btns.append(button(fav ? '✓  En Mi lista' : '+  Mi lista', async () => { await toggleFav('movie', m.id, m.name, m.poster); }));
    put(body,
      h('div.t-label.accent', null, 'PELÍCULA'),
      h('div.t-display.clamp2', { style: { marginTop: '1rem' } }, cleanTitle(m.name)),
      meta ? h('div.t-label.dim', { style: { marginTop: '1.2rem' } }, meta) : null,
      d?.plot || m.plot ? h('div.dim.clamp5', { style: { marginTop: '1.6rem', maxWidth: '62rem', display: '-webkit-box', WebkitLineClamp: '5', WebkitBoxOrient: 'vertical', overflow: 'hidden' } }, d?.plot || m.plot) : null,
      d?.director ? h('div.t-caption.faint', { style: { marginTop: '1.2rem' } }, 'Dirección: ' + d.director) : null,
      d?.cast ? h('div.t-caption.faint.clamp2', null, 'Reparto: ' + d.cast) : null,
      btns,
    );
    const sim = D.similarMovies(m);
    const rows = h('div.detail-rows', { style: { marginTop: '3.6rem', position: 'relative' } });
    if (sim.length) rows.append(shelf('Más como esto', sim, (x) => movieCard(ctx, x)));
    root.querySelector('.detail-rows')?.remove();
    root.append(rows);
  };
  draw(null);
  D.movieDetails(m).then((d) => {
    if (!root.isConnected) return;
    const a = document.activeElement;
    const keep = a && root.contains(a) ? a.textContent : null;
    draw(d);
    const again = keep && [...root.querySelectorAll('.f')].find((x) => x.textContent === keep);
    focus(again || root.querySelector('.f[data-default]'), { instant: true });
  }).catch(() => {});
  return root;
}

function seriesPage(ctx, s) {
  const root = h('div.scroll', { style: { padding: '0' } });
  const bgWrap = h('div');
  const body = h('div.detail', { style: { paddingTop: '5.6rem' } });
  const below = h('div.detail-rows', { style: { position: 'relative', marginTop: '2.2rem' } });
  root.append(bgWrap, body, below);
  let seasonIdx = -1;
  const draw = (d, error) => {
    const backdrop = d?.backdrop || s.backdrop;
    bgWrap.replaceChildren(detailBg(backdrop || s.poster, !!backdrop));
    const seasons = d?.seasons || [];
    const all = seasons.flatMap((x) => x.episodes);
    const resume = S.progress['series:' + s.id];
    const resumeEp = resume ? all.find((e) => e.id === resume.id) : null;
    if (seasonIdx < 0 && seasons.length) seasonIdx = Math.max(0, seasons.findIndex((x) => x.number === resumeEp?.season));
    const target = resumeEp || all[0];
    const pr = target ? S.progress['episode:' + target.id] : null;
    const startMs = pr && pr.dur && pr.pos < pr.dur * 0.92 ? pr.pos : 0;
    const playEp = (e, ms) => play(all.map((x) => episodeItem(s, x, d)), all.indexOf(e), ms);
    const fav = D.isFavorite('series', s.id);
    const meta = [metaLine(d?.year || s.year, d?.rating || s.rating, d?.genre || s.genre), seasons.length ? `${seasons.length} temporada${seasons.length > 1 ? 's' : ''}` : null].filter(Boolean).join('  ·  ');
    const btns = h('div.btnrow', { dataset: { group: 'sbtns' } },
      button(!d && !error ? 'Cargando…' : !target ? 'Sin episodios' : `▶  ${resumeEp ? 'Continuar' : 'Reproducir'} T${target.season} · E${target.episode}`, () => target && playEp(target, startMs), { primary: true }),
      button(fav ? '✓  En Mi lista' : '+  Mi lista', () => toggleFav('series', s.id, s.name, s.poster)));
    put(body,
      h('div.t-label.accent', null, 'SERIE'),
      h('div.t-display.clamp2', { style: { marginTop: '.8rem' } }, cleanTitle(s.name)),
      meta ? h('div.t-label.dim', { style: { marginTop: '1rem' } }, meta) : null,
      d?.plot || s.plot ? h('div.dim.clamp3', { style: { marginTop: '1.2rem', maxWidth: '62rem' } }, d?.plot || s.plot) : null,
      btns,
    );
    below.replaceChildren();
    if (!d && !error) below.append(h('div', { style: { height: '16rem', position: 'relative' } }, loading()));
    else if (!seasons.length) below.append(h('div', { style: { height: '12rem', position: 'relative' } }, emptyState('No se encontraron episodios', error ? 'El servidor no respondió. Vuelve a intentarlo más tarde.' : null)));
    else {
      if (seasons.length > 1) {
        const tabs = h('div.row', { dataset: { group: 'seasons' }, style: { gap: '.8rem', paddingTop: '.4rem', paddingBottom: '.4rem' } });
        seasons.forEach((x, i) => {
          const t = h('button.pill.f', { tabIndex: -1, type: 'button', style: { height: '3.6rem', fontSize: '1.25rem', background: i === seasonIdx ? 'rgba(255,255,255,.12)' : 'transparent' } }, x.name);
          t.addEventListener('click', () => {
            seasonIdx = i;
            draw(d, error);
            const again = [...root.querySelectorAll('[data-group="seasons"] .f')][i];
            focus(again, { instant: true });
          });
          tabs.append(t);
        });
        below.append(tabs);
      }
      const season = seasons[Math.max(0, seasonIdx)] || seasons[0];
      below.append(shelf(season.name, season.episodes, (e) => {
        const p = S.progress['episode:' + e.id];
        const frac = p && p.dur ? p.pos / p.dur : 0;
        const watched = frac >= 0.92;
        const c = posterCard({
          title: `${e.episode}. ${e.title}`, image: e.image || backdrop || s.poster, key: 'e' + e.id,
          subtitle: watched ? 'Visto' : e.secs ? Math.round(e.secs / 60) + ' min' : null,
          progress: watched ? 0 : frac,
          onClick: () => playEp(e, p && !watched ? p.pos : 0),
        });
        c.classList.add('wide');
        c.style.width = '22rem';
        return c;
      }, { key: 'eps' }));
    }
    const sim = D.similarSeries(s);
    if (sim.length) below.append(shelf('Más como esto', sim, (x) => seriesCard(ctx, x)));
  };
  draw(null, false);
  D.seriesDetails(s).then((d) => {
    if (!root.isConnected) return;
    draw(d, false);
    focus(root.querySelector('.f[data-default]'), { instant: true });
  }).catch(() => root.isConnected && draw(null, true));
  return root;
}

function episodeItem(s, e, d) {
  return {
    kind: 'episode', id: e.id, title: e.title, subtitle: `T${e.season} · E${e.episode}`,
    image: e.image || d?.backdrop || s.poster, url: D.episodeUrl(e), ext: e.ext,
    seriesId: s.id, seriesTitle: cleanTitle(s.name), seriesImage: s.poster,
  };
}

// ------------------------------------------------------------------ guide (all channels)

function guidePage(ctx, start) {
  const root = h('div.guide');
  const cats = h('div.cats', { dataset: { group: 'gcats' } });
  const chs = h('div.chs', { dataset: { group: 'gchs' } });
  const det = h('div.det');
  root.append(h('div.t-title', { style: { padding: '2.8rem 4.8rem .8rem' } }, 'Canales y guía'), h('div.cols', null, cats, chs, det));
  const virtual = [
    ['fav', 'Favoritos'], ['recent', 'Recientes'], ['all', 'Todos los canales'],
    ...D.liveThemes().map((t) => ['theme:' + t.key, t.title]),
  ];
  let selected = start || 'all';
  const listFor = (key) => {
    if (key === 'fav') return S.favorites.filter((f) => f.kind === 'live').map((f) => D.channelById(f.id)).filter(Boolean);
    if (key === 'recent') return D.recentChannels();
    if (key === 'all') return S.live;
    if (key.startsWith('theme:')) return D.channelsOfTheme(key.slice(6));
    return S.live.filter((c) => c.cat === key);
  };
  const catEls = new Map();
  const addCat = ([key, name]) => {
    const b = h('div.ci.f' + (key === selected ? '.sel' : ''), { tabIndex: -1, dataset: { key: 'c' + key } }, h('span.clamp1', null, name));
    if (key === selected) b.dataset.default = '';
    let t;
    b.addEventListener('focus', () => {
      clearTimeout(t);
      t = setTimeout(() => select(key), 250);
    });
    b.addEventListener('click', () => select(key, true));
    catEls.set(key, b);
    cats.append(b);
  };
  virtual.forEach(addCat);
  cats.append(h('div', { style: { height: '1px', background: 'var(--surface3)', margin: '.8rem 1.2rem' } }));
  S.cats.live.forEach((c) => addCat([c.id, c.name]));

  const drawDetail = (c) => {
    det.replaceChildren(h('div.biglogo', null, logoTile(c.logo, c.name)), h('div.t-title.clamp2', { style: { marginTop: '1.8rem' } }, c.name));
    const box = h('div', { style: { marginTop: '1.8rem' } });
    det.append(box);
    const fill = (nn) => {
      box.replaceChildren();
      if (nn?.now) {
        box.append(h('div.t-caption.accent', null, `AHORA  ·  ${fmt.clock(nn.now.s)} – ${fmt.clock(nn.now.e)}`), h('div.t-section.clamp2', { style: { marginTop: '.6rem' } }, nn.now.t));
        const b = progressBar((Date.now() - nn.now.s) / (nn.now.e - nn.now.s));
        b.style.width = '80%';
        b.style.marginTop = '1rem';
        box.append(b);
        if (nn.now.d) box.append(h('div.dim', { style: { marginTop: '1.2rem', display: '-webkit-box', WebkitLineClamp: '5', WebkitBoxOrient: 'vertical', overflow: 'hidden' } }, nn.now.d));
      } else box.append(h('div.faint', null, 'Sin información de programación'));
      if (nn?.next) box.append(h('div.t-caption.faint', { style: { marginTop: '2rem' } }, `A CONTINUACIÓN  ·  ${fmt.clock(nn.next.s)}`), h('div.t-label.dim.clamp1', null, nn.next.t));
    };
    const local = D.nowNext(c);
    fill(local);
    if (!local) D.shortEpg(c).then((nn) => det.isConnected && fill(nn));
  };

  function select(key, focusList) {
    if (key !== selected || !chs.childElementCount) {
      selected = key;
      catEls.forEach((el, k) => el.classList.toggle('sel', k === key));
      const list = listFor(key);
      chs.replaceChildren();
      chs.scrollTop = 0;
      if (!list.length) chs.append(h('div.faint', { style: { padding: '2rem 1.2rem' } }, key === 'fav' ? 'Mantén pulsado OK sobre un canal para añadirlo a favoritos.' : 'Sin canales'));
      let shown = 0;
      const more = () => {
        const end = Math.min(list.length, shown + 80);
        for (; shown < end; shown++) {
          const c = list[shown], i = shown;
          const nn = D.nowNext(c);
          const r = h('div.chr.f', { tabIndex: -1, dataset: { key: 'h' + c.id } },
            h('span.num', null, c.num ? String(c.num) : ''),
            h('div.lg', null, logoTile(c.logo, c.name.slice(0, 2).toUpperCase())),
            h('div.tx', null, h('div.t-label.clamp1', null, c.name), nn?.now ? h('div.t-caption.faint.clamp1', null, nn.now.t) : null));
          bindClick(r, () => playChannels(list, i), () => toggleFav('live', c.id, c.name, c.logo));
          r.addEventListener('focus', () => {
            drawDetail(c);
            if (i > shown - 20) more();
          });
          chs.append(r);
        }
      };
      more();
      chs._more = more;
    }
    if (focusList) {
      const first = chs.querySelector('.f');
      if (first) focus(first);
    }
  }
  select(selected);
  det.append(h('div.t-title.dim', { style: { marginTop: '4rem' } }, 'Elige un canal'), h('div.faint', { style: { marginTop: '.8rem' } }, 'Arriba y abajo para moverte, OK para verlo y mantén pulsado OK para añadirlo a favoritos.'));
  // Lists scroll themselves to keep the focused row in view.
  [cats, chs].forEach((col) => col.addEventListener('focusin', (e) => {
    const r = e.target.getBoundingClientRect(), cr = col.getBoundingClientRect();
    if (r.top < cr.top + 80 || r.bottom > cr.bottom - 80) col.scrollTo({ top: col.scrollTop + (r.top - cr.top) - cr.height / 3, behavior: D.prefs.reduceMotion ? 'auto' : 'smooth' });
  }));
  return root;
}

// ------------------------------------------------------------------ Buscar

function searchScreen(ctx) {
  const input = h('input.input.f', { placeholder: 'Buscar canales, películas y series…', autocomplete: 'off', dataset: { default: '' }, style: { width: '46rem' } });
  const results = h('div');
  const wrap = h('div.scroll', null, h('div', { style: { padding: '0 0 1.6rem var(--edge)' } }, input), results);
  watchScroll(ctx, wrap);
  let last = null;
  const run = debounce(() => {
    const q = input.value.trim();
    if (q === last) return;
    last = q;
    try { sessionStorage.setItem('nt:q', q); } catch { /* empty */ }
    results.replaceChildren();
    if (q.length < 2) {
      results.append(h('div.faint', { style: { padding: '0 0 0 var(--edge)' } }, 'Escribe al menos dos letras para buscar canales, películas y series.'));
      return;
    }
    const r = D.search(q);
    if (!r.live.length && !r.vod.length && !r.series.length) {
      results.append(h('div.dim', { style: { padding: '0 0 0 var(--edge)' } }, `Sin resultados para "${q}"`));
      return;
    }
    if (r.live.length) results.append(shelf('Canales', r.live, (c, i) => chCard(r.live, c, i, { width: 16 }), { live: true }));
    if (r.vod.length) results.append(shelf('Películas', r.vod, (m) => movieCard(ctx, m)));
    if (r.series.length) results.append(shelf('Series', r.series, (s) => seriesCard(ctx, s)));
  }, 250);
  input.addEventListener('input', run);
  try { input.value = sessionStorage.getItem('nt:q') || ''; } catch { /* empty */ }
  run();
  return wrap;
}

// ------------------------------------------------------------------ Ajustes

function settingsScreen(ctx) {
  const wrap = h('div.scroll');
  watchScroll(ctx, wrap);
  const s = h('div.settings');
  wrap.append(s);
  const row = (title, desc, control) => h('div.srow', null, h('div.tx', null, h('div.a', null, title), h('div.b', null, desc)), control);
  const yesNo = (key, after) => segmented(['No', 'Sí'], D.prefs[key] ? 1 : 0, (i) => { D.prefs[key] = i === 1; after && after(); });
  const pl = S.playlist;
  const when = S.lastSync ? new Date(S.lastSync).toLocaleString('es-ES', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : 'sin descargar';
  s.append(
    h('h2', null, 'Lista'),
    row(pl ? pl.name : 'Sin lista', pl ? `${pl.type === 'xtream' ? 'Xtream Codes · ' + pl.user : 'Lista M3U'}  ·  actualizada ${when}  ·  ${S.live.length} canales, ${S.vod.length} películas, ${S.series.length} series` : '',
      h('div', { style: { display: 'flex', gap: '1rem' }, dataset: { group: 'plbtns' } },
        button('Actualizar', () => app().runSync(true)),
        button('Cambiar', () => ctx.push(app().playlistScreen(false), { auto: false })),
        button('Quitar', async () => { await D.forgetPlaylist(); location.reload(); }))),
    row('Guía de programación', S.epgAt ? `Cargada ${new Date(S.epgAt).toLocaleString('es-ES', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })} · ${S.epg.size} canales con guía` : 'Todavía no se ha cargado. Se actualiza sola cada 6 horas.',
      button('Cargar ahora', async () => {
        toast('Cargando la guía…');
        try {
          await D.syncEpg();
          toast('Guía cargada');
        } catch (e) { toast(e.message); }
      })),
    h('h2', null, 'Reproducción'),
    row('Formato del directo (Xtream)', 'HLS va mejor en el navegador. Si un canal no arranca, prueba TS.', segmented(['HLS', 'TS'], D.prefs.liveFormat === 'ts' ? 1 : 0, (i) => { D.prefs.liveFormat = i ? 'ts' : 'm3u8'; })),
    row('Mosca de nansu tv', 'Marca de agua al 30 % en la esquina inferior izquierda. Solo en canales en vivo.', yesNo('watermark')),
    row('Tamaño de los subtítulos', 'XS es el tamaño de Apple TV.', segmented(['XS', 'S', 'M', 'L', 'XL'], D.prefs.subtitleSize, (i) => { D.prefs.subtitleSize = i; })),
    row('Estilo de los subtítulos', 'Sombra suave, o caja negra translúcida como en Apple TV.', segmented(['Sin fondo', 'Fondo'], D.prefs.subtitleBackground ? 1 : 0, (i) => { D.prefs.subtitleBackground = i === 1; })),
    h('h2', null, 'Interfaz'),
    row('Vista previa en directo', 'El destacado de Inicio y En vivo muestran el canal en movimiento y sin sonido. Usa una conexión de tu proveedor mientras se ve.', yesNo('livePreview')),
    row('Reducir animaciones', 'Quita el zoom lento y los fundidos. Útil en ordenadores o teles poco potentes.', yesNo('reduceMotion', () => document.body.classList.toggle('reduce', D.prefs.reduceMotion))),
    row('Conexión directa', 'Sin pasar por tu servidor. Solo funciona si tu proveedor usa https y lo permite; si algo deja de cargar, vuelve a "No".', yesNo('direct')),
    h('h2', null, 'Acceso'),
    row('Cerrar sesión', 'Para entrar de nuevo hará falta la contraseña de la web.', button('Cerrar sesión', async () => { await D.authPost('logout', ''); location.reload(); })),
    h('div.t-caption.faint', { style: { marginTop: '3.2rem' } }, 'nansu tv web · Reproductor personal. La web no incluye contenido. Tipografía Inter (OFL), hls.js (Apache 2.0), mpegts.js (Apache 2.0).'),
  );
  return wrap;
}

export const SCREENS = {
  home: homeScreen,
  live: liveScreen,
  movies: browseScreen('movie'),
  series: browseScreen('series'),
  search: searchScreen,
  settings: settingsScreen,
};

export { Preview, img };

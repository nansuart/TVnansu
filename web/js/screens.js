// Screens: Inicio, En vivo, Películas, Series, Buscar, Ajustes, plus the pages
// they open (movie, series, full category).
import { h, fmt, toast, cleanTitle, metaLine, debounce, put } from './util.js';
import { focus } from './nav.js';
import * as D from './data.js';
import {
  posterCard, channelCard, eventCard, shelf, logoTile, progressBar, button, iconButton, segmented, icon,
  dots, livePreview, thumbTour, eventSubtitle, TINT, emptyState, loading, bindClick, coverImg, favButton, refreshCaptures, picture,
  addCard, dialog, toggleSwitch, sortable,
} from './ui.js';
import { play } from './player.js';

const S = D.S;
const app = () => window.__nt;
const reduce = () => D.prefs.reduceMotion;

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
  toast(added ? (kind === 'live' ? 'Añadido a favoritos' : 'Añadido a Mi lista') : kind === 'live' ? 'Quitado de favoritos' : 'Quitado de Mi lista');
  return added;
}

function movieCard(ctx, m, extra = {}) {
  return posterCard({
    title: cleanTitle(m.name), image: m.poster, rating: m.rating, subtitle: extra.subtitle ?? m.year, key: 'm' + m.id,
    fav: { kind: 'movie', id: m.id, title: m.name, image: m.poster },
    onClick: () => openMovie(ctx, m),
    onLong: () => toggleFav('movie', m.id, m.name, m.poster),
  });
}
function seriesCard(ctx, s, extra = {}) {
  return posterCard({
    title: cleanTitle(s.name), image: s.poster, rating: s.rating, subtitle: extra.subtitle ?? s.year, key: 's' + s.id,
    fav: { kind: 'series', id: s.id, title: s.name, image: s.poster },
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

/** Rotates a carousel; waits while the mouse is over it or the tab is hidden. */
function rotator(ctx, box, count, delayOf, onNext) {
  let t = null, hover = false, index = 0;
  const schedule = (i) => {
    index = i;
    clearTimeout(t);
    if (count < 2 || reduce() || hover) return;
    t = setTimeout(() => (document.hidden ? schedule(index) : onNext()), delayOf(i));
  };
  box.addEventListener('pointerenter', () => { hover = true; clearTimeout(t); });
  box.addEventListener('pointerleave', () => { hover = false; schedule(index); });
  ctx.cleanup(() => clearTimeout(t));
  return { schedule, stop: () => clearTimeout(t) };
}

/** Prev/next arrows and dots in the corner of a carousel. */
function carouselControls(count, index, move, pick) {
  if (count < 2) return h('div');
  return h('div.boxctl', null,
    iconButton('chevL', (e) => { e.stopPropagation(); move(-1); }, 'Anterior', 'glass'),
    dots(count, index, pick),
    iconButton('chevR', (e) => { e.stopPropagation(); move(1); }, 'Siguiente', 'glass'));
}

/** Swaps in a new background layer with a cross-fade. */
function swapLayer(stage, old, layer) {
  stage.append(layer);
  requestAnimationFrame(() => requestAnimationFrame(() => layer.classList.remove('out')));
  if (old) {
    old.classList.add('out');
    setTimeout(() => old.remove(), 750);
  }
}

function artLayer(it) {
  const layer = h('div.layer.out');
  if (it.backdrop) layer.append(coverImg(it.backdrop, 'bg' + (reduce() ? '' : ' kb')));
  else layer.append(coverImg(it.poster, 'blur'), coverImg(it.poster, 'poster'));
  return layer;
}

// ------------------------------------------------------------------ Inicio

/**
 * Carousel of Inicio: recommended movies and series, alternated, preferring those with
 * wide artwork. Picked at random among the best ones once per catalogue (so it changes
 * between visits but not while browsing). The live channels only appear on demand.
 */
function slidesForHome() {
  return D.cached('homeSlides', () => {
    const pick = (list, n) => {
      const pool = [...list.filter((x) => x.backdrop).slice(0, 14), ...list.filter((x) => !x.backdrop)].slice(0, 14);
      for (let i = pool.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [pool[i], pool[j]] = [pool[j], pool[i]];
      }
      return pool.sort((x, y) => !!y.backdrop - !!x.backdrop).slice(0, n);
    };
    const ms = pick(D.recommendedMovies(30), 4), ss = pick(D.recommendedSeries(30), 4);
    const out = [];
    for (let i = 0; i < Math.max(ms.length, ss.length); i++) {
      if (ms[i]) out.push({ kind: 'movie', item: ms[i] });
      if (ss[i]) out.push({ kind: 'series', item: ss[i] });
    }
    return out;
  });
}

function slideKey(s) {
  return s.kind === 'live' ? 'e' + s.event.channel.id : s.kind[0] + s.item.id;
}

/** Big featured block: a live channel playing, or a movie/series with its artwork. */
function featuredBox(ctx, slides, { onOpen }) {
  const box = h('div.box.hero', { dataset: { top: '' } });
  const stage = h('div.stage');
  const eyebrow = h('div');
  const title = h('div.title.clamp2');
  const meta = h('div.meta.clamp1');
  const desc = h('div.desc.clamp2');
  const openBtn = button('', () => onOpen(current()), { primary: true, icon: 'play' });
  const favWrap = h('span.favwrap');
  const btns = h('div.btns', { dataset: { group: 'featbtns' } }, openBtn, favWrap);
  let ctl = h('div');
  box.append(stage, h('div.grad-l'), h('div.grad-b'), h('div.info', null, eyebrow, title, meta, desc, btns), ctl);
  stage.addEventListener('click', () => onOpen(current()));
  let index = 0, pinned = null, layer = null, preview = null;
  const current = () => pinned || slides[index % Math.max(1, slides.length)];
  const rot = rotator(ctx, box, slides.length, (i) => (slides[i]?.kind === 'live' ? 20000 : 8000), () => move(1));

  function move(d) {
    if (!slides.length) return;
    if (pinned && api.onUnpin) api.onUnpin();
    pinned = null;
    index = (index + d + slides.length) % slides.length;
    show();
  }
  function show() {
    const s = current();
    if (!s) return;
    const old = layer;
    if (preview) preview.stop();
    preview = null;
    favWrap.replaceChildren();
    if (s.kind === 'live') {
      const ev = s.event;
      layer = h('div.layer.out', { style: { background: `linear-gradient(135deg, #17171c, ${TINT[ev.label] || '#2e2e38'})` } });
      preview = livePreview(ev.channel);
      layer.append(preview.el);
      eyebrow.replaceChildren(h('span.livechip', null, h('i.dot'), 'EN DIRECTO · ' + ev.label));
      title.textContent = ev.title;
      meta.textContent = eventSubtitle(ev);
      desc.textContent = D.nowNext(ev.channel)?.now?.d || '';
      openBtn.lastChild.textContent = 'Ver en directo';
    } else {
      const it = s.item;
      layer = artLayer(it);
      eyebrow.replaceChildren(h('div.eyebrow', null, s.kind === 'series' ? 'SERIE' : 'PELÍCULA'));
      title.textContent = cleanTitle(it.name);
      meta.textContent = metaLine(it.year, it.rating, it.genre);
      desc.textContent = it.plot || '';
      openBtn.lastChild.textContent = s.kind === 'series' ? 'Ver serie' : 'Ver película';
      favWrap.append(favButton(s.kind, it.id, it.name, it.poster, { cls: 'pill.round.glass.f' }));
    }
    swapLayer(stage, old, layer);
    const nextCtl = carouselControls(slides.length, index, move, (i) => { pinned = null; index = i; show(); });
    ctl.replaceWith(nextCtl);
    ctl = nextCtl;
    if (!pinned) rot.schedule(index);
  }
  ctx.cleanup(() => preview && preview.stop());
  show();
  const api = {
    el: box,
    get pinned() { return pinned; },
    redraw() { if (!pinned) show(); },
    onUnpin: null,
    setPinned(s) {
      const same = (pinned && s && slideKey(pinned) === slideKey(s)) || (!pinned && !s);
      if (same) return;
      pinned = s;
      if (s) rot.stop();
      show();
    },
  };
  return api;
}

function homeScreen(ctx) {
  const scroller = h('div.scroll');
  if (!S.live.length && !S.vod.length && !S.series.length) return emptyState('Todavía no hay nada aquí', 'Añade una lista en Ajustes.');
  const events = D.liveNow();
  const slides = slidesForHome();
  const inSlides = new Set(slides.map(slideKey));
  const sports = events.filter((e) => e.label !== 'NOTICIAS');
  const news = events.filter((e) => e.label === 'NOTICIAS');
  const recent = D.recentChannels();
  const favCh = D.myChannels();
  const myList = S.favorites.filter((f) => f.kind !== 'live');
  const cont = D.continueWatching();
  D.loadCaptures([...recent, ...events.map((e) => e.channel), ...favCh].map((c) => c.id)).then((n) => {
    if (n > 0 && scroller.isConnected) refreshCaptures(scroller);
  });

  // Bento: featured + live now.
  if (slides.length || events.length) {
    const featured = featuredBox(ctx, slides, {
      onOpen: (s) => {
        if (!s) return;
        if (s.kind === 'live') {
          const list = events.map((e) => e.channel);
          playChannels(list.length ? list : [s.event.channel], Math.max(0, list.findIndex((c) => c.id === s.event.channel.id)));
        } else if (s.kind === 'series') openSeries(ctx, s.item);
        else openMovie(ctx, s.item);
      },
    });
    const list = h('div.list');
    const panel = h('div.livepanel', { dataset: { group: 'livenow', top: '' } },
      h('div.ph', null, h('h4', null, h('i.dot'), 'En directo ahora'),
        h('button.more', { type: 'button', tabIndex: -1, onclick: () => ctx.selectTab('live') }, 'Ver canales', icon('chevR', 'ic sm'))),
      list);
    if (!events.length) list.append(h('div.t-caption.faint', { style: { padding: '8px 10px' } }, 'Cuando la guía tenga partidos, eventos o informativos en emisión aparecerán aquí.'));
    events.slice(0, 8).forEach((ev, i) => {
      const r = h('div.lnr.f', { tabIndex: -1, dataset: { key: 'n' + ev.channel.id }, title: ev.title },
        h('div.lg', null, logoTile(ev.channel.logo, ev.channel.name.slice(0, 2).toUpperCase())),
        h('div.tx', null, h('div.t.clamp1', null, ev.title),
          h('div.s.clamp1', null, h('b', null, ev.label), ev.channel.name.toLowerCase() !== ev.title.toLowerCase() ? '  ·  ' + ev.channel.name : '')),
        ev.start ? progressBar((Date.now() - ev.start) / (ev.stop - ev.start)) : null);
      // First click: the channel plays in the big box. Second click (or its button): full screen.
      r.addEventListener('click', () => {
        if (r.classList.contains('pin')) {
          playChannels(events.map((e) => e.channel), i);
          return;
        }
        list.querySelectorAll('.lnr.pin').forEach((x) => x.classList.remove('pin'));
        r.classList.add('pin');
        featured.setPinned({ kind: 'live', event: ev });
      });
      list.append(r);
    });
    featured.onUnpin = () => list.querySelectorAll('.lnr.pin').forEach((x) => x.classList.remove('pin'));
    // Wide artwork for the movies of the carousel (one request each, remembered).
    const noArt = slides.filter((x) => x.kind === 'movie' && !x.item.backdrop).map((x) => x.item);
    if (noArt.length) D.prefetchBackdrops(noArt).then((ch) => { if (ch && scroller.isConnected) featured.redraw(); });
    scroller.append(h('div.bento', { dataset: { top: '' } }, featured.el, panel));
  }

  // Rows below, in the order chosen in Organizar (hidden ones are skipped).
  const rm = D.recommendedMovies().filter((m) => !inSlides.has('m' + m.id));
  const rs = D.recommendedSeries().filter((x) => !inSlides.has('s' + x.id));
  const ROWS = {
    mine: () => {
      const items = [...favCh, null];
      return shelf('Mis canales', items, (c, i) => (c
        ? chCard(favCh, c, i, { sub: D.nowNext(c)?.now?.t || '' })
        : addCard(favCh.length ? 'Añadir canales' : 'Elige tus canales', favCh.length ? '' : 'Tus favoritos, siempre a mano', () => pickChannels(ctx))),
      { live: true, key: 'mine', more: () => pickChannels(ctx), moreLabel: favCh.length ? 'Editar' : 'Añadir', moreIcon: favCh.length ? 'edit' : 'plus' });
    },
    continue: () => cont.length && shelf('Seguir viendo', cont, (p) => {
      const card = posterCard({
        title: p.title, image: p.image, key: p.uid,
        subtitle: p.kind === 'series' ? p.subtitle : fmt.minutesLeft(p.dur - p.pos),
        progress: p.dur ? p.pos / p.dur : 0,
        onClick: () => openProgress(ctx, p),
        onLong: async () => { await D.removeProgress(p.uid); card.remove(); toast('Quitado de Seguir viendo'); },
      });
      const x = h('button.favbtn', { type: 'button', tabIndex: -1, title: 'Quitar de Seguir viendo', html: icon('close').innerHTML });
      x.addEventListener('click', async (e) => {
        e.stopPropagation();
        await D.removeProgress(p.uid);
        card.remove();
        toast('Quitado de Seguir viendo');
      });
      card.querySelector('.art').append(x);
      return card;
    }),
    recent: () => recent.length && shelf('Últimos canales', recent, (c, i) => chCard(recent, c, i, { showAge: true }), { live: true, more: () => ctx.selectTab('live') }),
    movies: () => rm.length && shelf('Películas recomendadas', rm, (m) => movieCard(ctx, m), { more: () => ctx.selectTab('movies') }),
    sports: () => sports.length && shelf('Deportes y eventos en directo', sports, (ev, i) => eventCard(ev, { onClick: () => playChannels(sports.map((e) => e.channel), i) }), { live: true }),
    series: () => rs.length && shelf('Series recomendadas', rs, (x) => seriesCard(ctx, x), { more: () => ctx.selectTab('series') }),
    news: () => news.length && shelf('Noticias en directo', news, (ev, i) => eventCard(ev, { onClick: () => playChannels(news.map((e) => e.channel), i) }), { live: true }),
    mylist: () => myList.length && shelf('Mi lista', myList, (f) => posterCard({
      title: cleanTitle(f.title), image: f.image, key: f.kind + f.id,
      fav: { kind: f.kind, id: f.id, title: f.title, image: f.image },
      onClick: () => {
        const x = f.kind === 'series' ? D.seriesById(f.id) : D.movieById(f.id);
        if (x) (f.kind === 'series' ? openSeries : openMovie)(ctx, x);
      },
      onLong: () => toggleFav(f.kind, f.id, f.title, f.image),
    })),
  };
  for (const r of D.rowsFor('home')) {
    if (!r.on || !ROWS[r.key]) continue;
    const el = ROWS[r.key]();
    if (el) scroller.append(el);
  }
  scroller.append(organizeBar(ctx, 'home'));
  // A current picture for the channels of this page, taken one by one in the background.
  const tourList = [...new Map([...favCh.slice(0, 10), ...recent.slice(0, 8), ...sports.map((e) => e.channel), ...news.map((e) => e.channel)]
    .map((c) => [c.id, c])).values()].slice(0, 12);
  if (tourList.length) ctx.cleanup(thumbTour(scroller, tourList));
  return scroller;
}

// ------------------------------------------------------------------ En vivo

/** En vivo top: text on the left, the channel playing in a rounded box on the right. */
function liveBand(ctx, slides, playOne) {
  const band = h('div.liveband', { dataset: { top: '' } });
  const chip = h('span.livechip');
  const title = h('div.title.clamp3');
  const sub = h('div.sub');
  const bar = h('div.bar');
  const desc = h('div.desc.clamp3');
  const openBtn = button('Ver en directo', () => playOne(slides[index]), { primary: true, icon: 'play' });
  const favWrap = h('span.favwrap');
  const btns = h('div.btns', { dataset: { group: 'bandbtns' } }, openBtn, favWrap);
  const txt = h('div.txtcol', null, h('div', null, chip), title, sub, bar, desc, btns);
  const vid = h('div.vid.box');
  const stage = h('div.stage');
  let ctl = h('div');
  vid.append(stage, ctl);
  stage.addEventListener('click', () => playOne(slides[index]));
  band.append(txt, vid);
  let index = 0, layer = null, preview = null;
  const rot = rotator(ctx, band, slides.length, () => 14000, () => move(1));
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
    swapLayer(stage, old, layer);
    chip.replaceChildren(h('i.dot'), 'EN DIRECTO · ' + ev.label);
    title.textContent = ev.title;
    const lg = h('div.minilogo', null, logoTile(ev.channel.logo, '', { hideMissing: true }));
    sub.replaceChildren(lg, h('span.clamp1', null, eventSubtitle(ev)));
    bar.replaceChildren(ev.start ? progressBar((Date.now() - ev.start) / (ev.stop - ev.start)) : '');
    desc.textContent = D.nowNext(ev.channel)?.now?.d || '';
    favWrap.replaceChildren(favButton('live', ev.channel.id, ev.channel.name, ev.channel.logo, { heart: true, cls: 'pill.round.glass.f' }));
    const next = carouselControls(slides.length, index, move, (i) => { index = i; show(); });
    ctl.replaceWith(next);
    ctl = next;
    rot.schedule(index);
  }
  ctx.cleanup(() => preview && preview.stop());
  show();
  return band;
}

function liveScreen(ctx) {
  const scroller = h('div.scroll');
  if (!S.live.length) return emptyState('No hay canales en esta lista', 'Si tu proveedor los incluye, prueba a actualizar la lista en Ajustes.');
  const events = D.liveNow();
  const sports = events.filter((e) => e.label !== 'NOTICIAS');
  const news = events.filter((e) => e.label === 'NOTICIAS');
  const slides = [...sports.slice(0, 6), ...news.slice(0, 2)];
  const shown = new Set(slides.map((e) => e.channel.id));
  const more = sports.filter((e) => !shown.has(e.channel.id));
  const recent = D.recentChannels();
  D.loadCaptures([...recent, ...events.map((e) => e.channel)].map((c) => c.id)).then((n) => {
    if (n > 0 && scroller.isConnected) refreshCaptures(scroller);
  });

  if (slides.length) {
    scroller.append(liveBand(ctx, slides, (ev) => playChannels(events.map((e) => e.channel), Math.max(0, events.indexOf(ev)))));
  }
  const section = channelSection(ctx, scroller);
  const capIds = [];
  const ROWS = {
    events: () => more.length && shelf('Más deportes y eventos ahora', more, (ev, i) => eventCard(ev, { onClick: () => playChannels(more.map((e) => e.channel), i) }), { live: true }),
    recent: () => recent.length && shelf('Últimos canales', recent, (c, i) => chCard(recent, c, i, { showAge: true }), { live: true }),
    suggest: () => {
      const list = D.suggestedChannels();
      if (!list.length) return null;
      capIds.push(...list.slice(0, 16).map((c) => c.id));
      return shelf('Te puede gustar', list, (c, i) => chCard(list, c, i, { sub: D.nowNext(c)?.now?.t || '' }), { key: 'suggest' });
    },
  };
  for (const r of D.rowsFor('live')) {
    if (!r.on) continue;
    let el = null;
    if (r.def) {
      const list = D.listRow(r.def);
      if (list.length) {
        capIds.push(...list.slice(0, 16).map((c) => c.id));
        el = shelf(r.def.title, list, (c, i) => chCard(list, c, i, { sub: D.nowNext(c)?.now?.t || '' }), { key: r.key, more: () => section._show(r.key) });
      }
    } else if (ROWS[r.key]) el = ROWS[r.key]();
    if (el) scroller.append(el);
  }
  if (capIds.length) D.loadCaptures(capIds).then((n) => { if (n > 0 && scroller.isConnected) refreshCaptures(scroller); });
  scroller.append(organizeBar(ctx, 'live'));
  scroller.append(section);
  return scroller;
}

/** All channels: filters (favourites, recents, themes, category, name) and a grid of rows. */
function channelSection(ctx, scroller) {
  let saved = null;
  try { saved = JSON.parse(sessionStorage.getItem('nt:livefilter') || 'null'); } catch { /* empty */ }
  let filter = saved?.f || 'all';
  let q = saved?.q || '';
  const idx = D.catIndex('live');
  const cats = S.cats.live.filter((c) => idx.has(c.id));
  const catName = new Map(S.cats.live.map((c) => [c.id, c.name]));
  if (filter.startsWith('cat:') && !idx.has(filter.slice(4))) filter = 'all';

  // Filters: everything, Mis canales, recents and the lists switched on in Organizar.
  const lists = D.rowsFor('live').filter((r) => r.def && r.on);
  if (filter.startsWith('theme:') || (filter.startsWith('list:') && !D.listDef(filter.slice(5)))) filter = 'all';
  const chipDefs = [
    ['all', 'Todos', null], ['fav', 'Mis canales', null], ['recent', 'Recientes', null],
    ...lists.map((r) => [r.key, r.title, r.def.color]),
  ];
  const extra = filter.startsWith('list:') && !lists.some((r) => r.key === filter) ? D.listDef(filter.slice(5)) : null;
  if (extra) chipDefs.push(['list:' + extra.key, extra.title, extra.color]);
  const chips = h('div.chips', { dataset: { group: 'chips' } });
  const chipEls = chipDefs.map(([key, label, color]) => {
    const b = h('button.chipbtn.f', { tabIndex: -1, type: 'button', dataset: { f: key } }, color ? h('i', { style: { background: color } }) : null, label);
    b.addEventListener('click', () => setFilter(key));
    chips.append(b);
    return b;
  });
  const select = h('select.selectbox.f', { tabIndex: -1, 'aria-label': 'Categoría' },
    h('option', { value: '' }, 'Todas las categorías'),
    ...cats.map((c) => h('option', { value: c.id }, c.name)));
  select.addEventListener('change', () => setFilter(select.value ? 'cat:' + select.value : 'all'));
  const finput = h('input.f', { type: 'search', placeholder: 'Filtrar por nombre o número', autocomplete: 'off', spellcheck: false });
  finput.value = q;
  finput.addEventListener('input', debounce(() => {
    q = finput.value.trim();
    draw();
  }, 180));
  const count = h('span.count');
  const grid = h('div.chgrid', { dataset: { group: 'chgrid' } });
  const sentinel = h('div.sentinel');
  const head = h('div.chsec-h', null, h('h2', null, 'Canales'), count);
  const sec = h('section.chsec', null, head,
    h('div.chtools', null, chips, h('div.toolsr', null, select, h('label.filterbox', null, icon('search'), finput))),
    grid, sentinel);

  function listFor(key) {
    if (key === 'fav') return D.myChannels();
    if (key === 'recent') return D.recentChannels();
    if (key.startsWith('list:')) {
      const def = D.listDef(key.slice(5));
      if (!def) return [];
      // Channels airing a match of the list now come first, then all the others.
      const now = D.listRow(def, 60);
      const ids = new Set(now.map((c) => c.id));
      return [...now, ...D.listMembers(def).filter((c) => !ids.has(c.id))];
    }
    if (key.startsWith('cat:')) return idx.get(key.slice(4)) || [];
    return S.live;
  }
  function setFilter(key) {
    filter = key;
    draw();
    // Keep the list in view when the filter changes from below.
    const top = sec.getBoundingClientRect().top;
    if (top < 0) scroller.scrollTo({ top: scroller.scrollTop + top - 60, behavior: reduce() ? 'auto' : 'smooth' });
  }
  let list = [], count0 = 0;
  function draw() {
    try { sessionStorage.setItem('nt:livefilter', JSON.stringify({ f: filter, q })); } catch { /* empty */ }
    chipEls.forEach((b) => b.classList.toggle('on', b.dataset.f === filter));
    select.value = filter.startsWith('cat:') ? filter.slice(4) : '';
    select.classList.toggle('on', filter.startsWith('cat:'));
    let base = listFor(filter);
    if (q) {
      const k = D.fold(q);
      base = base.filter((c) => D.fold(c.name).includes(k) || String(c.num) === k);
    }
    list = base;
    count0 = 0;
    grid.replaceChildren();
    count.textContent = `${list.length.toLocaleString('es-ES')} ${list.length === 1 ? 'canal' : 'canales'}`;
    if (!list.length) {
      grid.append(h('div.chempty', null, filter === 'fav' && !q
        ? 'Aún no tienes canales en Mis canales. Pulsa el corazón de un canal para añadirlo.'
        : filter === 'recent' && !q ? 'Aquí aparecerán los últimos canales que veas.' : 'Ningún canal coincide con el filtro.'));
    }
    more();
  }
  function more() {
    const end = Math.min(list.length, count0 + 60);
    const frag = document.createDocumentFragment();
    for (; count0 < end; count0++) frag.append(chRow(list, count0));
    grid.append(frag);
  }
  function chRow(l, i) {
    const c = l[i];
    const nn = D.nowNext(c);
    const fav = favButton('live', c.id, c.name, c.logo, { heart: true, cls: 'fav' });
    const r = h('div.chrow.f', { tabIndex: -1, dataset: { key: 'h' + c.id }, title: c.name },
      h('span.num', null, c.num ? String(c.num) : ''),
      h('div.lg', null, logoTile(c.logo, c.name.slice(0, 2).toUpperCase())),
      h('div.tx', null,
        h('div.n.clamp1', null, c.name),
        nn?.now
          ? h('div.p.clamp1', null, h('span.tm', null, fmt.clock(nn.now.s)), nn.now.t)
          : h('div.p.faint.clamp1', null, catName.get(c.cat) || ''),
        nn?.now ? progressBar((Date.now() - nn.now.s) / (nn.now.e - nn.now.s)) : null),
      fav);
    bindClick(r, () => playChannels(l, i), async () => { await toggleFav('live', c.id, c.name, c.logo); fav._draw(); });
    r.addEventListener('focus', () => { if (i > count0 - 12) more(); });
    return r;
  }
  draw();
  /** "Ver todo" of a row: shows that list here. */
  sec._show = (key) => {
    if (!chipEls.some((b) => b.dataset.f === key)) {
      const def = D.listDef(key.slice(5));
      if (!def) return;
      const b = h('button.chipbtn.f', { tabIndex: -1, type: 'button', dataset: { f: key } }, h('i', { style: { background: def.color } }), def.title);
      b.addEventListener('click', () => setFilter(key));
      chips.append(b);
      chipEls.push(b);
    }
    filter = key;
    q = '';
    finput.value = '';
    draw();
    scroller.scrollTo({ top: scroller.scrollTop + sec.getBoundingClientRect().top - scroller.getBoundingClientRect().top - 4, behavior: reduce() ? 'auto' : 'smooth' });
    const on = chipEls.find((b) => b.dataset.f === key);
    if (on) {
      on.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      if (document.body.classList.contains('kbd')) setTimeout(() => on.focus({ preventScroll: true }), 300);
    }
  };
  const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting) && count0 < list.length) more(); }, { root: scroller, rootMargin: '900px' });
  requestAnimationFrame(() => io.observe(sentinel));
  ctx.cleanup(() => io.disconnect());
  return sec;
}

// ------------------------------------------------------------------ Organizar, Mis canales, listas propias

const ROW_NOTES = {
  mine: 'Tus canales, con el + para añadir', continue: 'Películas y series a medias', recent: 'Lo último que viste en directo',
  movies: 'Bien valoradas y recientes', sports: 'Partidos y eventos que se emiten ahora', series: 'Bien valoradas y recientes',
  news: 'Informativos en emisión', mylist: 'Películas y series que guardaste',
  events: 'Partidos y eventos que se emiten ahora', suggest: 'Parecidos a Mis canales',
};

/** Button at the end of the rows of Inicio and En vivo. */
function organizeBar(ctx, screen) {
  return h('div.orgbar', { dataset: { group: 'org' + screen } },
    button(screen === 'home' ? 'Organizar Inicio' : 'Organizar listas', () => organizeRows(ctx, screen), { icon: 'rows', cls: 'ghost' }));
}

/**
 * Order and visibility of the rows of a screen. Drag by the handle or use ▲ ▼; the
 * switch shows or hides the row. In En vivo also: make a list of your own.
 */
function organizeRows(ctx, screen) {
  const live = screen === 'live';
  const body = h('div.org');
  const intro = h('p.dim.t-caption');
  const holder = h('div');
  const foot = h('div.orgfoot', { dataset: { group: 'orgfoot' } });
  body.append(intro, holder, foot);
  let rows = [];
  const save = () => D.saveRows(screen, rows);
  function draw() {
    rows = D.rowsFor(screen);
    const mine = D.myChannels();
    intro.textContent = live
      ? (D.isAutoOrder('live')
        ? 'Las listas que más encajan con Mis canales van primero. Si mueves alguna, se queda tu orden.'
        : 'Arrastra por el asa o usa las flechas. El interruptor muestra u oculta cada fila.')
      : 'Arrastra por el asa o usa las flechas. El interruptor muestra u oculta cada fila.';
    const list = sortable(rows, (r) => {
      const meta = [];
      if (r.def) {
        const n = D.listMembers(r.def).length;
        meta.push(n ? `${n.toLocaleString('es-ES')} ${n === 1 ? 'canal' : 'canales'}` : r.def.now ? 'Los canales que lo emitan, según la guía' : 'Ningún canal de tu lista encaja');
        const a = D.listAffinity(r.def, mine);
        if (a) meta.push(`${a} de Mis canales`);
        if (r.def.custom) meta.push('Lista tuya');
      } else meta.push(ROW_NOTES[r.key] || '');
      const main = h('div.tx', null,
        h('div.a', null, r.def ? h('i.sw', { style: { background: r.def.color } }) : null, r.title),
        h('div.b', null, meta.join('  ·  ')));
      const extra = [];
      if (r.def?.custom) {
        const ed = h('button.mv.f', { type: 'button', tabIndex: -1, title: 'Editar la lista', 'aria-label': 'Editar la lista' }, icon('edit'));
        ed.addEventListener('click', () => customListDialog(r.def, draw));
        extra.push(ed);
      }
      const sw = toggleSwitch(r.on, (v) => {
        r.on = v;
        sw.closest('.srt')?.classList.toggle('hid', !v);
        sw.title = v ? 'Ocultar fila' : 'Mostrar fila';
        save();
      }, r.on ? 'Ocultar fila' : 'Mostrar fila');
      extra.push(sw);
      return { main, extra };
    }, (order) => {
      rows = order;
      save();
      if (live) intro.textContent = 'Arrastra por el asa o usa las flechas. El interruptor muestra u oculta cada fila.';
    });
    rows.forEach((r, i) => list.children[i].classList.toggle('hid', !r.on));
    holder.replaceChildren(list);
  }
  foot.append(...[
    live ? button('Crear lista', () => customListDialog(null, draw), { icon: 'plus' }) : null,
    button(live ? 'Orden automático' : 'Restablecer', async () => {
      await D.resetRows(screen);
      draw();
      toast(live ? 'Orden automático según Mis canales' : 'Inicio como al principio');
    }, { cls: 'ghost' }),
  ].filter(Boolean));
  draw();
  dialog(live ? 'Organizar En vivo' : 'Organizar Inicio', body, { onClose: () => ctx.refresh(), sub: live ? 'Listas por temas y filas de la pantalla' : 'Filas de la pantalla de Inicio' });
}

/** Makes or edits a list of your own: name, words and categories of your provider. */
function customListDialog(def, after) {
  const words = h('input.f', { type: 'text', placeholder: 'Ej.: Real Madrid, boxeo, DAZN', autocomplete: 'off', spellcheck: false, value: def ? def.words.join(', ') : '' });
  const name = h('input.f', { type: 'text', placeholder: 'Ej.: Mi equipo', autocomplete: 'off', value: def ? def.title : '' });
  if (!def) name.dataset.default = '';
  const picked = new Set(def ? def.cats : []);
  const idx = D.catIndex('live');
  const cats = S.cats.live.filter((c) => idx.has(c.id) && !D.isAdultChannel({ name: '' }, c.name));
  const catFilter = h('input', { type: 'search', placeholder: 'Buscar categoría', autocomplete: 'off', spellcheck: false });
  const catBox = h('div.catpick', { dataset: { group: 'catpick' } });
  const count = h('div.t-caption.dim');
  const current = () => ({
    title: name.value.trim(),
    words: words.value.split(',').map((w) => w.trim()).filter((w) => w.length > 1),
    cats: [...picked],
  });
  const recount = debounce(() => {
    const x = current();
    const n = x.words.length || x.cats.length ? D.previewList(x).length : 0;
    count.textContent = x.words.length || x.cats.length
      ? `${n.toLocaleString('es-ES')} ${n === 1 ? 'canal encaja' : 'canales encajan'} ahora. Con la guía también entran los canales que emitan algo con esas palabras.`
      : 'Escribe palabras o elige categorías.';
  }, 200);
  function drawCats() {
    const k = D.fold(catFilter.value.trim());
    const list = cats.filter((c) => !k || D.fold(c.name).includes(k)).slice(0, 120);
    catBox.replaceChildren(...list.map((c) => {
      const b = h('button.chipbtn.f' + (picked.has(c.id) ? '.on' : ''), { type: 'button', tabIndex: -1 }, c.name, h('span.n', null, String(idx.get(c.id).length)));
      b.addEventListener('click', () => {
        if (picked.has(c.id)) picked.delete(c.id);
        else picked.add(c.id);
        b.classList.toggle('on', picked.has(c.id));
        recount();
      });
      return b;
    }));
    if (!list.length) catBox.append(h('div.t-caption.faint', null, 'Ninguna categoría con ese nombre.'));
  }
  catFilter.addEventListener('input', debounce(drawCats, 150));
  words.addEventListener('input', recount);
  const saveBtn = button('Guardar lista', async () => {
    const x = current();
    if (!x.title) { toast('Ponle un nombre'); name.focus(); return; }
    if (!x.words.length && !x.cats.length) { toast('Añade alguna palabra o categoría'); words.focus(); return; }
    await D.saveCustomList({ key: def ? def.key : 'u' + Date.now().toString(36), ...x });
    dlg.close();
    toast(def ? 'Lista guardada' : 'Lista creada');
    after && after();
  }, { primary: true });
  delete saveBtn.dataset.default;
  const delBtn = def ? button('Borrar lista', async () => {
    if (!confirm(`¿Borrar la lista "${def.title}"?`)) return;
    await D.deleteCustomList(def.key);
    dlg.close();
    toast('Lista borrada');
    after && after();
  }, { cls: 'ghost' }) : null;
  const body = h('div.form',
    null,
    h('label.fld', null, h('span', null, 'Nombre'), name),
    h('label.fld', null, h('span', null, 'Palabras (separadas por comas)'), words),
    h('div.fld', null, h('span', null, 'Categorías de tu lista'), h('label.filterbox', null, icon('search'), catFilter), catBox),
    count,
    h('div.btnset', { dataset: { group: 'formbtns' } }, saveBtn, delBtn));
  drawCats();
  recount();
  const dlg = dialog(def ? 'Editar lista' : 'Crear lista', body, { sub: 'Un canal entra si su nombre tiene alguna de las palabras o si está en una de las categorías.' });
}

/** Mis canales: search your list and add or remove channels; put yours in order. */
function pickChannels(ctx) {
  const q = h('input.f', { type: 'search', placeholder: 'Buscar canal por nombre o número', autocomplete: 'off', spellcheck: false, dataset: { default: '' } });
  const results = h('div.pklist', { dataset: { group: 'pkres' } });
  const resTitle = h('h4');
  const mineBox = h('div');
  const mineTitle = h('h4');
  const catName = new Map(S.cats.live.map((c) => [c.id, c.name]));
  const rowFor = (c) => {
    const nn = D.nowNext(c);
    const on = () => D.isFavorite('live', c.id);
    const btn = h('span.pkbtn');
    const r = h('div.pkrow.f', { tabIndex: -1, dataset: { key: 'p' + c.id }, title: c.name },
      h('div.lg', null, logoTile(c.logo, c.name.slice(0, 2).toUpperCase())),
      h('div.tx', null, h('div.n.clamp1', null, c.name), h('div.p.clamp1', null, nn?.now?.t || catName.get(c.cat) || '')),
      btn);
    const paint = () => {
      r.classList.toggle('on', on());
      btn.innerHTML = on() ? ICON_CHECK : ICON_PLUS;
      r.setAttribute('aria-pressed', String(on()));
    };
    paint();
    r._paint = paint;
    r.addEventListener('click', async () => {
      await D.toggleFavorite('live', c.id, c.name, c.logo);
      paint();
      drawMine();
    });
    return r;
  };
  function suggestionsList() {
    const out = new Map();
    for (const e of D.liveNow()) out.set(e.channel.id, e.channel);
    for (const c of D.suggestedChannels(20)) out.set(c.id, c);
    for (const c of D.recentChannels()) out.set(c.id, c);
    for (const r of D.rowsFor('live').filter((x) => x.def && x.on)) for (const c of D.listRow(r.def, 6)) out.set(c.id, c);
    return [...out.values()].slice(0, 60);
  }
  function drawResults() {
    const k = D.fold(q.value.trim());
    let list;
    if (k) {
      const words = k.split(/\s+/);
      list = S.live.filter((c) => String(c.num) === k || words.every((w) => D.fold(c.name).includes(w))).slice(0, 120);
      resTitle.textContent = list.length ? `Resultados (${list.length >= 120 ? 'los 120 primeros' : list.length})` : 'Ningún canal con ese nombre';
    } else {
      list = suggestionsList();
      resTitle.textContent = 'Sugerencias';
    }
    results.replaceChildren(...list.map(rowFor));
  }
  function drawMine() {
    const mine = D.myChannels();
    mineTitle.textContent = `Mis canales (${mine.length})`;
    if (!mine.length) {
      mineBox.replaceChildren(h('div.t-caption.faint.pkempty', null, 'Busca a la izquierda y pulsa un canal para añadirlo. Aparecerán en Inicio en este orden.'));
      return;
    }
    const srt = sortable(mine, (c) => {
      const rm = h('button.mv.f', { type: 'button', tabIndex: -1, title: 'Quitar', 'aria-label': 'Quitar ' + c.name }, icon('close'));
      rm.addEventListener('click', async () => {
        await D.toggleFavorite('live', c.id, c.name, c.logo);
        drawMine();
        results.querySelectorAll('.pkrow').forEach((r) => r._paint && r._paint());
      });
      return {
        main: h('div.mrow', null, h('div.lg', null, logoTile(c.logo, c.name.slice(0, 2).toUpperCase())), h('div.n.clamp1', null, c.name)),
        extra: [rm],
      };
    }, (order) => D.setMyChannelOrder(order.map((c) => c.id)));
    // ← from your channels goes back to the results.
    srt._dir = (dir, from) => {
      if (dir !== 'left' || window.innerWidth <= 760) return false;
      const row = from.closest('.srt');
      if (row && row.querySelector('.f') !== from) return false;
      const t = (results._last && results._last.isConnected && results._last) || results.querySelector('.f');
      if (t) focus(t);
      return !!t;
    };
    mineBox.replaceChildren(srt);
  }
  // → from the results goes to your channels (two columns side by side).
  results._dir = (dir) => {
    if (dir !== 'right' || window.innerWidth <= 760) return false;
    const t = mineBox.querySelector('.mv.f:not(.off)') || mineBox.querySelector('.f');
    if (t) focus(t);
    return !!t;
  };
  q.addEventListener('input', debounce(drawResults, 180));
  const body = h('div.picker', null,
    h('div.pkcol', null, h('label.filterbox.big', null, icon('search'), q), resTitle, results),
    h('div.pkcol.mine', null, mineTitle, mineBox));
  drawResults();
  drawMine();
  dialog('Mis canales', body, { wide: true, onClose: () => ctx.refresh(), sub: 'Los que elijas salen en Inicio, en este orden, y ayudan a ordenar las listas de En vivo.' });
}
const ICON_PLUS = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>';
const ICON_CHECK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg>';

// ------------------------------------------------------------------ Películas / Series

/** Full-width carousel with wide artwork; text on the left over a dark gradient. */
function showcase(ctx, items, kind) {
  const box = h('div.box.showcase', { dataset: { top: '' } });
  const stage = h('div.stage');
  const eyebrow = h('div.eyebrow');
  const title = h('div.title.clamp2');
  const meta = h('div.meta.clamp1');
  const desc = h('div.desc.clamp3');
  const openBtn = button(kind === 'series' ? 'Ver serie' : 'Ver película', () => open(), { primary: true, icon: 'play' });
  const favWrap = h('span.favwrap');
  const btns = h('div.btns', { dataset: { group: 'showbtns' } }, openBtn, favWrap);
  let ctl = h('div');
  box.append(stage, h('div.grad-l'), h('div.grad-b'), h('div.info', null, eyebrow, title, meta, desc, btns), ctl);
  stage.addEventListener('click', () => open());
  let index = 0, layer = null;
  const rot = rotator(ctx, box, items.length, () => 9000, () => move(1));
  const open = () => {
    const it = items[index].item;
    if (kind === 'series') openSeries(ctx, it);
    else openMovie(ctx, it);
  };
  function move(d) {
    index = (index + d + items.length) % items.length;
    show();
  }
  function show() {
    const { item: it, eyebrow: eb } = items[index];
    const old = layer;
    layer = artLayer(it);
    swapLayer(stage, old, layer);
    eyebrow.textContent = eb;
    title.textContent = cleanTitle(it.name);
    meta.textContent = metaLine(it.year, it.rating, it.genre);
    desc.textContent = it.plot || '';
    favWrap.replaceChildren(favButton(kind, it.id, it.name, it.poster, { cls: 'pill.round.glass.f' }));
    const next = carouselControls(items.length, index, move, (i) => { index = i; show(); });
    ctl.replaceWith(next);
    ctl = next;
    rot.schedule(index);
  }
  show();
  return box;
}

function browseScreen(kind) {
  return (ctx) => {
    const scroller = h('div.scroll');
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
    if (!isSeries && recent.slice(0, 8).some((m) => !m.backdrop)) {
      D.prefetchBackdrops(recent.slice(0, 8)).then((changed) => {
        if (changed && scroller.isConnected && scroller.scrollTop < 200) ctx.refresh();
      });
    }
    const idx = D.catIndex(kind === 'series' ? 'series' : 'vod');
    const cats = (isSeries ? S.cats.series : S.cats.vod).filter((c) => idx.has(c.id));
    const card = (x) => (isSeries ? seriesCard(ctx, x) : movieCard(ctx, x));
    if (cats.length > 1) {
      const sec = shelf('Categorías', cats, (c) => {
        const b = h('button.chipbtn.f', { tabIndex: -1, type: 'button', dataset: { key: 'k' + c.id } }, c.name, h('span.n', null, String(idx.get(c.id).length)));
        b.addEventListener('click', () => ctx.push((cx) => gridPage(cx, kind, c)));
        return b;
      }, { key: 'catchips' });
      sec.classList.add('catrow');
      scroller.append(sec);
    }
    scroller.append(shelf(isSeries ? 'Actualizadas recientemente' : 'Añadidas recientemente', recent, card));
    const favs = S.favorites.filter((f) => f.kind === kind).map((f) => (isSeries ? D.seriesById(f.id) : D.movieById(f.id))).filter(Boolean);
    if (favs.length) scroller.append(shelf('Mi lista', favs, card));
    // Category rows, added as the user scrolls down.
    let next = 0;
    const sentinel = h('div.sentinel');
    const addMore = (n) => {
      const end = Math.min(cats.length, next + n);
      for (; next < end; next++) {
        const c = cats[next];
        const list = D.inCategory(kind === 'series' ? 'series' : 'vod', c.id, 40);
        if (!list.length) continue;
        const total = idx.get(c.id).length;
        scroller.insertBefore(shelf(c.name, list, card, { more: total > list.length || total > 8 ? () => ctx.push((cx) => gridPage(cx, kind, c)) : null }), sentinel);
      }
    };
    scroller.append(sentinel);
    addMore(6);
    const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting) && next < cats.length) addMore(5); }, { root: scroller, rootMargin: '700px' });
    requestAnimationFrame(() => io.observe(sentinel));
    ctx.cleanup(() => io.disconnect());
    return scroller;
  };
}

/** Back button at the top of the pages opened over a section. */
function backRow(ctx, ...extra) {
  return h('div.backrow', null, button('Volver', () => ctx.back(), { icon: 'back', cls: 'ghost' }), ...extra);
}

function gridPage(ctx, kind, cat) {
  const isSeries = kind === 'series';
  const base = D.catIndex(isSeries ? 'series' : 'vod').get(cat.id) || [];
  let sort = 0;
  const sorters = [
    (a, b) => b.added - a.added,
    (a, b) => b.rating - a.rating || b.added - a.added,
    (a, b) => cleanTitle(a.name).localeCompare(cleanTitle(b.name), 'es'),
  ];
  let list = base;
  const grid = h('div.pgrid', { dataset: { group: 'grid' } });
  const sentinel = h('div.sentinel');
  let shown = 0;
  const more = () => {
    const end = Math.min(list.length, shown + 90);
    const frag = document.createDocumentFragment();
    for (; shown < end; shown++) frag.append(isSeries ? seriesCard(ctx, list[shown]) : movieCard(ctx, list[shown]));
    grid.append(frag);
  };
  const draw = () => {
    list = [...base].sort(sorters[sort]);
    shown = 0;
    grid.replaceChildren();
    more();
  };
  const sortSeg = segmented(['Recientes', 'Mejor valoradas', 'A–Z'], 0, (i) => { sort = i; draw(); });
  const scroller = h('div.scroll', null,
    backRow(ctx),
    h('div.gridhead', null, h('div', null, h('h1.t-title', null, cat.name), h('div.dim', null, `${base.length.toLocaleString('es-ES')} ${isSeries ? 'series' : 'películas'}`)), h('div.grow'), sortSeg),
    grid, sentinel);
  draw();
  const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting) && shown < list.length) more(); }, { root: scroller, rootMargin: '900px' });
  requestAnimationFrame(() => io.observe(sentinel));
  ctx.cleanup(() => io.disconnect());
  return scroller;
}

// ------------------------------------------------------------------ detail pages

function detailBg(image, wide) {
  return h('div.detail-bg' + (wide ? '' : '.blur'), null, coverImg(image, 'x'));
}

function moviePage(ctx, m) {
  const root = h('div.scroll.detailpage');
  const bgWrap = h('div');
  const body = h('div.detail');
  const rows = h('div.detail-rows');
  root.append(bgWrap, backRow(ctx), body, rows);
  const draw = (d) => {
    const backdrop = d?.backdrop || m.backdrop;
    bgWrap.replaceChildren(detailBg(backdrop || d?.poster || m.poster, !!backdrop));
    const prog = S.progress['movie:' + m.id];
    const meta = [metaLine(d?.year || m.year, d?.rating || m.rating, d?.genre), d?.duration].filter(Boolean).join('  ·  ');
    const btns = h('div.btnrow', { dataset: { group: 'mbtns' } });
    if (prog && prog.pos > 0) {
      btns.append(button(`Reanudar · ${fmt.duration(prog.pos)}`, () => play([movieItem(m)], 0, prog.pos), { primary: true, icon: 'play' }));
      btns.append(button('Desde el principio', () => play([movieItem(m)], 0, 0)));
    } else btns.append(button('Reproducir', () => play([movieItem(m)], 0, 0), { primary: true, icon: 'play' }));
    btns.append(favPill('movie', m));
    put(body,
      h('div.eyebrow', null, 'PELÍCULA'),
      h('h1.t-display.clamp2', null, cleanTitle(m.name)),
      meta ? h('div.meta', null, meta) : null,
      d?.plot || m.plot ? h('p.plot', null, d?.plot || m.plot) : null,
      d?.director ? h('div.credits', null, h('span.faint', null, 'Dirección  '), d.director) : null,
      d?.cast ? h('div.credits.clamp2', null, h('span.faint', null, 'Reparto  '), d.cast) : null,
      btns,
    );
    const sim = D.similarMovies(m);
    rows.replaceChildren(sim.length ? shelf('Más como esto', sim, (x) => movieCard(ctx, x)) : '');
  };
  draw(null);
  D.movieDetails(m).then((d) => {
    if (!root.isConnected) return;
    const a = document.activeElement;
    const keep = a && root.contains(a) ? a.textContent : null;
    draw(d);
    const again = keep && [...root.querySelectorAll('.f')].find((x) => x.textContent === keep);
    if (again) again.focus({ preventScroll: true });
  }).catch(() => {});
  return root;
}

/** "Mi lista" pill that updates its own label. */
function favPill(kind, x) {
  const b = button('', async () => {
    await toggleFav(kind, x.id, x.name, x.poster);
    draw();
  });
  const draw = () => {
    const on = D.isFavorite(kind, x.id);
    b.replaceChildren(icon(on ? 'check' : 'plus'), h('span', null, on ? 'En Mi lista' : 'Mi lista'));
  };
  draw();
  return b;
}

function seriesPage(ctx, s) {
  const root = h('div.scroll.detailpage');
  const bgWrap = h('div');
  const body = h('div.detail');
  const below = h('div.detail-rows');
  root.append(bgWrap, backRow(ctx), body, below);
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
    const meta = [metaLine(d?.year || s.year, d?.rating || s.rating, d?.genre || s.genre), seasons.length ? `${seasons.length} temporada${seasons.length > 1 ? 's' : ''}` : null].filter(Boolean).join('  ·  ');
    const btns = h('div.btnrow', { dataset: { group: 'sbtns' } },
      button(!d && !error ? 'Cargando…' : !target ? 'Sin episodios' : `${resumeEp ? 'Continuar' : 'Reproducir'} T${target.season} · E${target.episode}`, () => target && playEp(target, startMs), { primary: true, icon: 'play' }),
      favPill('series', s));
    put(body,
      h('div.eyebrow', null, 'SERIE'),
      h('h1.t-display.clamp2', null, cleanTitle(s.name)),
      meta ? h('div.meta', null, meta) : null,
      d?.plot || s.plot ? h('p.plot', null, d?.plot || s.plot) : null,
      btns,
    );
    below.replaceChildren();
    if (!d && !error) below.append(h('div.loadbox', null, loading()));
    else if (!seasons.length) below.append(h('div.loadbox', null, emptyState('No se encontraron episodios', error ? 'El servidor no respondió. Vuelve a intentarlo más tarde.' : null)));
    else {
      const season = seasons[Math.max(0, seasonIdx)] || seasons[0];
      const head = h('div.eps-h', null, h('h2', null, 'Episodios'));
      if (seasons.length > 1) {
        const sel = h('select.selectbox.f', { tabIndex: -1, 'aria-label': 'Temporada' }, ...seasons.map((x, i) => h('option', { value: String(i) }, x.name)));
        sel.value = String(Math.max(0, seasonIdx));
        sel.addEventListener('change', () => {
          seasonIdx = parseInt(sel.value, 10);
          draw(d, error);
        });
        head.append(sel);
      } else head.append(h('span.dim', null, season.name));
      below.append(head);
      const grid = h('div.epgrid', { dataset: { group: 'eps' } });
      season.episodes.forEach((e) => {
        const p = S.progress['episode:' + e.id];
        const frac = p && p.dur ? p.pos / p.dur : 0;
        const watched = frac >= 0.92;
        grid.append(posterCard({
          title: `${e.episode}. ${e.title}`, image: e.image || backdrop || s.poster, key: 'e' + e.id, wide: true,
          subtitle: watched ? 'Visto' : e.secs ? Math.round(e.secs / 60) + ' min' : null,
          progress: watched ? 0 : frac,
          onClick: () => playEp(e, p && !watched ? p.pos : 0),
        }));
      });
      below.append(grid);
    }
    const sim = D.similarSeries(s);
    if (sim.length) below.append(shelf('Más como esto', sim, (x) => seriesCard(ctx, x)));
  };
  draw(null, false);
  D.seriesDetails(s).then((d) => {
    if (!root.isConnected) return;
    draw(d, false);
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

// ------------------------------------------------------------------ Buscar (the box is in the top bar)

function searchScreen(ctx) {
  const results = h('div');
  const title = h('h1.t-title');
  const wrap = h('div.scroll', null, h('div.searchhead', null, title), results);
  let last = null;
  const run = (raw) => {
    const q = (raw || '').trim();
    if (q === last) return;
    last = q;
    results.replaceChildren();
    if (q.length < 2) {
      title.textContent = 'Buscar';
      results.append(h('div.hint', null, 'Escribe al menos dos letras en el buscador de arriba para encontrar canales, películas y series.'));
      return;
    }
    title.textContent = `Resultados para «${q}»`;
    const r = D.search(q);
    if (!r.live.length && !r.vod.length && !r.series.length) {
      results.append(h('div.hint', null, `Sin resultados para «${q}».`));
      return;
    }
    if (r.live.length) results.append(shelf('Canales', r.live, (c, i) => chCard(r.live, c, i), { live: true }));
    if (r.vod.length) results.append(shelf('Películas', r.vod, (m) => movieCard(ctx, m)));
    if (r.series.length) results.append(shelf('Series', r.series, (s) => seriesCard(ctx, s)));
  };
  const deb = debounce(run, 200);
  ctx.onQuery(deb);
  run(ctx.query());
  return wrap;
}

// ------------------------------------------------------------------ Ajustes

function settingsScreen(ctx) {
  const wrap = h('div.scroll');
  const s = h('div.settings');
  wrap.append(s);
  const row = (title, desc, control) => h('div.srow', null, h('div.tx', null, h('div.a', null, title), h('div.b', null, desc)), control);
  const yesNo = (key, after) => segmented(['No', 'Sí'], D.prefs[key] ? 1 : 0, (i) => { D.prefs[key] = i === 1; after && after(); });
  const pl = S.playlist;
  const when = S.lastSync ? new Date(S.lastSync).toLocaleString('es-ES', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : 'sin descargar';
  const keys = [
    ['Flechas', 'Moverse por la web, como con el mando'], ['Intro', 'Abrir · mantener: añadir a Mi lista'], ['Esc', 'Volver'],
    ['Espacio', 'Pausa (reproductor)'], ['◀ ▶', 'Retroceder / avanzar 10 s'], ['▲ ▼', 'Volumen · en directo, cambiar de canal'],
    ['F', 'Pantalla completa'], ['M', 'Silencio'], ['C', 'Subtítulos y audio'], ['L', 'Lista de canales'], ['0–9', 'Ir a un canal por su número'],
  ];
  s.append(
    h('h1.t-title', null, 'Ajustes'),
    h('h2', null, 'Lista'),
    row(pl ? pl.name : 'Sin lista', pl ? `${pl.type === 'xtream' ? 'Xtream Codes · ' + pl.user : 'Lista M3U'}  ·  actualizada ${when}  ·  ${S.live.length.toLocaleString('es-ES')} canales, ${S.vod.length.toLocaleString('es-ES')} películas, ${S.series.length.toLocaleString('es-ES')} series` : '',
      h('div.btnset', { dataset: { group: 'plbtns' } },
        button('Actualizar', () => app().runSync(true)),
        button('Cambiar', () => ctx.push(app().playlistScreen(false), { auto: false })),
        button('Quitar', async () => { if (!confirm('¿Quitar la lista de este navegador?')) return; await D.forgetPlaylist(); location.reload(); }))),
    row('Guía de programación', S.epgAt ? `Cargada ${new Date(S.epgAt).toLocaleString('es-ES', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })} · ${S.epg.size.toLocaleString('es-ES')} canales con guía` : 'Todavía no se ha cargado. Se actualiza sola cada 6 horas.',
      button('Cargar ahora', async () => {
        toast('Cargando la guía…');
        try {
          await D.syncEpg();
          toast('Guía cargada');
        } catch (e) { toast(e.message); }
      })),
    h('h2', null, 'Reproducción'),
    row('Formato del directo (Xtream)', 'HLS va mejor en el navegador. Si un canal no arranca, prueba TS.', segmented(['HLS', 'TS'], D.prefs.liveFormat === 'ts' ? 1 : 0, (i) => { D.prefs.liveFormat = i ? 'ts' : 'm3u8'; })),
    row('Reproductor de películas y series', 'Automático: MKV con el reproductor MKV de la web (idiomas y subtítulos del archivo) y el resto con el del navegador; si uno no puede, prueba el otro. Las demás opciones usan siempre ese reproductor, para comparar. En la pantalla de error también puedes probar otro.',
      segmented(['Automático', 'Navegador', 'MKV (movi)', 'avbridge'], Math.max(0, ['auto', 'native', 'movi', 'avbridge'].indexOf(D.prefs.vodPlayer || (D.prefs.mkvPlayer === false ? 'native' : 'auto'))), (i) => { D.prefs.vodPlayer = ['auto', 'native', 'movi', 'avbridge'][i]; })),
    row('Mosca de nansu tv', 'Marca de agua al 30 % en la esquina inferior izquierda. Solo en canales en vivo.', yesNo('watermark')),
    row('Tamaño de los subtítulos', 'XS es el tamaño de Apple TV.', segmented(['XS', 'S', 'M', 'L', 'XL'], D.prefs.subtitleSize, (i) => { D.prefs.subtitleSize = i; })),
    row('Estilo de los subtítulos', 'Sombra suave, o caja negra translúcida como en Apple TV.', segmented(['Sin fondo', 'Fondo'], D.prefs.subtitleBackground ? 1 : 0, (i) => { D.prefs.subtitleBackground = i === 1; })),
    h('h2', null, 'Interfaz'),
    row('Filas de Inicio y En vivo', 'Elige qué filas y listas ves y en qué orden. También desde el botón Organizar al final de cada pantalla.',
      h('div.btnset', { dataset: { group: 'orgbtns' } },
        button('Inicio', () => organizeRows({ refresh: () => {} }, 'home'), { icon: 'rows' }),
        button('En vivo', () => organizeRows({ refresh: () => {} }, 'live'), { icon: 'rows' }),
        button('Mis canales', () => pickChannels({ refresh: () => {} }), { icon: 'heart' }))),
    row('Miniaturas en directo', 'Inicio va tomando, de uno en uno y en segundo plano, una imagen de lo que emite cada canal (cada 20 minutos). Se para mientras ves algo. Cada imagen gasta 1–2 MB de tu hosting.', yesNo('autoThumbs')),
    row('Vista previa en directo', 'Los destacados de Inicio y En vivo muestran el canal en movimiento y sin sonido. Usa una conexión de tu proveedor mientras se ve.', yesNo('livePreview')),
    row('Reducir animaciones', 'Quita el zoom lento y los fundidos. Útil en ordenadores poco potentes.', yesNo('reduceMotion', () => document.body.classList.toggle('reduce', D.prefs.reduceMotion))),
    row('Conexión directa', 'Sin pasar por tu servidor. Solo funciona si tu proveedor usa https y lo permite; si algo deja de cargar, vuelve a "No".', yesNo('direct')),
    row('Prueba de velocidad', 'Mide cuánto tarda en empezar una película a través de tu hosting y si tu proveedor permite la conexión directa (más rápida al saltar en la línea de tiempo).', speedTest()),
    h('h2', null, 'Teclado'),
    h('div.keys', null, ...keys.map(([k, d]) => h('div.key', null, h('kbd', null, k), h('span', null, d)))),
    h('h2', null, 'Acceso'),
    row('Cerrar sesión', 'Para entrar de nuevo hará falta la contraseña de la web.', button('Cerrar sesión', async () => { await D.authPost('logout', ''); location.reload(); })),
    h('div.t-caption.faint', { style: { marginTop: '32px' } }, 'nansu tv web · Reproductor personal. La web no incluye contenido. Tipografía Inter (OFL), hls.js, mpegts.js y movi-player (Apache 2.0; FFmpeg bajo LGPL).'),
  );
  return wrap;
}

/** Reads a proxy error into words for the speed test. */
async function proxyError(r) {
  let j = null;
  try { j = await r.json(); } catch { /* not JSON */ }
  if (j && j.error === 'connect') return D.explainFailure(r.status, j).replace(/\.$/, '');
  if (r.status === 502 && !j) return 'tu hosting cortó la petición (error 502 del servidor)';
  if (j && j.error === 'connect') return 'tu hosting no logra conectar con el proveedor' + (j.msg ? ` (${j.msg})` : '');
  if (r.status === 401) return 'la sesión caducó, vuelve a entrar';
  if ([403, 429, 458, 509, 503].includes(r.status)) return `el proveedor rechazó la conexión (código ${r.status}); puede que haya otra conexión abierta con tu cuenta`;
  return `el proveedor respondió ${r.status}`;
}

/** Downloads a byte range through the hosting: [seconds to first byte, total seconds, bytes]. */
async function timedRange(url, from, to) {
  const t0 = performance.now();
  const r = await fetch(D.viaProxy(url), { credentials: 'same-origin', headers: { Range: `bytes=${from}-${to}` } });
  const first = (performance.now() - t0) / 1000;
  if (!r.ok) throw new Error(await proxyError(r));
  const reader = r.body.getReader();
  let bytes = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    bytes += value.length;
  }
  return [first, (performance.now() - t0) / 1000, bytes];
}

const fmtS = (x) => x.toFixed(1).replace('.', ',');

/**
 * Settings: measures the hosting with a movie (trying a few, as one may be broken),
 * detects whether the hosting holds each piece until it is complete, and then picks
 * the size of the pieces for this browser. Also checks the direct connection.
 */
function speedTest() {
  const out = h('div.speed');
  const btn = button('Probar', async () => {
    const seen = new Set();
    const cands = [
      ...Object.values(S.progress).filter((p) => p.kind === 'movie' && p.url).map((p) => ({ name: p.title, url: p.url })),
      ...D.recentMovies(6).map((m) => ({ name: cleanTitle(m.name), url: D.movieUrl(m) })),
    ].filter((c) => !seen.has(c.url) && seen.add(c.url)).slice(0, 3);
    if (!cands.length) {
      out.textContent = 'No hay películas en la lista para probar.';
      return;
    }
    btn.disabled = true;
    const lines = [];
    const say = (msg) => out.replaceChildren(...lines.map((l) => h('div', null, l)), msg ? h('div.dim', null, msg) : '');
    let ok = null, lastErr = '';
    for (const c of cands) {
      say(`Probando con «${c.name}»…`);
      try {
        const small = await timedRange(c.url, 0, 262143);
        const big = await timedRange(c.url, 262144, 262144 + 6 * 1048576 - 1);
        ok = { c, small, big };
        break;
      } catch (e) {
        lastErr = e.message;
      }
    }
    if (!ok) {
      lines.push(`Por tu hosting: falló, ${lastErr}.`);
      say();
      btn.disabled = false;
      return;
    }
    const [f1] = ok.small;
    const [f2, t2, bytes] = ok.big;
    const bps = bytes / Math.max(0.05, t2 - f2 > 0.3 ? t2 - f2 : t2);
    lines.push(`Por tu hosting: responde en ${fmtS(f1)} s · ${Math.round((bytes * 8) / 1e6 / Math.max(0.05, t2))} Mbit/s`);
    // If 6 MB take much longer to *start* than 256 KB, the hosting holds pieces until complete:
    // then smaller pieces start sooner.
    const holds = f2 - f1 > 1;
    let chunk = 8;
    if (holds) chunk = Math.max(2, Math.min(8, Math.round((bps * 1.5) / 1048576)));
    try {
      if (chunk === 8) document.cookie = 'nt_chunk=; path=/; max-age=0; SameSite=Lax';
      else document.cookie = `nt_chunk=${chunk * 1048576}; path=/; max-age=31536000; SameSite=Lax`;
    } catch { /* empty */ }
    lines.push(holds
      ? `Tu hosting espera a tener cada trozo entero antes de enviarlo: ajustado a trozos de ${chunk} MB para que arranque antes.`
      : 'Tu hosting envía el vídeo según llega: trozos de 8 MB.');
    const direct = D.directVodUrl(ok.c.url);
    if (direct) {
      say('Probando la conexión directa…');
      const t = await new Promise((res) => {
        const v = document.createElement('video');
        v.muted = true;
        v.preload = 'metadata';
        const t0 = performance.now();
        const end = (val) => { clearTimeout(tm); v.removeAttribute('src'); try { v.load(); } catch { /* empty */ } res(val); };
        const tm = setTimeout(() => end(null), 8000);
        v.onloadedmetadata = () => end((performance.now() - t0) / 1000);
        v.onerror = () => end(null);
        v.src = direct;
      });
      D.directMemo.set(direct, t != null);
      lines.push(t != null
        ? `Conexión directa: sí, arranca en ${fmtS(t)} s (películas y series la usan solas).`
        : 'Conexión directa: tu proveedor no la permite, todo va por tu hosting.');
    }
    say();
    btn.disabled = false;
  });
  return h('div.speedbox', null, out, btn);
}

export const SCREENS = {
  home: homeScreen,
  live: liveScreen,
  movies: browseScreen('movie'),
  series: browseScreen('series'),
  search: searchScreen,
  settings: settingsScreen,
};

export { picture, focus };

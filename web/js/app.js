// nansu tv web: start-up (access, list), the main shell (top bar) and navigation between pages.
import { h, fmt, toast } from './util.js';
import { focus, onKey, pushLayer, popLayer, reveal } from './nav.js';
import { ICONS, icon, loading, button, Preview } from './ui.js';
import * as D from './data.js';
import { SCREENS } from './screens.js';
import { isPlaying } from './player.js';

const app = document.getElementById('app');
if (D.prefs.reduceMotion) document.body.classList.add('reduce');

// ------------------------------------------------------------------ focus keys (restore focus after re-render)

function fkOf(el) {
  const shelf = el.closest('.shelf');
  const group = shelf ? shelf.querySelector('h3')?.textContent : el.closest('[data-group]')?.dataset.group || '';
  return (group || '') + '|' + (el.dataset.key || el.textContent.trim().slice(0, 50));
}
function findFk(root, fk) {
  return [...root.querySelectorAll('.f')].find((el) => fkOf(el) === fk) || null;
}

// ------------------------------------------------------------------ pages stack

const stack = []; // {el, render, dispose, fk, scroll}
export const nav = {
  push(factory, opts = {}) {
    const prev = stack[stack.length - 1];
    if (prev) {
      saveState(prev);
      prev.el.classList.add('under');
    }
    Preview.stop();
    const page = { factory, el: h('div.page'), disposers: [], noAutoRefresh: opts.auto === false };
    // The main screen shares its keyboard area with the top bar.
    page.layer = opts.withBar ? app : page.el;
    stack.push(page);
    app.append(page.el);
    pushLayer(page.layer);
    renderPage(page, true);
    syncNavBar();
  },
  back() {
    if (stack.length <= 1) return false;
    closeTop();
    showTop();
    return true;
  },
  /** Closes every page opened over the main screen. */
  home() {
    if (stack.length <= 1) return false;
    while (stack.length > 1) closeTop();
    showTop();
    return true;
  },
  refresh() {
    const top = stack[stack.length - 1];
    if (!top) return;
    // The main screen only redraws the current section.
    const inner = top.el.firstElementChild;
    if (inner && inner._refresh) {
      inner._refresh();
      return;
    }
    saveState(top);
    renderPage(top, false);
  },
};

function closeTop() {
  const page = stack.pop();
  dispose(page);
  popLayer(page.layer);
  page.el.remove();
}
function showTop() {
  const top = stack[stack.length - 1];
  top.el.classList.remove('under');
  if (top.dirty) {
    top.dirty = false;
    nav.refresh();
  } else restoreState(top);
  syncNavBar();
}

function dispose(page) {
  page.disposers.forEach((f) => { try { f(); } catch { /* empty */ } });
  page.disposers = [];
}

const SCROLLERS = '.scroll, .row, .chs';
function saveState(page) {
  const a = document.activeElement;
  if (a && page.el.contains(a) && a.classList.contains('f')) page.fk = fkOf(a);
  page.scroll = [...page.el.querySelectorAll(SCROLLERS)].map((s) => [s.scrollTop, s.scrollLeft]);
}
function restoreState(page) {
  const scrolls = page.el.querySelectorAll(SCROLLERS);
  (page.scroll || []).forEach(([t, l], i) => {
    if (scrolls[i]) {
      scrolls[i].scrollTop = t;
      scrolls[i].scrollLeft = l;
    }
  });
  const el = page.fk && findFk(page.el, page.fk);
  if (el) {
    el.focus({ preventScroll: true });
    if (document.body.classList.contains('kbd')) focus(el, { instant: true });
  } else if (!page.scroll) {
    const first = page.el.querySelector('.f[data-default]') || page.el.querySelector('.f');
    if (first) first.focus({ preventScroll: true });
  }
}

function renderPage(page, fresh) {
  dispose(page);
  const ctx = {
    cleanup: (fn) => page.disposers.push(fn),
    refresh: () => nav.refresh(),
    push: nav.push,
    back: nav.back,
  };
  page.el.replaceChildren(page.factory(ctx));
  requestAnimationFrame(() => (fresh ? restoreState({ ...page, fk: null, scroll: null }) : restoreState(page)));
}

// Catalogue changes (a sync, the guide) redraw the screen. User changes (favourites,
// progress) update the card that changed; other pages redraw when shown again.
let dirtyTimer;
D.onChange((scope) => {
  stack.forEach((p, i) => { if (i < stack.length - 1) p.dirty = true; });
  const top = stack[stack.length - 1];
  if (scope !== 'catalog' || isPlaying()) {
    if (top) top.dirty = true;
    return;
  }
  clearTimeout(dirtyTimer);
  dirtyTimer = setTimeout(() => {
    const t = stack[stack.length - 1];
    if (t && !t.noAutoRefresh) {
      t.dirty = false;
      nav.refresh();
    }
  }, 400);
});

// Coming back from the player redraws what changed (progress, captures, recents).
document.addEventListener('nt:playerclosed', () => {
  const top = stack[stack.length - 1];
  if (top && top.dirty && !top.noAutoRefresh) {
    top.dirty = false;
    nav.refresh();
  }
});

// Back: closes pages, then goes to Inicio.
onKey((e) => {
  const typing = ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName);
  if (!(e.key === 'Escape' || e.key === 'BrowserBack' || e.key === 'GoBack' || (e.key === 'Backspace' && !typing))) return false;
  if (typing && e.key === 'Escape') {
    document.activeElement.blur();
    return true;
  }
  if (nav.back()) return true;
  if (shell.handleBack()) return true;
  return true;
});

// ------------------------------------------------------------------ main shell (top bar + sections)

const TABS = [
  ['home', 'Inicio'],
  ['live', 'En vivo'],
  ['movies', 'Películas'],
  ['series', 'Series'],
];

const shell = {
  tab: 'home',
  query: '',
  onQuery: null,
  links: [],
  content: null,
  setTab: null,
  bar: null,
  handleBack() {
    if (this.tab !== 'home') {
      this.setTab('home', true);
      return true;
    }
    return true;
  },
};

/** The bar turns solid once the page under it scrolls. */
function syncNavBar() {
  const bar = shell.bar;
  if (!bar) return;
  const top = stack[stack.length - 1];
  const sc = top && top.el.querySelector('.scroll');
  bar.classList.toggle('solid', !!sc && sc.scrollTop > 8);
  bar.classList.toggle('onpage', stack.length > 1);
}
document.addEventListener('scroll', (e) => {
  const t = e.target;
  if (shell.bar && t.classList && t.classList.contains('scroll')) shell.bar.classList.toggle('solid', t.scrollTop > 8);
}, { capture: true, passive: true });

function topBar() {
  const bar = h('header.topnav', { dataset: { group: 'topnav', enter: 'up' } });
  const brand = h('button.brand.f', { tabIndex: -1, type: 'button', title: 'Inicio', 'aria-label': 'Inicio' });
  brand.addEventListener('click', () => goTab('home'));
  const links = h('nav.links');
  shell.links = TABS.map(([key, label]) => {
    const b = h('button.lnk.f', { tabIndex: -1, type: 'button', dataset: { tab: key } }, label);
    b.addEventListener('click', () => goTab(key));
    links.append(b);
    return b;
  });
  const input = h('input.f', { type: 'search', placeholder: 'Buscar', autocomplete: 'off', spellcheck: false, 'aria-label': 'Buscar canales, películas y series' });
  const search = h('label.searchbox', null, icon('search'), input);
  input.addEventListener('input', () => {
    shell.query = input.value;
    if (stack.length > 1) nav.home();
    if (input.value.trim() && shell.tab !== 'search') shell.setTab('search', false);
    else if (shell.onQuery) shell.onQuery(input.value);
  });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      e.stopPropagation();
      const first = shell.content && shell.content.querySelector('.f');
      if (first) focus(first);
    }
  });
  shell.searchInput = input;
  const statusEl = h('div.status');
  const clockEl = h('span.clock');
  const gear = h('button.iconbtn.f', { tabIndex: -1, type: 'button', title: 'Ajustes', 'aria-label': 'Ajustes', dataset: { tab: 'settings' }, html: ICONS.settings });
  gear.addEventListener('click', () => goTab('settings'));
  shell.links.push(gear);
  bar.append(brand, links, h('div.grow'), statusEl, search, gear, clockEl);
  const tick = () => { clockEl.textContent = fmt.clock(Date.now()); };
  tick();
  clearInterval(shell.clockTimer);
  shell.clockTimer = setInterval(tick, 15000);
  shell.status = (msg) => {
    statusEl.replaceChildren();
    statusEl.title = msg || '';
    if (msg) statusEl.append(h('div.spinner'), h('span.clamp1', null, msg));
  };
  shell.status(syncMessage);
  // ↓ from the bar goes back to where the user was in the content.
  bar._dir = (dir) => {
    if (dir !== 'down') return false;
    const content = shell.content;
    const last = content && content._last;
    if (last && last.isConnected && content.contains(last)) focus(last);
    else {
      const first = content && (content.querySelector('.f[data-default]') || content.querySelector('.f'));
      if (first) focus(first);
    }
    return true;
  };
  bar._entry = () => shell.links.find((l) => l.dataset.tab === shell.tab) || shell.links[0];
  shell.bar = bar;
  return bar;
}

function goTab(key) {
  nav.home();
  if (key !== 'search' && shell.searchInput) {
    shell.searchInput.value = '';
    shell.query = '';
  }
  shell.setTab(key, false);
  const sc = shell.content && shell.content.querySelector('.scroll');
  if (sc) sc.scrollTop = 0;
  syncNavBar();
}

function mainShell(ctx) {
  const root = h('div.screen');
  const content = h('div.screen', { dataset: { group: 'content' } });

  let screenCleanup = [];
  function renderTab(fk, scrolls) {
    screenCleanup.forEach((f) => { try { f(); } catch { /* empty */ } });
    screenCleanup = [];
    shell.onQuery = null;
    Preview.stop();
    const sctx = {
      cleanup: (fn) => screenCleanup.push(fn),
      push: nav.push,
      selectTab: (t) => goTab(t),
      query: () => shell.query,
      onQuery: (fn) => { shell.onQuery = fn; },
      refresh: () => {
        const a = document.activeElement;
        const scrolls = [...content.querySelectorAll('.scroll, .row')].map((s) => [s.scrollTop, s.scrollLeft]);
        renderTab(a && content.contains(a) && a.classList.contains('f') ? fkOf(a) : null, scrolls);
      },
    };
    content.replaceChildren(SCREENS[shell.tab](sctx));
    shell.links.forEach((r) => r.classList.toggle('cur', r.dataset.tab === shell.tab));
    if (scrolls) {
      const now = content.querySelectorAll('.scroll, .row');
      scrolls.forEach(([t, l], i) => {
        if (now[i]) {
          now[i].scrollTop = t;
          now[i].scrollLeft = l;
        }
      });
    }
    if (fk) requestAnimationFrame(() => {
      const el = findFk(content, fk);
      if (el) el.focus({ preventScroll: true });
    });
    syncNavBar();
  }
  ctx.cleanup(() => screenCleanup.forEach((f) => { try { f(); } catch { /* empty */ } }));

  function setTab(key, focusContent) {
    if (key !== shell.tab) {
      shell.tab = key;
      try { sessionStorage.setItem('nt:tab', key); } catch { /* empty */ }
      renderTab();
    }
    if (focusContent) {
      setTimeout(() => {
        const first = content.querySelector('.f[data-default]') || content.querySelector('.f');
        if (first) focus(first);
      }, 60);
    }
  }
  shell.setTab = setTab;
  shell.content = content;

  renderTab();
  root.append(content);
  root._refresh = () => {
    const a = document.activeElement;
    const inContent = a && content.contains(a) && a.classList.contains('f');
    const scrolls = [...content.querySelectorAll('.scroll, .row')].map((s) => [s.scrollTop, s.scrollLeft]);
    renderTab(inContent ? fkOf(a) : null, scrolls);
  };
  return root;
}

let syncMessage = null;
function setSyncMessage(m) {
  syncMessage = m;
  if (shell.status) shell.status(m);
}

async function backgroundRefresh() {
  const day = 24 * 3600000;
  try {
    if (Date.now() - D.S.lastSync > day) {
      await D.sync((m) => setSyncMessage('Actualizando: ' + m));
    }
  } catch (e) {
    toast('No se pudo actualizar la lista: ' + e.message);
  }
  try {
    if (Date.now() - D.S.epgAt > 6 * 3600000 && D.epgUrl()) {
      setSyncMessage('Cargando la guía…');
      await D.syncEpg((m) => setSyncMessage(m));
    }
  } catch (e) {
    toast('No se pudo cargar la guía: ' + e.message);
  }
  setSyncMessage(null);
}

// ------------------------------------------------------------------ access and list screens

function authScreen(setup) {
  return () => {
    const input = h('input.input.f', { type: 'password', placeholder: setup ? 'Nueva contraseña (mínimo 6 caracteres)' : 'Contraseña', autocomplete: setup ? 'new-password' : 'current-password' });
    const input2 = setup ? h('input.input.f', { type: 'password', placeholder: 'Repite la contraseña', autocomplete: 'new-password' }) : null;
    const err = h('div.err');
    const go = async () => {
      err.textContent = '';
      if (setup && input.value !== input2.value) {
        err.textContent = 'Las contraseñas no coinciden';
        return;
      }
      try {
        await D.authPost(setup ? 'setup' : 'login', input.value);
        start();
      } catch (e) {
        err.textContent = e.message === 'wrong' ? 'Contraseña incorrecta' : e.message === 'short' ? 'Mínimo 6 caracteres' : 'No se pudo entrar (' + e.message + ')';
      }
    };
    [input, input2].forEach((i) => i && i.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); }));
    const btn = button(setup ? 'Crear contraseña y entrar' : 'Entrar', go, { primary: true });
    return h('div.center', null,
      h('div.brand-big'),
      h('div.form', null,
        h('div.t-title', { style: { textAlign: 'center' } }, setup ? 'Protege tu nansu tv' : 'Entrar'),
        setup ? h('div.dim', { style: { textAlign: 'center' } }, 'Es la primera vez que se abre esta web. Crea una contraseña para que solo tú puedas usarla; se guarda cifrada en tu hosting.') : null,
        input, input2, err, btn));
  };
}

function playlistScreen(first) {
  return (ctx) => {
    let type = 'xtream';
    const name = h('input.input.f', { placeholder: 'Nombre (opcional)', value: 'Mi lista' });
    const server = h('input.input.f', { placeholder: 'Servidor, p. ej. http://proveedor.com:8080', autocomplete: 'off' });
    const user = h('input.input.f', { placeholder: 'Usuario', autocomplete: 'off' });
    const pass = h('input.input.f', { placeholder: 'Contraseña', type: 'password', autocomplete: 'off' });
    const m3u = h('input.input.f', { placeholder: 'Dirección de la lista M3U', autocomplete: 'off' });
    const epg = h('input.input.f', { placeholder: 'Guía XMLTV (opcional)', autocomplete: 'off' });
    const err = h('div.err');
    const xBox = h('div', { style: { display: 'flex', flexDirection: 'column', gap: '12px' } }, server, user, pass);
    const mBox = h('div', { style: { display: 'none', flexDirection: 'column', gap: '12px' } }, m3u, epg);
    const tabs = h('div.seg', { dataset: { group: 'pltype' } });
    const tX = h('button.tab.f.on', { tabIndex: -1, type: 'button' }, 'Xtream Codes');
    const tM = h('button.tab.f', { tabIndex: -1, type: 'button' }, 'Lista M3U');
    tX.onclick = () => { type = 'xtream'; tX.classList.add('on'); tM.classList.remove('on'); xBox.style.display = 'flex'; mBox.style.display = 'none'; };
    tM.onclick = () => { type = 'm3u'; tM.classList.add('on'); tX.classList.remove('on'); mBox.style.display = 'flex'; xBox.style.display = 'none'; };
    tabs.append(tX, tM);
    const save = async () => {
      err.textContent = '';
      let pl;
      if (type === 'xtream') {
        if (!server.value.trim() || !user.value.trim()) {
          err.textContent = 'Rellena servidor y usuario';
          return;
        }
        pl = { type, name: name.value.trim() || 'Mi lista', server: server.value.trim(), user: user.value.trim(), pass: pass.value };
        btn.disabled = true;
        btn.textContent = 'Comprobando…';
        try {
          await D.testXtream(pl);
        } catch (e) {
          err.textContent = e.message;
          btn.disabled = false;
          btn.textContent = 'Guardar y cargar';
          return;
        }
      } else {
        if (!/^https?:\/\//i.test(m3u.value.trim())) {
          err.textContent = 'Escribe una dirección que empiece por http:// o https://';
          return;
        }
        pl = { type, name: name.value.trim() || 'Mi lista', url: m3u.value.trim(), epg: epg.value.trim() || null };
      }
      await D.savePlaylist(pl);
      if (first) start();
      else {
        ctx.back();
        runSync(true);
      }
    };
    const btn = button('Guardar y cargar', save, { primary: true });
    const page = h('div.center', { style: { overflowY: 'auto' } },
      h('div.brand-big', { style: { width: '170px', height: '54px' } }),
      h('div.form', null,
        h('div.t-title', { style: { textAlign: 'center' } }, 'Añade tu lista'),
        h('div.dim', { style: { textAlign: 'center' } }, 'Los datos se quedan en este navegador. La web no incluye contenido.'),
        tabs, name, xBox, mBox, err, btn,
        first ? null : button('Cancelar', () => ctx.back())));
    return page;
  };
}

function syncScreen() {
  return () => {
    const msg = h('div.dim', null, 'Preparando…');
    const err = h('div.err');
    const wrap = h('div.center', null, h('div.spinner'), h('div.t-title', null, 'Cargando tu lista'), msg, err);
    (async () => {
      try {
        await D.sync((m) => (msg.textContent = m));
        msg.textContent = 'Listo';
        showMain();
        backgroundRefresh();
      } catch (e) {
        err.textContent = e.message;
        wrap.append(button('Reintentar', () => { resetStack(); app.replaceChildren(); shell.bar = null; nav.push(syncScreen(), { auto: false }); }, { primary: true }),
          button('Cambiar la lista', async () => { await D.forgetPlaylist(); start(); }));
      }
    })();
    return wrap;
  };
}

export function runSync(force) {
  (async () => {
    try {
      if (force || Date.now() - D.S.lastSync > 0) await D.sync((m) => setSyncMessage('Actualizando: ' + m));
      if (D.epgUrl()) {
        setSyncMessage('Cargando la guía…');
        await D.syncEpg((m) => setSyncMessage(m));
      }
    } catch (e) {
      toast(e.message);
    }
    setSyncMessage(null);
  })();
}

function resetStack() {
  for (const p of stack) {
    dispose(p);
    popLayer(p.layer);
  }
  stack.length = 0;
}

function showMain() {
  resetStack();
  app.replaceChildren();
  try {
    const t = sessionStorage.getItem('nt:tab');
    if (t && SCREENS[t] && t !== 'search') shell.tab = t;
  } catch { /* empty */ }
  app.append(topBar());
  nav.push(mainShell, { withBar: true });
}

export const appApi = { nav, runSync, showMain, playlistScreen, shell };
window.__nt = appApi;

async function start() {
  resetStack();
  shell.bar = null;
  app.replaceChildren(loading());
  let st;
  try {
    st = await D.authStatus();
  } catch (e) {
    app.replaceChildren(h('div.center', null, h('div.brand-big'), h('div.t-title', null, 'No se pudo conectar'), h('div.dim', null, e.message + '. ¿Está subida la carpeta api y el hosting tiene PHP?')));
    return;
  }
  if (!st.curl) {
    app.replaceChildren(h('div.center', null, h('div.brand-big'), h('div.t-title', null, 'Falta la extensión cURL de PHP'), h('div.dim', null, 'Actívala en el panel de Hostinger (PHP → Extensiones) y recarga.')));
    return;
  }
  if (st.setup || !st.authed) {
    app.replaceChildren();
    nav.push(authScreen(st.setup), { auto: false });
    return;
  }
  await D.loadAll();
  app.replaceChildren();
  if (!D.S.playlist) {
    nav.push(playlistScreen(true), { auto: false });
    return;
  }
  if (!D.S.lastSync || (!D.S.live.length && !D.S.vod.length && !D.S.series.length)) {
    nav.push(syncScreen(), { auto: false });
    return;
  }
  showMain();
  backgroundRefresh();
}

start();

export { reveal, popLayer };


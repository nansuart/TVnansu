// nansu tv web: start-up (access, list), the main shell (side menu, header) and navigation between pages.
import { h, fmt, toast } from './util.js';
import { focus, onKey, pushLayer, popLayer, reveal } from './nav.js';
import { ICONS, loading, button, Preview } from './ui.js';
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
    stack.push(page);
    app.append(page.el);
    pushLayer(page.el);
    renderPage(page, true);
  },
  back() {
    if (stack.length <= 1) return false;
    const page = stack.pop();
    dispose(page);
    popLayer(page.el);
    page.el.remove();
    const top = stack[stack.length - 1];
    top.el.classList.remove('under');
    if (top.dirty) {
      top.dirty = false;
      renderPage(top, false);
    } else restoreState(top);
    return true;
  },
  refresh() {
    const top = stack[stack.length - 1];
    if (!top) return;
    // The main screen only redraws the current section (menu and header stay).
    const inner = top.el.firstElementChild;
    if (inner && inner._refresh) {
      inner._refresh();
      return;
    }
    saveState(top);
    renderPage(top, false);
  },
};

function dispose(page) {
  page.disposers.forEach((f) => { try { f(); } catch { /* empty */ } });
  page.disposers = [];
}

function saveState(page) {
  const a = document.activeElement;
  if (a && page.el.contains(a) && a.classList.contains('f')) page.fk = fkOf(a);
  page.scroll = [...page.el.querySelectorAll('.scroll, .row, .cats, .chs')].map((s) => [s.scrollTop, s.scrollLeft]);
}
function restoreState(page) {
  const scrolls = page.el.querySelectorAll('.scroll, .row, .cats, .chs');
  (page.scroll || []).forEach(([t, l], i) => {
    if (scrolls[i]) {
      scrolls[i].scrollTop = t;
      scrolls[i].scrollLeft = l;
    }
  });
  const el = page.fk && findFk(page.el, page.fk);
  if (el) focus(el, { instant: true });
  else {
    const first = page.el.querySelector('.f[data-default]') || page.el.querySelector('.f');
    if (first) focus(first, { instant: true });
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

// Re-render after data changes (favourites, a sync, progress after the player…).
let dirtyTimer;
D.onChange(() => {
  clearTimeout(dirtyTimer);
  dirtyTimer = setTimeout(() => {
    stack.forEach((p, i) => { if (i < stack.length - 1) p.dirty = true; });
    if (isPlaying()) {
      const top = stack[stack.length - 1];
      if (top) top.dirty = true;
      return;
    }
    const top = stack[stack.length - 1];
    if (top && !top.noAutoRefresh) nav.refresh();
  }, 250);
});

// Coming back from the player re-renders what changed (progress, captures).
document.addEventListener('nt:playerclosed', () => {
  const top = stack[stack.length - 1];
  if (top && top.dirty) {
    top.dirty = false;
    renderPage(top, false);
  } else if (top) restoreState(top);
});

// Back: closes pages, then goes to the side menu, then to Inicio.
onKey((e) => {
  const typing = ['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName);
  if (!(e.key === 'Escape' || e.key === 'BrowserBack' || e.key === 'GoBack' || (e.key === 'Backspace' && !typing))) return false;
  if (nav.back()) return true;
  if (shell.handleBack()) return true;
  return true;
});

// ------------------------------------------------------------------ main shell

const TABS = [
  ['home', 'Inicio', ICONS.home],
  ['live', 'En vivo', ICONS.live],
  ['movies', 'Películas', ICONS.movies],
  ['series', 'Series', ICONS.series],
  ['search', 'Buscar', ICONS.search],
  ['settings', 'Ajustes', ICONS.settings],
];

const shell = {
  tab: 'home',
  railItems: [],
  content: null,
  header: null,
  setTab: null,
  handleBack() {
    const a = document.activeElement;
    const inRail = a && a.closest && a.closest('.rail');
    if (!inRail) {
      const cur = this.railItems.find((r) => r.dataset.tab === this.tab);
      if (cur) focus(cur);
      return true;
    }
    if (this.tab !== 'home') {
      this.setTab('home', false);
      focus(this.railItems[0]);
      return true;
    }
    return true;
  },
};

function mainShell(ctx) {
  const root = h('div.screen');
  const content = h('div.screen', { dataset: { group: 'content', restore: 'on' } });
  const header = h('div.header');
  const rail = h('nav.rail', { dataset: { group: 'rail', enter: 'left' } });
  const col = h('div.col');
  const logo = h('div.logo', null, h('div.x'), h('div.word'));
  col.append(logo);
  shell.railItems = TABS.map(([key, label, icon]) => {
    const b = h('button.ri.f', { tabIndex: -1, type: 'button', dataset: { tab: key } });
    b.innerHTML = icon;
    b.append(h('span.lbl', null, label));
    let t;
    b.addEventListener('focus', () => {
      clearTimeout(t);
      t = setTimeout(() => { if (document.activeElement === b) setTab(key, false); }, 550);
    });
    b.addEventListener('click', () => {
      setTab(key, true);
    });
    col.append(b);
    return b;
  });
  const prof = h('button.ri.f.profile', { tabIndex: -1, type: 'button' }, h('span.avatar', null, 'P'), h('span.lbl', null, 'Principal'));
  prof.addEventListener('click', () => setTab('settings', true));
  col.append(prof);
  rail.append(col);

  // Menu opens when it has focus; previews pause meanwhile.
  let open = false;
  const setOpen = (o) => {
    if (o === open) return;
    open = o;
    rail.classList.toggle('open', o);
    const x = logo.querySelector('.x');
    x.classList.remove('spin', 'unspin');
    void x.offsetWidth;
    if (!D.prefs.reduceMotion) x.classList.add(o ? 'spin' : 'unspin');
    Preview.paused = o;
    if (o) Preview.stop();
  };
  rail.addEventListener('focusin', () => setOpen(true));
  rail.addEventListener('focusout', () => setTimeout(() => setOpen(rail.contains(document.activeElement)), 0));
  rail.addEventListener('mouseenter', () => setOpen(true));
  rail.addEventListener('mouseleave', () => setOpen(rail.contains(document.activeElement)));
  // Entering the menu lands on the current section.
  rail._entry = () => shell.railItems.find((r) => r.dataset.tab === shell.tab);
  // → from the menu goes back to where the user was in the content.
  rail._dir = (dir) => {
    if (dir !== 'right') return false;
    const last = content._last;
    if (last && last.isConnected) focus(last);
    else {
      const first = content.querySelector('.f[data-default]') || content.querySelector('.f');
      if (first) focus(first);
    }
    return true;
  };

  // Header: section, date, sync status, clock.
  const titleEl = h('span.t-title');
  const dateEl = h('span.t-label.faint');
  const statusEl = h('div.status');
  const clockEl = h('span.t-title');
  header.append(h('div.in', null, titleEl, dateEl, h('div.grow'), statusEl, clockEl));
  const tick = () => {
    clockEl.textContent = fmt.clock(Date.now());
    dateEl.textContent = fmt.longDate(Date.now());
  };
  tick();
  const clock = setInterval(tick, 15000);
  ctx.cleanup(() => clearInterval(clock));
  shell.status = (msg) => {
    statusEl.replaceChildren();
    if (msg) statusEl.append(h('div.spinner'), h('span', null, msg));
  };
  shell.status(syncMessage);

  let screenCleanup = [];
  function renderTab(fk, scrolls) {
    screenCleanup.forEach((f) => { try { f(); } catch { /* empty */ } });
    screenCleanup = [];
    Preview.stop();
    header.classList.remove('hidden');
    const sctx = {
      cleanup: (fn) => screenCleanup.push(fn),
      push: nav.push,
      setHeaderHidden: (b) => header.classList.toggle('hidden', b),
      selectTab: (t) => setTab(t, true),
      refresh: () => {
        const a = document.activeElement;
        renderTab(a && content.contains(a) && a.classList.contains('f') ? fkOf(a) : null);
      },
    };
    content.replaceChildren(SCREENS[shell.tab](sctx));
    titleEl.textContent = TABS.find((t) => t[0] === shell.tab)[1];
    shell.railItems.forEach((r) => r.classList.toggle('cur', r.dataset.tab === shell.tab));
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
      const el = findFk(content, fk) || content.querySelector('.f[data-default]');
      if (el) focus(el, { instant: true });
    });
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
  shell.header = header;

  const a = document.activeElement;
  const fk = a && a.classList && a.classList.contains('f') && app.contains(a) ? fkOf(a) : null;
  renderTab(fk);
  root.append(content, header, rail);
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
    const xBox = h('div', { style: { display: 'flex', flexDirection: 'column', gap: '1.2rem' } }, server, user, pass);
    const mBox = h('div', { style: { display: 'none', flexDirection: 'column', gap: '1.2rem' } }, m3u, epg);
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
      h('div.brand-big', { style: { width: '18rem', height: '5.6rem' } }),
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
        wrap.append(button('Reintentar', () => { app.replaceChildren(); stack.length = 0; nav.push(syncScreen(), { auto: false }); }, { primary: true }),
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

function showMain() {
  for (const p of stack) dispose(p);
  stack.length = 0;
  app.replaceChildren();
  try {
    const t = sessionStorage.getItem('nt:tab');
    if (t && SCREENS[t]) shell.tab = t;
  } catch { /* empty */ }
  nav.push(mainShell);
}

export const appApi = { nav, runSync, showMain, playlistScreen, shell };
window.__nt = appApi;

async function start() {
  for (const p of stack) dispose(p);
  stack.length = 0;
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

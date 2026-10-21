// Small helpers shared by every screen.

/** Creates an element: h('div.card.big', {onclick, style:{...}, dataset:{...}}, children...) */
export function h(tag, props, ...children) {
  const [name, ...classes] = tag.split('.');
  const el = document.createElement(name || 'div');
  if (classes.length) el.className = classes.join(' ');
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v == null || v === false) continue;
      if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'class') el.className += (el.className ? ' ' : '') + v;
      else if (k === 'html') el.innerHTML = v;
      else if (k in el && k !== 'list') el[k] = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c == null || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

/** replaceChildren that skips null/false (which the DOM would print as text). */
export function put(el, ...kids) {
  el.replaceChildren(...kids.flat().filter((k) => k != null && k !== false));
  return el;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function debounce(fn, ms) {
  let t;
  return (...a) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...a), ms);
  };
}

export const b64url = (s) => btoa(unescape(encodeURIComponent(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const es = 'es-ES';
export const fmt = {
  clock(ms) {
    if (!ms) return '';
    return new Date(ms).toLocaleTimeString(es, { hour: '2-digit', minute: '2-digit' });
  },
  longDate(ms) {
    return new Date(ms).toLocaleDateString(es, { weekday: 'long', day: 'numeric', month: 'long' });
  },
  ago(ms, now = Date.now()) {
    const min = Math.max(0, Math.floor((now - ms) / 60000));
    if (min < 1) return 'Ahora';
    if (min < 60) return `Hace ${min} min`;
    if (min < 1440) return `Hace ${Math.floor(min / 60)} h`;
    if (min < 2880) return 'Ayer';
    return `Hace ${Math.floor(min / 1440)} días`;
  },
  duration(ms) {
    const t = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
    const p = (n) => String(n).padStart(2, '0');
    return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${m}:${p(s)}`;
  },
  minutesLeft(ms) {
    const min = Math.max(1, Math.floor(ms / 60000));
    return min >= 60 ? `Quedan ${Math.floor(min / 60)} h ${min % 60} min` : `Quedan ${min} min`;
  },
};

const trailingYear = /\s*[([]\s*(19|20)\d{2}\s*[)\]]\s*$/;
/** "Dune (2021)" → "Dune" */
export const cleanTitle = (n) => (n || '').replace(trailingYear, '') || n || '';

export const yearIn = (n) => {
  const m = /[([ ]((19|20)\d{2})[)\] ]?/.exec(n || '');
  return m ? m[1] : null;
};

export function metaLine(year, rating, genre) {
  return [year || null, rating > 0 ? `★ ${rating.toFixed(1).replace('.', ',')}` : null, genre || null]
    .filter(Boolean)
    .join('  ·  ');
}

const tags = 'ES|EN|US|UK|GB|MX|LAT|LATAM|AR|FR|DE|IT|PT|BR|CA|CO|CL|VE|PE|NL|TR|IN|JP|KR|MULTI|VOSE|4K|HD|FHD|UHD';
const prefix = new RegExp(`^\\s*(?:[\\[(|]\\s*(?:${tags})\\s*[\\])|]\\s*[-:|]?|(?:${tags})\\s*[-:|])\\s*`, 'i');
/** "ES - The Bear (2022) [4K]" → "the bear" — to compare titles. */
export function searchName(raw) {
  let s = raw || '';
  s = s.replace(prefix, '').replace(/\[[^\]]*]/g, ' ').replace(/\(\s*(19|20)\d{2}\s*\)/g, ' ');
  s = s.replace(/\b(4K|UHD|FHD|HD|SD|MULTI|SUB|DUAL|LAT|CAST|VOSE|ESP)\b/gi, ' ');
  return s.replace(/\s+/g, ' ').trim().toLowerCase();
}

export function toast(msg) {
  let t = document.getElementById('toast');
  if (!t) {
    t = h('div');
    t.id = 'toast';
    document.body.append(t);
  }
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(t._h);
  t._h = setTimeout(() => t.classList.remove('show'), 2400);
}

/** Image that falls back to a text plate when missing or broken. */
export function img(src, cls, alt = '') {
  const i = h('img' + (cls ? '.' + cls : ''), { alt, loading: 'lazy', decoding: 'async', draggable: false });
  if (src) i.src = src;
  else i.classList.add('missing');
  i.addEventListener('error', () => i.classList.add('missing'));
  return i;
}

export const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

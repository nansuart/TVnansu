// Remote-control style navigation: arrow keys move focus to the nearest item in
// that direction, rows scroll so the focused card stays in place, and groups
// remember their last focused item (like the TV app's focus restorer).

const KEYS = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
let layerStack = [];
const keyHandlers = [];

/** A layer (screen, player, dialog) limits navigation to its own items. */
export function pushLayer(root) {
  layerStack.push(root);
}
export function popLayer(root) {
  layerStack = layerStack.filter((r) => r !== root);
}
export function topLayer() {
  return layerStack[layerStack.length - 1] || document.body;
}

/** Extra key handling (player, back). Returns true to stop default navigation. */
export function onKey(fn) {
  keyHandlers.push(fn);
  return () => {
    const i = keyHandlers.indexOf(fn);
    if (i >= 0) keyHandlers.splice(i, 1);
  };
}

function visible(el) {
  if (!el.isConnected) return false;
  const r = el.getBoundingClientRect();
  if (r.width < 2 || r.height < 2) return false;
  const st = getComputedStyle(el);
  return st.visibility !== 'hidden' && st.display !== 'none';
}

function focusables(root) {
  return [...root.querySelectorAll('.f')].filter((el) => !el.disabled && !el.closest('[inert]') && visible(el));
}

function center(r) {
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}

/** Nearest item in a direction, scored like Compose: distance along it weighs more. */
function findNext(from, dir, root) {
  const fr = from.getBoundingClientRect();
  const fc = center(fr);
  let best = null, bestScore = Infinity, bestInBeam = false;
  for (const el of focusables(root)) {
    if (el === from) continue;
    // Some groups (the side menu) can only be entered moving in one direction.
    const gate = el.closest('[data-enter]');
    if (gate && !gate.contains(from) && gate.dataset.enter !== dir) continue;
    const r = el.getBoundingClientRect();
    const c = center(r);
    let major, minor;
    if (dir === 'right') {
      if (r.left < fr.right - 4 && c.x <= fc.x + 2) continue;
      if (c.x <= fc.x) continue;
      major = Math.max(0, r.left - fr.right);
      minor = Math.abs(c.y - fc.y);
    } else if (dir === 'left') {
      if (c.x >= fc.x) continue;
      if (r.right > fr.left + 4 && c.x >= fc.x - 2) continue;
      major = Math.max(0, fr.left - r.right);
      minor = Math.abs(c.y - fc.y);
    } else if (dir === 'down') {
      if (c.y <= fc.y || r.top < fr.bottom - 4) continue;
      major = Math.max(0, r.top - fr.bottom);
      minor = Math.abs(c.x - fc.x);
    } else {
      if (c.y >= fc.y || r.bottom > fr.top + 4) continue;
      major = Math.max(0, fr.top - r.bottom);
      minor = Math.abs(c.x - fc.x);
    }
    // Items in the "beam" (overlapping our span across the direction) always win,
    // as on Android TV: → from a button goes to what is beside it, not below.
    const overlap = dir === 'up' || dir === 'down'
      ? Math.min(r.right, fr.right) - Math.max(r.left, fr.left)
      : Math.min(r.bottom, fr.bottom) - Math.max(r.top, fr.top);
    const inBeam = overlap > 0;
    const score = 13 * major * major + minor * minor;
    if ((inBeam && !bestInBeam) || (inBeam === bestInBeam && score < bestScore)) {
      bestScore = score;
      bestInBeam = inBeam;
      best = el;
    }
  }
  return best;
}

/** Entering a group from outside goes back to the item focused last in it. */
function restoreTarget(el, from) {
  const group = el.closest('[data-group]');
  if (!group || group.contains(from)) return el;
  if (group.dataset.restore === 'off') return el;
  if (group._entry) return group._entry() || el;
  const last = group._last;
  if (last && last.isConnected && group.contains(last) && visible(last)) return last;
  const pref = group.querySelector('.f[data-default]');
  return pref && visible(pref) ? pref : el;
}

export function focus(el, opts = {}) {
  if (!el) return;
  el.focus({ preventScroll: true });
  reveal(el, opts.instant);
}

document.addEventListener('focusin', (e) => {
  const el = e.target;
  let g = el.closest && el.closest('[data-group]');
  while (g) {
    g._last = el;
    g = g.parentElement && g.parentElement.closest('[data-group]');
  }
});

/** Scrolls rows and screens so the focused item is seen whole. */
export function reveal(el, instant) {
  const behavior = instant || document.body.classList.contains('reduce') ? 'auto' : 'smooth';
  const row = el.closest('.row');
  if (row) {
    const rr = row.getBoundingClientRect();
    const er = el.getBoundingClientRect();
    const pad = parseFloat(getComputedStyle(row).paddingLeft) || 0;
    const target = row.scrollLeft + (er.left - rr.left) - pad;
    const maxScroll = row.scrollWidth - row.clientWidth;
    if (er.left < rr.left + pad - 2 || er.right > rr.right - 8) {
      row.scrollTo({ left: Math.max(0, Math.min(maxScroll, target)), behavior });
    }
  }
  const scroller = el.closest('.scroll');
  if (scroller) {
    if (el.closest('[data-top]')) {
      scroller.scrollTo({ top: 0, behavior });
      return;
    }
    const sr = scroller.getBoundingClientRect();
    const block = el.closest('.shelf') || el;
    const br = block.getBoundingClientRect();
    const top = sr.top + rem(4);
    const bottom = sr.bottom - rem(2.8);
    if (br.top >= top && br.bottom <= bottom) return;
    let delta = br.top - top;
    if (br.height > sr.height - rem(4)) {
      const er = el.getBoundingClientRect();
      delta = er.top - top;
    }
    scroller.scrollTo({ top: scroller.scrollTop + delta, behavior });
  }
}

export function rem(n) {
  return n * parseFloat(getComputedStyle(document.documentElement).fontSize);
}

document.addEventListener('keydown', (e) => {
  for (let i = keyHandlers.length - 1; i >= 0; i--) {
    if (keyHandlers[i](e) === true) {
      e.preventDefault();
      return;
    }
  }
  const active = document.activeElement;
  const typing = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA');
  const dir = KEYS[e.key];
  if (dir) {
    if (typing && (dir === 'left' || dir === 'right')) return;
    const root = topLayer();
    let from = active && active.classList && active.classList.contains('f') && root.contains(active) ? active : null;
    if (!from) {
      const content = root.querySelector('[data-group="content"]');
      const last = content && content._last && content._last.isConnected ? content._last : null;
      const first = last || root.querySelector('.f[data-default]') || focusables(root)[0];
      if (first) focus(first);
      e.preventDefault();
      return;
    }
    // Groups can take over a direction (e.g. the side menu sends → back to the content).
    const g = from.closest('[data-group]');
    if (g && g._dir && g._dir(dir, from) === true) {
      e.preventDefault();
      return;
    }
    const next = findNext(from, dir, root);
    if (next) focus(restoreTarget(next, from));
    e.preventDefault();
    return;
  }
  if ((e.key === 'Enter' || e.key === ' ') && active && active.classList.contains('f') && !typing) {
    e.preventDefault();
    active.click();
  }
});

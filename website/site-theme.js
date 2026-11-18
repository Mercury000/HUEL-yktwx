(() => {
  'use strict';
  const root = document.documentElement;
  const system = matchMedia('(prefers-color-scheme: dark)');
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  let preference;
  try { preference = localStorage.getItem('cxt-color-mode'); } catch { /* Use the system preference. */ }
  const apply = (mode) => {
    root.dataset.theme = mode;
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = mode === 'dark' ? '#000000' : '#ffffff';
    const button = document.querySelector('[data-site-theme]');
    const label = mode === 'dark' ? '切换浅色模式' : '切换深色模式';
    button?.setAttribute('aria-label', label);
    button?.setAttribute('title', label);
    document.querySelectorAll('[data-theme-picture] source').forEach((source) => {
      source.media = mode === 'dark' ? 'all' : 'not all';
    });
    document.dispatchEvent(new Event('site-theme-change'));
  };
  apply(['light', 'dark'].includes(preference) ? preference : system.matches ? 'dark' : 'light');
  system.addEventListener('change', () => {
    if (!['light', 'dark'].includes(preference)) apply(system.matches ? 'dark' : 'light');
  });
  document.addEventListener('DOMContentLoaded', () => {
    apply(root.dataset.theme);
    let changing = false;
    document.querySelector('[data-site-theme]')?.addEventListener('click', async (event) => {
      if (changing) return;
      preference = root.dataset.theme === 'dark' ? 'light' : 'dark';
      try { localStorage.setItem('cxt-color-mode', preference); } catch { /* The current visit still works. */ }
      if (reduced.matches || !document.startViewTransition) { apply(preference); return; }
      changing = true;
      const bounds = event.currentTarget.getBoundingClientRect();
      const x = bounds.left + bounds.width / 2;
      const y = bounds.top + bounds.height / 2;
      const radius = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
      const transition = document.startViewTransition(() => apply(preference));
      try {
        await transition.ready;
        root.animate({ clipPath: [`circle(0 at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`] },
          { duration: 650, easing: 'cubic-bezier(.22,1,.36,1)', pseudoElement: '::view-transition-new(root)' });
        await transition.finished;
      } catch { apply(preference); } finally { changing = false; }
    });
  });
})();

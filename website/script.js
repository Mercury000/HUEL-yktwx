(() => {
  'use strict';
  const root = document.documentElement;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const header = document.querySelector('[data-header]');
  const nav = document.querySelector('.nav__links');
  const toggle = document.querySelector('[data-nav-toggle]');
  const mobile = matchMedia('(max-width: 1000px)');
  const setMenu = (open) => {
    const expanded = open && mobile.matches;
    if (!expanded && mobile.matches && nav?.contains(document.activeElement)) toggle?.focus({ preventScroll: true });
    header?.classList.toggle('is-menu-open', expanded);
    toggle?.setAttribute('aria-expanded', String(expanded));
    toggle?.setAttribute('aria-label', expanded ? '关闭导航' : '打开导航');
    if (nav) nav.inert = mobile.matches && !expanded;
  };
  setMenu(false);
  toggle?.addEventListener('click', () => setMenu(toggle.getAttribute('aria-expanded') !== 'true'));
  nav?.addEventListener('click', (event) => { if (event.target.closest('a')) setMenu(false); });
  document.addEventListener('click', (event) => { if (!header?.contains(event.target) || event.target.closest('[data-nav-backdrop]')) setMenu(false); });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && toggle?.getAttribute('aria-expanded') === 'true') { setMenu(false); toggle.focus(); }
  });
  header?.addEventListener('focusout', (event) => { if (event.relatedTarget && !header.contains(event.relatedTarget)) setMenu(false); });
  mobile.addEventListener('change', () => setMenu(false));

  // All content remains visible if animation support or JavaScript is unavailable.
  const revealTargets = document.querySelectorAll('[data-reveal], .download-page .reveal');
  if ('IntersectionObserver' in window) {
    if (!reduced.matches) root.classList.add('motion-ready');
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      });
    }, { threshold: .12, rootMargin: '0px 0px -35px 0px' });
    revealTargets.forEach((element) => observer.observe(element));
  }
  const parallax = [...document.querySelectorAll('[data-parallax]')];
  const progress = document.querySelector('[data-reading-progress]');
  const sections = [...document.querySelectorAll('.nav__links a[href^="#"]')]
    .map((link) => ({ link, section: document.getElementById(link.hash.slice(1)) }))
    .filter(({ section }) => section);
  let frame = 0;
  const drawScroll = () => {
    frame = 0;
    header?.classList.toggle('is-scrolled', scrollY > 16);
    if (progress) {
      const ratio = Math.max(0, Math.min(1, scrollY / Math.max(1, document.documentElement.scrollHeight - innerHeight)));
      progress.style.transform = `scaleX(${ratio})`;
      progress.parentElement.setAttribute('aria-valuenow', String(Math.round(ratio * 100)));
    }
    const readingLine = Math.max((header?.getBoundingClientRect().bottom || 0) + 48,
      (parseFloat(getComputedStyle(root).scrollPaddingTop) || 0) + 2);
    const atBottom = scrollY > 0 && scrollY + innerHeight >= root.scrollHeight - 2;
    const current = atBottom ? sections.at(-1)
      : sections.findLast(({ section }) => section.getBoundingClientRect().top <= readingLine);
    sections.forEach(({ link }) => {
      const selected = link === current?.link;
      link.classList.toggle('is-current', selected);
      if (selected) link.setAttribute('aria-current', 'location');
      else link.removeAttribute('aria-current');
    });
    parallax.forEach((element) => {
      element.style.translate = reduced.matches ? '' : `0 ${Math.min(scrollY, 1000) * Number(element.dataset.parallax)}px`;
    });
  };
  addEventListener('scroll', () => { if (!frame) frame = requestAnimationFrame(drawScroll); }, { passive: true });
  addEventListener('resize', drawScroll);
  addEventListener('hashchange', drawScroll);
  addEventListener('pageshow', drawScroll);
  if ('ResizeObserver' in window) new ResizeObserver(drawScroll).observe(document.body);
  drawScroll();
  reduced.addEventListener('change', () => {
    root.classList.toggle('motion-ready', !reduced.matches);
    if (reduced.matches) revealTargets.forEach((target) => target.classList.add('is-visible'));
    drawScroll();
  });

  // The gallery responds to the pointer without taking over page scrolling.
  const stage = document.querySelector('[data-parallax-stage]');
  const galleryPrev = document.querySelector('[data-gallery-prev]');
  const galleryNext = document.querySelector('[data-gallery-next]');
  const syncGallery = () => {
    if (!stage || !galleryPrev || !galleryNext) return;
    galleryPrev.disabled = stage.scrollLeft <= 2;
    galleryNext.disabled = stage.scrollLeft >= stage.scrollWidth - stage.clientWidth - 2;
  };
  const moveGallery = (direction) => {
    const screen = stage?.querySelector('.gallery-screen');
    if (!screen) return;
    stage.scrollBy({ left: direction * (screen.offsetWidth + parseFloat(getComputedStyle(stage).gap)), behavior: reduced.matches ? 'instant' : 'smooth' });
  };
  galleryPrev?.addEventListener('click', () => moveGallery(-1));
  galleryNext?.addEventListener('click', () => moveGallery(1));
  stage?.addEventListener('scroll', syncGallery, { passive: true });
  addEventListener('resize', syncGallery);
  syncGallery();
  let pointerFrame;
  stage?.addEventListener('pointermove', (event) => {
    if (reduced.matches || event.pointerType !== 'mouse') return;
    cancelAnimationFrame(pointerFrame);
    pointerFrame = requestAnimationFrame(() => {
      const rect = stage.getBoundingClientRect();
      const x = (event.clientX - rect.left) / rect.width - .5;
      const y = (event.clientY - rect.top) / rect.height - .5;
      stage.querySelectorAll('.app-frame').forEach((phone, i) => {
        phone.style.rotate = `${x * (i % 2 ? 3 : -3)}deg`;
        phone.style.translate = `${x * 5}px ${y * 7}px`;
      });
    });
  });
  stage?.addEventListener('pointerleave', () => {
    cancelAnimationFrame(pointerFrame);
    stage.querySelectorAll('.app-frame').forEach((phone) => { phone.style.rotate = ''; phone.style.translate = ''; });
  });

  document.querySelectorAll('.privacy-details details').forEach((details) => {
    const summary = details.querySelector('summary');
    const body = details.querySelector('p');
    let animation;
    let targetOpen = details.open;
    summary.addEventListener('click', async (event) => {
      if (reduced.matches || !details.animate) { targetOpen = !details.open; return; }
      event.preventDefault();
      const start = details.getBoundingClientRect().height;
      targetOpen = !targetOpen;
      animation?.cancel();
      details.style.height = '';
      details.open = true;
      const end = targetOpen ? details.getBoundingClientRect().height : summary.getBoundingClientRect().height + 1;
      details.style.overflow = 'hidden';
      const current = details.animate({ height: [`${start}px`, `${end}px`] }, { duration: 360, easing: 'cubic-bezier(.22,1,.36,1)' });
      animation = current;
      body.animate({ opacity: targetOpen ? [0,1] : [1,0], transform: targetOpen ? ['translateY(-6px)','none'] : ['none','translateY(-6px)'] }, { duration: 250 });
      try { await current.finished; if (animation === current) { details.open = targetOpen; details.style.overflow = ''; } } catch { /* A new click supersedes this transition. */ }
    });
  });
  let leaving = false;
  document.addEventListener('click', async (event) => {
    const link = event.target.closest('a[href]');
    if (!link || event.defaultPrevented || reduced.matches || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || link.hasAttribute('download') || (link.target && link.target !== '_self')) return;
    const target = new URL(link.href, location.href);
    if (target.origin !== location.origin || target.pathname === location.pathname || !/\/(?:index|download)\.html$/.test(target.pathname)) return;
    event.preventDefault();
    if (leaving) return;
    leaving = true;
    const transition = document.querySelector('main')?.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'translateY(-12px)' }], { duration: 180, fill: 'forwards' });
    try { await transition?.finished; } catch { /* Still navigate. */ }
    location.assign(target.href);
  });
  addEventListener('pageshow', () => {
    leaving = false;
    document.querySelector('main')?.getAnimations().forEach((animation) => animation.cancel());
  });
})();

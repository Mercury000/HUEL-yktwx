(() => {
  'use strict';
  const tabs = [...document.querySelectorAll('[data-screen]')];
  if (!tabs.length) return;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const screens = {
    payment: { label: '付款页面', title: '吃饭前，打开财小通。', description: '二维码与条形码同屏展示，可隐藏码号、放大付款码，查看余额并充值。扫一扫进入生活服务，也能继续查看进行中的服务。' },
    hall: { label: '大厅页面', title: '宿舍的事，顺手办好。', description: '绑定房间，查看水电读数并缴费。校园网充值和代人充值也在这里。' },
    bill: { label: '账单页面', title: '每一笔，都有迹可循。', description: '查看交易明细、收支统计与消费趋势，按关键词、日期或交易类型查找账单。' },
    mine: { label: '我的页面', title: '你的校园卡，你来管理。', description: '查看个人资料和校园卡状态，办理挂失、解挂或修改查询密码，也可设置头像、界面偏好和生活服务账户。' }
  };
  const image = document.querySelector('[data-screen-image]');
  const phone = document.querySelector('[data-screen-phone]');
  const panel = document.getElementById('screen-panel');
  let screen = 'payment';
  const versions = new WeakMap();
  const updateImage = async (target, source, alt, direction = 1) => {
    const version = (versions.get(target) || 0) + 1;
    versions.set(target, version);
    const next = new Image();
    next.src = source;
    try { await next.decode(); } catch { return; }
    if (version !== versions.get(target)) return;
    target.parentElement.querySelectorAll('.image-transition-layer').forEach((layer) => layer.remove());
    const old = target.cloneNode();
    old.className = 'image-transition-layer';
    old.removeAttribute('data-screen-image');
    old.removeAttribute('loading'); old.alt = ''; old.setAttribute('aria-hidden', 'true');
    target.src = source; target.alt = alt;
    if (reduced.matches || !target.animate) return;
    old.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;object-fit:cover;pointer-events:none;z-index:2';
    target.parentElement.append(old);
    target.animate([{ opacity: .4, transform: `translateX(${direction * 22}px) scale(.98)` }, { opacity: 1, transform: 'none' }], { duration: 480, easing: 'cubic-bezier(.22,1,.36,1)' });
    const fade = old.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: `translateX(${-direction * 22}px)` }], { duration: 360, fill: 'forwards', easing: 'ease-out' });
    try { await fade.finished; } catch { /* Superseded. */ } finally { old.remove(); }
  };
  const render = (direction = 1, animateCopy = true) => {
    const data = screens[screen];
    tabs.forEach((tab) => {
      const selected = tab.dataset.screen === screen;
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
    });
    panel.setAttribute('aria-labelledby', `tab-${screen}`);
    document.getElementById('screen-preview').setAttribute('aria-labelledby', `tab-${screen}`);
    document.querySelector('[data-screen-title]').textContent = data.title;
    document.querySelector('[data-screen-description]').textContent = data.description;
    const mode = document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
    updateImage(image, `assets/screens/${screen}-${mode}@2x.webp`, `${mode === 'dark' ? '深色' : '浅色'}${data.label}预览`, direction);
    if (!reduced.matches && animateCopy) {
      document.querySelector('[data-screen-detail]').animate([{ opacity: 0, transform: 'translateY(10px)' }, { opacity: 1, transform: 'none' }], { duration: 420, easing: 'ease-out' });
      phone.animate([{ rotate: '0deg', scale: '1' }, { rotate: `${direction * 1.5}deg`, scale: '.985', offset: .35 }, { rotate: '0deg', scale: '1' }], { duration: 600, easing: 'cubic-bezier(.22,1,.36,1)' });
    }
  };
  const choose = (value) => {
    if (value === screen || !screens[value]) return;
    const direction = Object.keys(screens).indexOf(value) > Object.keys(screens).indexOf(screen) ? 1 : -1;
    screen = value; render(direction);
  };
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => choose(tab.dataset.screen));
    tab.addEventListener('keydown', (event) => {
      const offsets = { ArrowRight: 1, ArrowLeft: -1 };
      let next;
      if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = tabs.length - 1;
      else if (event.key in offsets) next = (index + offsets[event.key] + tabs.length) % tabs.length;
      else return;
      event.preventDefault(); tabs[next].focus(); choose(tabs[next].dataset.screen);
    });
  });
  document.querySelectorAll('[data-jump-screen]').forEach((link) => link.addEventListener('click', () => choose(link.dataset.jumpScreen)));
  const matchTheme = () => render(1, false);
  document.addEventListener('site-theme-change', matchTheme); matchTheme();

  const services = {
    water: { title: '扫一下，\n接好你的下一杯。', description: '扫码或输入设备编号，开始接水、查看用水时间，用完后在应用里结束。', images: [['water', '胖乖生活 · 接水选项'], ['hui798', '慧生活798 · 开始用水']] },
    shower: { title: '从开始淋浴，\n到结束用水。', description: '扫码开始淋浴，查看用水状态和时间，用完后结束。', images: [['shower', '胖乖生活 · 淋浴进度'], ['hui798-shower', '慧生活798 · 淋浴用水']] },
    laundry: { title: '衣服放进去，\n进度看得见。', description: '扫码查看洗衣选项、费用与预计时长，选择支付方式并付款，随时查看进度。待支付订单可继续付款或取消。', images: [['laundry', '洗衣选项'], ['laundry-running', '洗衣进度']] },
    bike: { title: '去教室的路上，\n也少一点切换。', description: '扫码开锁、查看骑行状态、还车付款。支持临时锁车的车辆可锁车后继续骑行；账户内可查看余额、骑行卡与校园商品。', images: [['bike', '哈啰单车 · 骑行中']] }
  };
  const lifeTabs = [...document.querySelectorAll('[data-life]')];
  const lifeGallery = document.querySelector('[data-life-gallery]');
  let lifeSelected = 'water';
  const lifeFigure = (name, caption) => {
    const figure = document.createElement('figure');
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'screenshot-button'; button.dataset.imageOpen = '';
    button.setAttribute('aria-label', `放大${caption}`);
    const picture = document.createElement('picture'); picture.dataset.themePicture = '';
    const source = document.createElement('source');
    source.media = document.documentElement.dataset.theme === 'dark' ? 'all' : 'not all';
    source.srcset = `assets/screens/${name}-dark@2x.webp`;
    const image = document.createElement('img');
    image.src = `assets/screens/${name}-light@2x.webp`; image.width = 960; image.height = 2081;
    image.alt = caption; image.loading = 'lazy';
    const hint = document.createElement('span'); hint.textContent = '放大查看';
    const label = document.createElement('figcaption'); label.textContent = caption;
    picture.append(source, image); button.append(picture, hint); figure.append(button, label);
    return figure;
  };
  const chooseLife = (name) => {
    if (!services[name] || lifeSelected === name) return;
    lifeSelected = name;
    const data = services[name];
    lifeTabs.forEach((tab) => {
      const selected = tab.dataset.life === name;
      tab.setAttribute('aria-selected', String(selected)); tab.tabIndex = selected ? 0 : -1;
    });
    document.querySelector('#life-panel').setAttribute('aria-labelledby', `life-tab-${name}`);
    document.querySelector('[data-life-title]').textContent = data.title;
    document.querySelector('[data-life-description]').textContent = data.description;
    lifeGallery.replaceChildren(...data.images.map(([name, caption]) => lifeFigure(name, caption)));
    lifeGallery.classList.toggle('is-single', data.images.length === 1);
    if (!reduced.matches) {
      document.querySelector('.life-copy').animate([{ opacity: 0, transform: 'translateY(12px)' }, { opacity: 1, transform: 'none' }], { duration: 480, easing: 'ease-out' });
      [...lifeGallery.children].forEach((figure, index) => {
        figure.animate([{ opacity: 0, transform: 'translateY(22px) scale(.97)' }, { opacity: 1, transform: 'none' }], { duration: 600, delay: index * 80, easing: 'cubic-bezier(.22,1,.36,1)', fill: 'backwards' });
      });
    }
  };
  lifeTabs.forEach((tab, index) => {
    tab.addEventListener('click', () => chooseLife(tab.dataset.life));
    tab.addEventListener('keydown', (event) => {
      let next;
      if (event.key === 'ArrowRight') next = (index + 1) % lifeTabs.length;
      else if (event.key === 'ArrowLeft') next = (index - 1 + lifeTabs.length) % lifeTabs.length;
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = lifeTabs.length - 1;
      else return;
      event.preventDefault(); lifeTabs[next].focus(); chooseLife(lifeTabs[next].dataset.life);
    });
  });
  const lightbox = document.querySelector('[data-image-dialog]');
  document.addEventListener('click', (event) => {
    const button = event.target.closest('[data-image-open]');
    if (!button) return;
    const image = button.querySelector('img');
    document.querySelector('[data-image-title]').textContent = image.alt;
    const full = document.querySelector('[data-image-full]');
    full.src = image.currentSrc || image.src; full.alt = image.alt;
    lightbox.showModal(); document.documentElement.classList.add('is-image-dialog-open');
  });
  const closeImage = () => lightbox.close();
  document.querySelector('[data-image-close]')?.addEventListener('click', closeImage);
  lightbox?.addEventListener('click', (event) => { if (event.target === lightbox) closeImage(); });
  lightbox?.addEventListener('close', () => document.documentElement.classList.remove('is-image-dialog-open'));
})();

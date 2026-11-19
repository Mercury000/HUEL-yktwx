(() => {
  'use strict';

  const get = (name) => document.querySelector(`[data-${name}]`);
  const make = (tag, className, value) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (value !== undefined) node.textContent = value;
    return node;
  };
  const icon = (path) => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 20 20');
    svg.setAttribute('aria-hidden', 'true');
    const shape = document.createElementNS(svg.namespaceURI, 'path');
    shape.setAttribute('d', path);
    svg.append(shape);
    return svg;
  };
  const downloadUrl = (asset, template) => window.releaseData.downloadUrl(asset, template);
  const size = (bytes) => `${(bytes / 1048576).toLocaleString('zh-CN', { maximumFractionDigits: 2 })} MB`;
  const date = (value) => {
    const parsed = new Date(value);
    return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(parsed);
  };
  const androidVersions = { 21: '5.0', 22: '5.1', 23: '6.0', 24: '7.0', 25: '7.1', 26: '8.0', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 32: '12L', 33: '13', 34: '14', 35: '15', 36: '16' };
  const system = (sdk) => androidVersions[sdk] ? `Android ${androidVersions[sdk]} 及以上` : `Android API ${sdk} 及以上`;
  const validRelease = (value) => value && typeof value.id === 'string' && typeof value.versionName === 'string' && value.channel === 'stable' && Array.isArray(value.assets) && value.assets.length > 0 && value.assets.every((asset) => typeof asset.id === 'string' && typeof asset.name === 'string');
  let downloadSources = [];
  const request = async (path, signal) => {
    const data = await window.releaseData.load();
    signal?.throwIfAborted();
    downloadSources = data.downloadSources;
    if (path === 'latest') return data.latest;
    const params = new URLSearchParams(path.split('?')[1]);
    const query = (params.get('q') || '').toLocaleLowerCase();
    const items = data.releases.filter((release) => `${release.versionName} ${release.name} ${release.notes}`.toLocaleLowerCase().includes(query));
    const start = Math.max(0, Number(params.get('cursor')) || 0);
    const limit = 10;
    return { items: items.slice(start, start + limit), total: items.length, nextCursor: start + limit < items.length ? String(start + limit) : null };
  };
  const timedController = () => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(new DOMException('请求超时', 'TimeoutError')), 15000);
    return { controller, clear: () => clearTimeout(timer) };
  };
  let feedbackTimer;
  const feedback = (message) => {
    clearTimeout(feedbackTimer);
    get('feedback').textContent = message;
    feedbackTimer = setTimeout(() => { get('feedback').textContent = ''; }, 6000);
  };
  const copy = async (text, localStatus) => {
    const report = (message) => { if (localStatus) localStatus.textContent = message; else feedback(message); };
    try {
      await navigator.clipboard.writeText(text);
      report('已复制');
    } catch {
      report(localStatus ? '未能自动复制，请选中上方校验值手动复制。' : `未能自动复制，请手动复制：${text}`);
    }
  };

  const sanitizeMarkdown = (html) => {
    const fragment = window.DOMPurify.sanitize(html, {
      RETURN_DOM_FRAGMENT: true,
      ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'strong', 'em', 'del', 'a', 'pre', 'code', 'blockquote', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'input'],
      ALLOWED_ATTR: ['href', 'title', 'start', 'align', 'type', 'checked', 'disabled', 'class']
    });
    fragment.querySelectorAll('a').forEach((link) => {
      const raw = link.getAttribute('href') || '';
      const documents = window.projectDocuments;
      const prefix = `https://github.com/${documents.repository}/blob/main/`;
      const projectPath = raw.startsWith(prefix) ? raw.slice(prefix.length) : raw.replace(/^\.\//, '');
      const [path, fragment] = projectPath.split('#', 2);
      const local = documents.pages[path];
      const url = new URL(local ? local + (fragment ? `#${fragment}` : '') : raw, document.baseURI);
      if (!['https:', 'http:'].includes(url.protocol) || (url.protocol === 'http:' && url.origin !== location.origin)) link.removeAttribute('href');
      else {
        link.href = url.href;
        if (url.origin !== location.origin) { link.target = '_blank'; link.rel = 'noopener noreferrer'; }
      }
    });
    fragment.querySelectorAll('input').forEach((input) => {
      if (input.type !== 'checkbox') input.remove();
      else input.disabled = true;
    });
    return fragment;
  };

  // Parse GitHub-flavored Markdown and sanitize the result before insertion.
  const renderNotes = (target, notes) => {
    target.replaceChildren();
    if (typeof notes !== 'string' || !notes.trim()) {
      return;
    }
    const html = window.marked.parse(notes, { gfm: true, breaks: false });
    target.append(sanitizeMarkdown(html));
  };

  const notesDialog = document.getElementById('release-notes-dialog');
  const packageDialog = document.getElementById('package-dialog');
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const bindDialog = (dialog) => {
    let opener;
    let animation;
    let pointerStartedOutside = false;
    const close = () => {
      if (!dialog.open || animation) return;
      if (reducedMotion.matches) { dialog.close(); return; }
      animation = dialog.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'translateY(8px)' }], { duration: 140, easing: 'ease-out' });
      animation.finished.then(() => { animation = null; dialog.close(); }, () => { animation = null; });
    };
    const outside = (event) => {
      const bounds = dialog.getBoundingClientRect();
      return event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom;
    };
    dialog.querySelector('[data-dialog-close]').addEventListener('click', close);
    dialog.addEventListener('cancel', (event) => { event.preventDefault(); close(); });
    dialog.addEventListener('pointerdown', (event) => { pointerStartedOutside = outside(event); });
    dialog.addEventListener('click', (event) => {
      if (pointerStartedOutside && outside(event)) close();
      pointerStartedOutside = false;
    });
    dialog.addEventListener('close', () => {
      animation?.cancel();
      pointerStartedOutside = false;
      document.documentElement.classList.remove('is-download-dialog-open');
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    });
    return (trigger) => {
      if (document.querySelector('.download-dialog[open]')) return;
      opener = trigger;
      dialog.showModal();
      document.documentElement.classList.add('is-download-dialog-open');
      dialog.querySelector('.download-dialog__body').scrollTop = 0;
    };
  };
  const showNotes = bindDialog(notesDialog);
  const showPackage = bindDialog(packageDialog);
  const openNotes = (release, opener) => {
    if (!release || document.querySelector('.download-dialog[open]')) return;
    get('notes-title').textContent = `财小通 v${release.versionName}`;
    get('notes-date').textContent = date(release.publishedAt);
    renderNotes(get('notes-body'), release.notes);
    showNotes(opener);
  };
  const openPackage = (release, asset, opener) => {
    if (!release || !asset || document.querySelector('.download-dialog[open]')) return;
    get('package-title').textContent = `财小通 v${release.versionName}`;
    get('package-filename').textContent = asset.name;
    get('package-date').textContent = date(release.publishedAt);
    get('package-size').textContent = size(asset.sizeBytes);
    get('package-system').textContent = system(asset.minSdk ?? release.minSdk);
    get('package-name').textContent = asset.packageName;
    get('package-sha').textContent = asset.sha256;
    get('copy-sha').hidden = !asset.sha256;
    get('package-copy-state').textContent = '';
    get('package-sources').replaceChildren(...downloadSources.map((source) => {
      const link = make('a', 'notes-link', source.label);
      link.href = downloadUrl(asset, source.template);
      link.setAttribute('download', asset.name);
      link.rel = 'noreferrer';
      return link;
    }));
    showPackage(opener);
  };

  const prepareDownload = (link, release, asset, eager = false) => {
    link.downloadCleanup?.();
    link.href = downloadUrl(asset);
    let active = true;
    let busy = false;
    const choose = async () => {
      const source = await window.releaseData.selectDownloadSource(asset, downloadSources);
      if (active) {
        link.href = source?.url || downloadUrl(asset);
      }
      return source;
    };
    const click = async (event) => {
      if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      if (busy) return;
      busy = true;
      link.setAttribute('aria-busy', 'true');
      try {
        const source = await choose();
        if (!active || !link.isConnected) return;
        if (!source) {
          openPackage(release, asset, link);
          return;
        }
        const download = make('a');
        download.href = source.url;
        download.setAttribute('download', asset.name);
        download.rel = 'noreferrer';
        download.hidden = true;
        document.body.append(download);
        download.click();
        download.remove();
      } finally {
        busy = false;
        if (active) link.removeAttribute('aria-busy');
      }
    };
    link.addEventListener('click', click);
    link.downloadCleanup = () => {
      active = false;
      link.removeEventListener('click', click);
      link.removeAttribute('aria-busy');
    };
    if (eager) void choose();
  };

  let latestRelease;
  let selectedAsset;
  let latestRequest;
  let latestSequence = 0;
  let latestRetryTimer;
  let latestRetryCount = 0;
  let latestPending = true;
  let latestBusy = false;
  const retryDelays = [2000, 5000, 12000];
  const selectAsset = (asset) => {
    selectedAsset = asset;
    get('size').textContent = size(asset.sizeBytes);
    get('system').textContent = system(asset.minSdk ?? latestRelease.minSdk);
    get('filename').textContent = asset.name;
    get('download-link').href = downloadUrl(asset);
    get('download-link').setAttribute('download', asset.name);
    get('download-link').setAttribute('aria-label', `下载财小通 ${latestRelease.versionName}，${asset.name}`);
    prepareDownload(get('download-link'), latestRelease, asset, true);
  };
  const loadLatest = async (retry = false) => {
    clearTimeout(latestRetryTimer);
    if (!retry) latestRetryCount = 0;
    const sequence = ++latestSequence;
    latestRequest?.controller.abort();
    latestRequest = timedController();
    const current = latestRequest;
    latestPending = true;
    latestBusy = true;
    get('latest-panel').setAttribute('aria-busy', 'true');
    get('latest-content').hidden = !latestRelease;
    get('latest-state').hidden = Boolean(latestRelease);
    try {
      const release = await request('latest', current.controller.signal);
      if (sequence !== latestSequence) return;
      if (!validRelease(release)) throw new Error('Invalid release response');
      latestRelease = release;
      get('latest-notes-open').hidden = !release.notes.trim();
      get('version').textContent = `v${release.versionName}`;
      get('published').textContent = date(release.publishedAt);
      get('asset-select').replaceChildren(...release.assets.map((asset, index) => {
        const option = make('option', '', `${asset.name} · ${size(asset.sizeBytes)}`);
        option.value = String(index);
        return option;
      }));
      get('asset-picker').hidden = release.assets.length <= 1;
      selectAsset(release.assets[0]);
      get('latest-content').hidden = false;
      get('latest-state').hidden = true;
      latestPending = false;
      get('release-fallback').hidden = true;
      get('latest-panel').setAttribute('aria-busy', 'false');
    } catch {
      if (sequence !== latestSequence) return;
      get('latest-state').hidden = true;
      get('latest-panel').setAttribute('aria-busy', 'false');
      get('release-fallback').hidden = false;
      if (latestRetryCount < retryDelays.length) {
        latestRetryTimer = setTimeout(() => loadLatest(true), retryDelays[latestRetryCount++]);
      }
    } finally {
      current.clear();
      if (sequence === latestSequence) latestBusy = false;
    }
  };
  get('latest-notes-open').addEventListener('click', (event) => openNotes(latestRelease, event.currentTarget));
  get('package-open').addEventListener('click', (event) => openPackage(latestRelease, selectedAsset, event.currentTarget));
  get('asset-select').addEventListener('change', (event) => selectAsset(latestRelease.assets[Number(event.target.value)]));
  get('copy-sha').addEventListener('click', () => copy(get('package-sha').textContent, get('package-copy-state')));
  get('copy-page').addEventListener('click', () => {
    const url = new URL(location.href);
    url.hash = '';
    url.search = '';
    copy(url.href);
  });
  const isAppleMobile = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  if (isAppleMobile) {
    get('device-hint').hidden = false;
    get('device-hint').textContent = '此安装包适用于 Android 手机和平板。请在 Android 设备上打开此页面下载。';
  }

  let cursor = null;
  let historyRequest;
  let historySequence = 0;
  let searchTimer;
  let historyQuery = '';
  let retryAppend = false;
  let historyRetryTimer;
  let historyRetryCount = 0;
  let historyPending = true;
  let historyBusy = false;
  const knownIds = new Set();
  const showHistorySkeleton = (append) => {
    const template = document.getElementById('history-skeleton-template');
    get('history-skeleton').replaceChildren(...Array.from({ length: append ? 1 : 3 }, () => template.content.cloneNode(true)));
    get('history-skeleton').hidden = false;
  };
  const animateDisclosure = (card, stateAttribute) => {
    const summary = card.querySelector('summary');
    let animation;
    let expanded = card.open;
    summary.addEventListener('click', (event) => {
      event.preventDefault();
      const startHeight = card.getBoundingClientRect().height;
      expanded = animation ? !expanded : !card.open;
      animation?.cancel();
      card.dataset[stateAttribute] = String(expanded);
      if (reducedMotion.matches || !card.animate) {
        animation = null;
        card.open = expanded;
        return;
      }
      card.open = true;
      const style = getComputedStyle(card);
      const endHeight = expanded ? card.getBoundingClientRect().height : summary.getBoundingClientRect().height + parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth);
      animation = card.animate([{ height: `${startHeight}px` }, { height: `${endHeight}px` }], {
        duration: 280, easing: 'cubic-bezier(.16, 1, .3, 1)',
      });
      animation.onfinish = () => { card.open = expanded; animation = null; };
    });
  };
  const noteHighlights = (notes) => {
    if (typeof notes !== 'string' || !notes.trim()) return '';
    const tokens = window.marked.lexer(notes, { gfm: true });
    const excerpt = [];
    excerpt.links = tokens.links;
    let remaining = 3;
    for (const token of tokens) {
      if (token.type === 'space') continue;
      // Omit the release title, keeping section headings and complete content blocks.
      if (token.type === 'heading') {
        if (token.depth > 2) excerpt.push(token);
        continue;
      }
      if (token.type === 'list') {
        const items = token.items.slice(0, remaining);
        excerpt.push({ ...token, items });
        remaining -= items.length;
      } else {
        excerpt.push(token);
        remaining--;
      }
      if (remaining <= 0) break;
    }
    return window.marked.parser(excerpt, { gfm: true });
  };
  const historyItem = (release) => {
    const item = make('article', 'history-release');
    const meta = make('header', 'history-release__meta');
    const version = make('div', 'history-version');
    version.append(make('h3', '', `v${release.versionName}`));
    const published = make('time', '', date(release.publishedAt));
    published.dateTime = release.publishedAt;
    meta.append(version, published);
    const content = make('div', 'history-release__content');
    const highlights = noteHighlights(release.notes);
    if (highlights.length) {
      const summary = make('div', 'history-highlights release-notes');
      summary.append(sanitizeMarkdown(highlights));
      content.append(summary);
    }
    const notesButton = make('button', 'notes-link history-notes-link', '查看完整更新说明');
    notesButton.type = 'button';
    notesButton.hidden = !release.notes.trim();
    notesButton.setAttribute('aria-haspopup', 'dialog');
    notesButton.setAttribute('aria-controls', notesDialog.id);
    notesButton.append(icon('m8 5 5 5-5 5'));
    notesButton.addEventListener('click', () => openNotes(release, notesButton));
    content.append(notesButton);
    const assets = make('div', 'history-assets');
    for (const asset of release.assets) {
      const row = make('div', 'history-asset');
      const info = make('div');
      if (release.assets.length > 1) info.append(make('strong', '', asset.name));
      const metadata = make('p', 'history-asset__meta');
      metadata.append(make('span', '', system(asset.minSdk ?? release.minSdk)), make('span', '', size(asset.sizeBytes)));
      info.append(metadata);
      const detailsButton = make('button', 'notes-link history-package-link', '详情与校验');
      detailsButton.type = 'button';
      detailsButton.setAttribute('aria-haspopup', 'dialog');
      detailsButton.setAttribute('aria-controls', packageDialog.id);
      detailsButton.append(icon('m8 5 5 5-5 5'));
      detailsButton.addEventListener('click', () => openPackage(release, asset, detailsButton));
      info.append(detailsButton);
      const link = make('a', '', '下载安装包');
      link.href = downloadUrl(asset);
      link.setAttribute('download', asset.name);
      link.setAttribute('aria-label', `下载 ${asset.name}`);
      link.append(icon('M10 3v9m0 0 3.5-3.5M10 12 6.5 8.5M4 16h12'));
      prepareDownload(link, release, asset);
      row.append(info, link);
      assets.append(row);
    }
    content.append(assets);
    item.append(meta, content);
    return item;
  };
  const loadHistory = async (append = false, retry = false) => {
    clearTimeout(historyRetryTimer);
    if (!retry) historyRetryCount = 0;
    const sequence = ++historySequence;
    historyRequest?.controller.abort();
    historyRequest = timedController();
    const current = historyRequest;
    retryAppend = append;
    historyPending = true;
    historyBusy = true;
    if (!append && !retry) {
      cursor = null;
      knownIds.clear();
      get('release-list').replaceChildren();
      get('history-total').textContent = '';
      get('history-records').scrollTop = 0;
    }
    get('history-records').setAttribute('aria-busy', 'true');
    get('history-status').textContent = '';
    showHistorySkeleton(append);
    get('load-more').hidden = true;
    const params = new URLSearchParams({ channel: 'stable', limit: '10', q: historyQuery });
    if (append && cursor) params.set('cursor', cursor);
    try {
      const response = await request(`history?${params}`, current.controller.signal);
      if (sequence !== historySequence) return;
      if (!Array.isArray(response.items) || !response.items.every(validRelease) || !Number.isInteger(response.total)) throw new Error('Invalid release list');
      const fragment = document.createDocumentFragment();
      for (const release of response.items) {
        if (knownIds.has(release.id)) continue;
        knownIds.add(release.id);
        fragment.append(historyItem(release));
      }
      get('release-list').append(fragment);
      get('history-skeleton').hidden = true;
      cursor = response.nextCursor || null;
      get('history-total').textContent = `${response.total} 个版本`;
      get('history-status').textContent = response.total === 0 ? historyQuery ? '没有找到匹配的正式版本，试试其他关键词。' : '暂无正式版记录。' : '';
      get('load-more').hidden = !cursor;
      historyPending = false;
    } catch (error) {
      if (sequence !== historySequence) return;
      get('history-skeleton').hidden = true;
      get('history-status').textContent = '版本记录暂时不可用，请稍后重试。';
      historyPending = true;
      if (error.status === 400 && append) {
        // An expired cursor restarts from page one while preserving readable cards.
        cursor = null;
      }
      if (historyRetryCount < retryDelays.length) {
        historyRetryTimer = setTimeout(() => loadHistory(append, true), retryDelays[historyRetryCount++]);
      }
    } finally {
      current.clear();
      if (sequence === historySequence) {
        historyBusy = false;
        get('history-records').setAttribute('aria-busy', String(historyPending));
      }
    }
  };
  const searchHistory = () => {
    clearTimeout(searchTimer);
    clearTimeout(historyRetryTimer);
    historyQuery = get('search').value.trim();
    loadHistory();
  };
  get('search-form').addEventListener('submit', (event) => { event.preventDefault(); searchHistory(); });
  get('search').addEventListener('input', () => {
    clearTimeout(searchTimer);
    clearTimeout(historyRetryTimer);
    // Cancel before the debounce, so an old response cannot overwrite a new search.
    historySequence++;
    historyRequest?.controller.abort();
    historyBusy = false;
    get('load-more').hidden = true;
    searchTimer = setTimeout(searchHistory, 300);
  });
  get('load-more').addEventListener('click', () => loadHistory(true));

  const resumeRequests = () => {
    if (document.hidden || !navigator.onLine) return;
    if (latestPending && !latestBusy) loadLatest();
    if (historyPending && !historyBusy) loadHistory(retryAppend, true);
  };
  window.addEventListener('online', resumeRequests);
  document.addEventListener('visibilitychange', resumeRequests);

  document.querySelectorAll('.faq-list details').forEach((card) => animateDisclosure(card, 'faqExpanded'));

  loadLatest();
  loadHistory();
})();

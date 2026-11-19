(() => {
  'use strict';

  let pending;
  const load = () => {
    if (!pending) {
      pending = (async () => {
        const response = await fetch(new URL('releases.json', document.baseURI), {
          credentials: 'omit', cache: 'no-cache', signal: AbortSignal.timeout(10000)
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        const official = /^https:\/\/github\.com\/[^/]+\/[^/]+\/releases\/download\//;
        const valid = (release) => release && typeof release.id === 'string' &&
          typeof release.versionName === 'string' && release.channel === 'stable' &&
          typeof release.notes === 'string' && Number.isFinite(Date.parse(release.publishedAt)) &&
          Array.isArray(release.assets) && release.assets.length > 0 &&
          release.assets.every((asset) => typeof asset.id === 'string' && typeof asset.name === 'string' &&
            official.test(asset.downloadUrl) && asset.name.endsWith('.apk') &&
            Number.isInteger(asset.minSdk) && asset.minSdk > 0 &&
            typeof asset.packageName === 'string' && asset.packageName.length > 0 &&
            Number.isFinite(asset.sizeBytes) && asset.sizeBytes > 0 && /^[a-fA-F0-9]{64}$/.test(asset.sha256));
        if (data.schemaVersion !== 1 || !Array.isArray(data.releases) || !data.releases.every(valid) ||
          (data.latest !== null && !valid(data.latest)) || !Array.isArray(data.downloadSources) ||
          !data.downloadSources.every((source) => typeof source.label === 'string' &&
            typeof source.template === 'string' && (source.template === '{officialUrl}' || source.template.startsWith('https://')))) {
          throw new Error('Invalid release snapshot');
        }
        return data;
      })().catch((error) => { pending = null; throw error; });
    }
    return pending;
  };
  const downloadUrl = (asset, template = '{officialUrl}') => {
    const url = new URL(asset.downloadUrl);
    const parts = url.pathname.split('/');
    const values = { officialUrl: asset.downloadUrl, owner: parts[1], repo: parts[2], tag: parts[5], apk: parts.slice(6).join('/') };
    const expanded = template.replace(/\{(officialUrl|owner|repo|tag|apk)\}/g, (_, key) => values[key]);
    const result = new URL(expanded);
    if (result.protocol !== 'https:') throw new Error('Invalid download URL');
    return result.href;
  };
  const selections = new Map();
  const probeSource = async (asset, source, signal) => {
    const controller = new AbortController();
    const abort = () => controller.abort();
    signal.addEventListener('abort', abort, { once: true });
    const timer = setTimeout(() => controller.abort(), 8000);
    let reader;
    try {
      const url = downloadUrl(asset, source.template);
      const sampleSize = Math.min(16384, asset.sizeBytes);
      const started = performance.now();
      const response = await fetch(url, {
        mode: 'cors', credentials: 'omit', cache: 'no-store',
        headers: { Range: `bytes=0-${sampleSize - 1}` }, signal: controller.signal
      });
      if (![200, 206].includes(response.status) || !response.body) return null;
      const type = response.headers.get('content-type') || '';
      if (/text\/|html|json|xml/i.test(type)) return null;
      const range = response.headers.get('content-range');
      if (range && range !== `bytes 0-${sampleSize - 1}/${asset.sizeBytes}`) return null;
      const length = response.headers.get('content-length');
      if (length && Number(length) !== (response.status === 206 ? sampleSize : asset.sizeBytes)) return null;
      reader = response.body.getReader();
      const signature = [];
      let received = 0;
      while (received < sampleSize) {
        const { done, value } = await reader.read();
        if (done) return null;
        for (const byte of value.subarray(0, 4 - signature.length)) signature.push(byte);
        received += value.length;
        if (signature.length === 4 && !signature.every((byte, index) => byte === [80, 75, 3, 4][index])) return null;
      }
      if (signature.length !== 4) return null;
      return { url, label: source.label, elapsed: performance.now() - started };
    } catch {
      // CORS failures, redirects to unreadable pages and timeouts exclude only this attempt.
      return null;
    } finally {
      clearTimeout(timer);
      signal.removeEventListener('abort', abort);
      if (reader) void reader.cancel().catch(() => {});
      controller.abort();
    }
  };
  const selectDownloadSource = (asset, sources) => {
    const key = JSON.stringify([asset.downloadUrl, asset.sizeBytes, sources]);
    const cached = selections.get(key);
    if (cached && (cached.pending || cached.expires > Date.now())) return cached.promise;
    const entry = { pending: true, expires: 0 };
    entry.promise = (async () => {
      const unique = [...new Map(sources.map((source) => [source.template, source])).values()];
      const controller = new AbortController();
      let best;
      try {
        best = await Promise.any(unique.map(async (source) => {
          const result = await probeSource(asset, source, controller.signal);
          if (!result) throw new Error('Unavailable download source');
          return result;
        }));
      } catch {
        best = null;
      } finally {
        controller.abort();
      }
      entry.pending = false;
      if (best) entry.expires = Date.now() + 120000;
      else selections.delete(key);
      return best;
    })();
    if (selections.size >= 100) selections.delete(selections.keys().next().value);
    selections.set(key, entry);
    return entry.promise;
  };
  window.releaseData = Object.freeze({ load, downloadUrl, selectDownloadSource });
})();

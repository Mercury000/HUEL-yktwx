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
  window.releaseData = Object.freeze({ load, downloadUrl });
})();

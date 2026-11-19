const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const code = readFileSync(path.resolve(__dirname, '../../website/release-data.js'), 'utf8');
const asset = { downloadUrl: 'https://github.com/owner/repo/releases/download/v1.0.0/app.apk', sizeBytes: 32768 };
const sources = ['slow', 'fast', 'blocked', 'html'].map(label => ({ label, template: `https://${label}.example/{officialUrl}` }));
const sample = () => {
  const bytes = new Uint8Array(16384);
  bytes.set([80, 75, 3, 4]);
  return bytes;
};
const apk = (headers = {}) => new Response(sample(), { status: 206, headers: {
  'content-type': 'application/vnd.android.package-archive', 'content-length': '16384',
  'content-range': 'bytes 0-16383/32768', ...headers
} });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function setup(fetch, timers = {}) {
  let now = 0;
  const context = { window: {}, fetch, URL, AbortController, performance,
    Date: { now: () => now }, setTimeout: timers.setTimeout || ((fn, ms) => setTimeout(fn, Math.min(ms, 100))), clearTimeout: timers.clearTimeout || clearTimeout };
  vm.runInNewContext(code, context);
  return { select: context.window.releaseData.selectDownloadSource, advance: ms => { now += ms; } };
}

test('probes every source with a bounded CORS range and chooses the fastest APK response', async () => {
  const calls = [];
  const { select } = setup(async (url, options) => {
    calls.push(url);
    assert.equal(options.mode, 'cors');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.headers.Range, 'bytes=0-16383');
    if (url.includes('blocked.example')) throw new TypeError('CORS');
    if (url.includes('html.example')) return new Response('<html>Access denied</html>', { headers: { 'content-type': 'text/html' } });
    if (url.includes('slow.example')) await delay(30);
    return apk();
  });
  const selected = await select(asset, sources);
  assert.equal(selected.label, 'fast');
  assert.equal(calls.length, sources.length);
});

test('accepts CORS-readable APK bodies when Content-Range is not exposed', async () => {
  const { select } = setup(async () => new Response(sample(), { status: 206, headers: { 'content-type': 'application/octet-stream' } }));
  assert.equal((await select(asset, [sources[0]])).label, 'slow');
});

test('rejects error status, incorrect sizes, wrong ranges, HTML and non-APK bytes', async () => {
  const invalid = [
    () => new Response(sample(), { status: 403 }),
    () => apk({ 'content-length': '32' }),
    () => apk({ 'content-range': 'bytes 0-16383/99999' }),
    () => apk({ 'content-range': 'bytes 16384-32767/32768' }),
    () => new Response('<html>restricted</html>', { status: 200 }),
    () => new Response(new Uint8Array(16384), { status: 206 }),
    () => new Response(sample().slice(0, 8), { status: 206 }),
    () => apk({ 'content-type': 'text/html' })
  ];
  for (const response of invalid) {
    const { select } = setup(async () => response());
    assert.equal(await select(asset, [sources[0]]), null);
  }
});

test('reads split signatures and cancels servers that ignore Range after the sample', async () => {
  let cancelled = false;
  let index = 0;
  const chunks = [sample().slice(0, 2), sample().slice(2), new Uint8Array(16384)];
  const { select } = setup(async () => new Response(new ReadableStream({
    pull(controller) { controller.enqueue(chunks[index++]); },
    cancel() { cancelled = true; }
  }), { status: 200, headers: { 'content-length': '32768' } }));
  assert.ok(await select(asset, [sources[0]]));
  assert.equal(cancelled, true);
});

test('aborts stalled response bodies while keeping a healthy source eligible', async () => {
  let aborted = false;
  const { select } = setup(async (url, { signal }) => {
    if (url.includes('fast.example')) return apk();
    return new Response(new ReadableStream({ start(controller) {
      signal.addEventListener('abort', () => { aborted = true; controller.error(new Error('timeout')); }, { once: true });
    } }), { status: 206 });
  });
  assert.equal((await select(asset, sources.slice(0, 2))).label, 'fast');
  assert.equal(aborted, true);
});

test('returns the first verified response without waiting for a stalled source or its timeout', { timeout: 1000 }, async () => {
  let cancelled = false;
  const { select } = setup(async (url, { signal }) => {
    if (url.includes('fast.example')) return apk();
    return new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => { cancelled = true; reject(new Error('cancelled')); }, { once: true });
    });
  }, { setTimeout: () => 0, clearTimeout: () => {} });
  assert.equal((await select(asset, sources.slice(0, 2))).label, 'fast');
  assert.equal(cancelled, true);
});

test('fast error pages cannot win against a later verified APK', async () => {
  const { select } = setup(async url => {
    if (url.includes('html.example')) return new Response('<html>restricted</html>');
    await delay(20);
    return apk();
  });
  assert.equal((await select(asset, [sources[3], sources[1]])).label, 'fast');
});

test('coalesces concurrent probes, caches success briefly, then measures again', async () => {
  let calls = 0;
  const { select, advance } = setup(async () => { calls++; return apk(); });
  await Promise.all([select(asset, [sources[0]]), select(asset, [sources[0]])]);
  await select(asset, [sources[0]]);
  assert.equal(calls, 1);
  advance(120001);
  await select(asset, [sources[0]]);
  assert.equal(calls, 2);
});

test('failed sources participate again and new configuration is measured independently', async () => {
  let works = false;
  const calls = [];
  const { select } = setup(async url => {
    calls.push(url);
    if (!works) throw new TypeError('CORS');
    return apk();
  });
  assert.equal(await select(asset, [sources[0]]), null);
  works = true;
  assert.ok(await select(asset, [sources[0]]));
  assert.ok(await select(asset, [sources[1]]));
  assert.equal(calls.length, 3);
});

test('one malformed source cannot stop other candidates', async () => {
  const { select } = setup(async () => apk());
  assert.equal((await select(asset, [{ label: 'invalid', template: 'http://invalid.example/app.apk' }, sources[1]])).label, 'fast');
});

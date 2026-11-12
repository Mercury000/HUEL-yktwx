// Run against release-please 17.3.0, the dependency bundled by the pinned Action.
// Only the GitHub boundary is replaced; versioning, PR updates and releases use the real tool.
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');
const rpModule = process.env.RELEASE_PLEASE_MODULE || 'release-please';
const {Manifest, VERSION, setLogger} = require(rpModule);
const root = path.resolve(__dirname, '..');
const logger = Object.fromEntries(['info', 'warn', 'error', 'debug', 'trace'].map(key => [key, () => {}]));
setLogger(logger);

class RepositoryFixture {
  constructor() {
    this.repository = {owner: 'owner', repo: 'repo', defaultBranch: 'main'};
    this.sequence = 0;
    const files = new Map(['version.txt', '.release-please-manifest.json', '.release-please-manifest-beta.json',
      'release-please-config.json', 'release-please-config-beta.json'].map(file =>
      [file, readFileSync(path.join(root, file), 'utf8')]));
    // Keep the user's 1.4.0 -> 1.5.0 example independent of future repository releases.
    files.set('version.txt', '1.4.0\n');
    for (const manifest of ['.release-please-manifest.json', '.release-please-manifest-beta.json']) {
      files.set(manifest, JSON.stringify({'.': '1.4.0'}) + '\n');
    }
    const base = this.commit('chore: existing stable release');
    base.sha = JSON.parse(files.get('release-please-config.json'))['bootstrap-sha'];
    this.branches = {main: {files, commits: [base], prs: []}};
    this.releases = [{tagName: `v${JSON.parse(files.get('.release-please-manifest.json'))['.']}`, sha: base.sha}];
    this.sync('main', 'beta');
  }

  commit(message, files = ['app/src/main/Feature.kt']) {
    return {sha: (++this.sequence).toString(16).padStart(40, '0'), message, files};
  }

  sync(source, target) {
    const branch = this.branches[source];
    this.branches[target] = {files: new Map(branch.files), commits: [...branch.commits], prs: []};
  }

  addCommit(branch, message) {
    this.branches[branch].commits.unshift(this.commit(message));
  }

  async getFileJson(file, branch) {
    return JSON.parse(this.branches[branch].files.get(file));
  }

  async *releaseIterator() { yield* this.releases; }
  async *tagIterator() { yield* this.releases.map(release => ({name: release.tagName, sha: release.sha})); }
  async *mergeCommitIterator(branch) { yield* this.branches[branch].commits; }
  async *pullRequestIterator(branch) { yield* this.branches[branch].prs; }

  async manifest(branch) {
    return Manifest.fromManifest(this, branch,
      branch === 'beta' ? 'release-please-config-beta.json' : 'release-please-config.json',
      branch === 'beta' ? '.release-please-manifest-beta.json' : '.release-please-manifest.json', {logger});
  }

  async releasePR(branch, expectedVersion) {
    const candidates = await (await this.manifest(branch)).buildPullRequests();
    assert.equal(candidates.length, 1);
    assert.equal(candidates[0].version.toString(), expectedVersion);
    return candidates[0];
  }

  async mergeAndRelease(branch, pr) {
    const state = this.branches[branch];
    for (const update of pr.updates) {
      state.files.set(update.path, update.updater.updateContent(state.files.get(update.path), logger));
    }
    const commit = this.commit(pr.title.toString(), pr.updates.map(update => update.path));
    const merged = {number: this.sequence, title: pr.title.toString(), body: pr.body.toString(),
      headBranchName: pr.headRefName, baseBranchName: branch, labels: pr.labels,
      sha: commit.sha, files: commit.files};
    commit.pullRequest = merged;
    state.commits.unshift(commit);
    state.prs.unshift(merged);
    const releases = await (await this.manifest(branch)).buildReleases();
    assert.equal(releases.length, 1);
    const release = releases[0];
    assert.equal(release.tag.toString(), `v${pr.version}`);
    assert.equal(release.prerelease, branch === 'beta');
    assert.equal(release.draft, true);
    assert.equal(release.forceTag, true);
    assert.equal(release.sha, commit.sha);
    this.releases.unshift({tagName: release.tag.toString(), sha: release.sha, notes: release.notes});
    merged.labels = ['autorelease: tagged'];
    return release;
  }
}

test('the pinned Action version is used for the integration checks', () => {
  assert.equal(VERSION, '17.3.0');
});

test('beta PRs promote to one stable minor release, then start the next testing cycle', async () => {
  const repo = new RepositoryFixture();
  const initialStable = repo.branches.main.files.get('.release-please-manifest.json');
  repo.addCommit('beta', 'feat: add a tested feature');
  await repo.mergeAndRelease('beta', await repo.releasePR('beta', '1.5.0-beta'));
  repo.addCommit('beta', 'fix: correct the tested feature');
  await repo.mergeAndRelease('beta', await repo.releasePR('beta', '1.5.0-beta.1'));
  assert.equal(repo.branches.beta.files.get('.release-please-manifest.json'), initialStable);

  // A regular beta -> main merge keeps beta's commits and its generated files.
  repo.sync('beta', 'main');
  repo.addCommit('main', 'Merge beta into main');
  const stablePR = await repo.releasePR('main', '1.5.0');
  assert.match(stablePR.body.toString(), /add a tested feature/);
  assert.match(stablePR.body.toString(), /correct the tested feature/);
  await repo.mergeAndRelease('main', stablePR);
  for (const manifest of ['.release-please-manifest.json', '.release-please-manifest-beta.json']) {
    assert.equal(await repo.getFileJson(manifest, 'main').then(value => value['.']), '1.5.0');
  }
  assert.equal(repo.branches.main.files.get('version.txt').trim(), '1.5.0');

  // Syncing the stable version PR also advances beta's baseline via native extra-files.
  repo.sync('main', 'beta');
  repo.addCommit('beta', 'fix: next cycle correction');
  const next = await repo.releasePR('beta', '1.5.1-beta');
  assert.doesNotMatch(next.body.toString(), /add a tested feature/);
  await repo.mergeAndRelease('beta', next);
});

test('a squash merge with an accurate feature title releases 1.5.0 rather than 1.6.0', async () => {
  const repo = new RepositoryFixture();
  repo.addCommit('beta', 'feat: add a tested feature');
  await repo.mergeAndRelease('beta', await repo.releasePR('beta', '1.5.0-beta'));
  repo.branches.main.files = new Map(repo.branches.beta.files);
  repo.addCommit('main', 'feat: add the tested feature from beta');
  await repo.mergeAndRelease('main', await repo.releasePR('main', '1.5.0'));
});

test('ordinary commits create PRs without creating releases on either branch', async () => {
  const repo = new RepositoryFixture();
  for (const branch of ['main', 'beta']) {
    repo.addCommit(branch, 'fix: a pending correction');
    const manifest = await repo.manifest(branch);
    assert.equal((await manifest.buildPullRequests()).length, 1);
    assert.deepEqual(await manifest.buildReleases(), []);
  }
});

test('the first beta and stable PRs work with the repository legacy numeric tags', async () => {
  const repo = new RepositoryFixture();
  repo.releases[0].tagName = '9';
  repo.addCommit('beta', 'feat: begin the first testing cycle');
  await repo.mergeAndRelease('beta', await repo.releasePR('beta', '1.5.0-beta'));
  repo.sync('beta', 'main');
  repo.addCommit('main', 'Merge beta into main');
  await repo.mergeAndRelease('main', await repo.releasePR('main', '1.5.0'));
});

// Git Integration regression checks, run by run.sh against one rig gateway. Phases run in order
// (sep1 → comb → sep2 → sep3 → audit → merged → modes; ONLY=comma list picks some) and each check is one PASS/FAIL row.
// Env from run.sh: GW_URL GW_PASS CONTAINER LOCAL RIG_TOOL RIG_NAME RIG_RIG GIT_PORT LANIP W_PW R_PW STATE_DIR.
const path = require('path');
const fs = require('fs');
const { spawnSync } = require('child_process');
const PW = process.env.PLAYWRIGHT_DIR ||
  '/Home-Claude/ignition-claude-toolkit/plugins/ignition/skills/verify-view/tool/node_modules/playwright';
const { chromium } = require(PW);

const E = process.env;
const GW = E.GW_URL.replace(/\/+$/, '');
const NAME = E.RIG_NAME;
const PORT = +E.GIT_PORT;
const BR = '172.17.0.1';
const SRV = { dev: `https://${BR}:${PORT}`, prod: `https://${BR}:${PORT + 1}`,
  untrusted: `https://${BR}:${PORT + 2}`, redirect: `https://${BR}:${PORT + 3}` };
const D = `/tmp/gitreg-${NAME}`;
const DATA = '/usr/local/bin/ignition/data';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── rig helpers ────────────────────────────────────────────────────────────────────────────────
function rsh(script, { allowFail = false } = {}) {
  const cmd = E.LOCAL ? ['bash', ['-s']] : [E.RIG_TOOL, ['sh', '--rig', E.RIG_RIG, 'bash', '-s']];
  const r = spawnSync(cmd[0], cmd[1], { input: 'set -e\n' + script, encoding: 'utf8' });
  if (r.status !== 0 && !allowFail) throw new Error(`rig sh failed (${r.status}): ${(r.stderr || '') + (r.stdout || '')}`.slice(0, 600));
  return (r.stdout || '').trim();
}
// git on a repository inside the running container (run.sh installs git there), as the gateway
// user. rel is relative to the data folder.
function cgit(rel, args) {
  return rsh(`docker exec -u 2003 ${E.CONTAINER} git -c safe.directory='*' -c user.name=regtest -c user.email=regtest@example.com -C ${DATA}/${rel} ${args}`);
}
// A copy of a bare repository from the host inside the container, for a fetch by path.
function bare(server, repo) {
  const c = `/tmp/bare/${server}/${repo}.git`;
  rsh(`docker exec -u 0 ${E.CONTAINER} sh -c 'rm -rf ${c} && mkdir -p /tmp/bare/${server}'
docker cp ${D}/${server}/${repo}.git ${E.CONTAINER}:/tmp/bare/${server}/
docker exec -u 0 ${E.CONTAINER} chown -R 2003:2003 /tmp/bare`);
  return c;
}
const dexec = (cmd) => rsh(`docker exec ${E.CONTAINER} sh -c ${JSON.stringify(cmd)}`);
// Commit to a bare repository on the rig: clone, run script in the clone, push all branches.
// signed: commits are signed with run.sh's throwaway key, which the gateway trusts in place of
// GitHub's web-flow key.
function remoteCommit(server, repo, script, push = 'git push -q origin HEAD', signed = false) {
  const sign = signed ? `export GNUPGHOME=${D}/gnupg
git config user.signingkey "$(gpg --list-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')"
git config commit.gpgsign true` : '';
  return rsh(`W=$(mktemp -d); git clone -q ${D}/${server}/${repo}.git $W/c; cd $W/c
git config user.name remote-dev; git config user.email remote@example.com
${sign}
${script}
${push}
git rev-parse HEAD; rm -rf $W`);
}
const bareRev = (server, repo, ref) => rsh(`git -C ${D}/${server}/${repo}.git rev-parse ${ref} 2>/dev/null || true`);
const logLines = () => rsh(`cat ${D}/requests.log 2>/dev/null || true`).split('\n').filter(Boolean);

// ── gateway session ────────────────────────────────────────────────────────────────────────────
let browser, ctx, csrf;
async function login() {
  if (browser) await browser.close().catch(() => {});
  browser = await chromium.launch();
  ctx = await browser.newContext({ ignoreHTTPSErrors: true });
  const pg = await ctx.newPage();
  await pg.goto(GW + '/web/home', { waitUntil: 'networkidle', timeout: 90000 });
  const btn = pg.locator('text=Log In').first();
  await btn.waitFor({ state: 'visible', timeout: 30000 }); await pg.waitForTimeout(1500); await btn.click();
  const u = pg.locator('input').first(); await u.waitFor({ timeout: 30000 }); await u.fill('admin'); await u.press('Enter');
  const p = pg.locator('input[type="password"]').first(); await p.waitFor({ timeout: 30000 }); await p.fill(E.GW_PASS); await p.press('Enter');
  await pg.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
  await pg.waitForTimeout(2000);
  csrf = (await (await ctx.request.get(GW + '/data/git-config/csrf')).json()).token;
  if (!csrf) throw new Error('login failed: no CSRF token');
  await pg.close();
}
async function call(method, url, body, headers = {}) {
  const opts = { method, headers: { 'X-CSRF-Token': csrf, 'Content-Type': 'application/json', ...headers }, timeout: 180000, failOnStatusCode: false };
  if (body !== undefined) opts.data = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  const r = await ctx.request.fetch(url, opts);
  const text = await r.text();
  let json = null; try { json = JSON.parse(text); } catch (e) { /* not JSON */ }
  return { status: r.status(), json: json || {}, text };
}
const api = (m, p, b) => call(m, GW + '/data/git-config' + p, b);
const plat = (m, p, b, h) => call(m, GW + '/data/api/v1' + p, b, h);
const ok = (r) => r.status >= 200 && r.status < 300 && !r.json.error;
const refused = (r) => r.status >= 400;
const q = encodeURIComponent;

// ── results ────────────────────────────────────────────────────────────────────────────────────
const rows = [];
let phase = '';
async function check(name, fn) {
  let res, detail = '';
  try {
    const out = await fn();
    res = out === true || (out && out.pass) ? 'PASS' : 'FAIL';
    detail = out && out.detail ? out.detail : (out === true ? '' : JSON.stringify(out).slice(0, 300));
  } catch (e) { res = 'FAIL'; detail = 'error: ' + e.message.slice(0, 300); }
  rows.push({ phase, name, res, detail });
  console.log(`${res}  [${phase}] ${name}${res === 'FAIL' ? '  — ' + detail : ''}`);
}
const pass = (cond, detail) => ({ pass: !!cond, detail });
async function until(fn, ms = 30000, step = 1500) {
  const end = Date.now() + ms; let v;
  while (Date.now() < end) { v = await fn(); if (v) return v; await sleep(step); }
  return v;
}

// ── shared steps ───────────────────────────────────────────────────────────────────────────────
let holiday = 0;
async function topCommit() { return ((await api('GET', '/history?skip=0&limit=3')).json.commits || [])[0]; }
async function configChange() {
  const before = await topCommit();
  const name = `regtest-${Date.now()}-${++holiday}`;
  const r = await plat('POST', '/resources/ignition/holiday', [{ name, enabled: true, config: { date: '2026-12-25T00:00:00Z', repeatAnnually: true } }]);
  if (!ok(r)) throw new Error('holiday create ' + r.status + ' ' + r.text.slice(0, 200));
  const c = await until(async () => { const t = await topCommit(); return t && (!before || t.hash !== before.hash) ? t : null; }, 40000);
  if (!c) throw new Error('no auto-commit after a config change');
  const files = (await api('GET', '/commit-files?hash=' + c.hash)).json.files || [];
  return { name, commit: c, files };
}
async function project(name) { return ((await api('GET', '/projects')).json.projects || []).find((p) => p.name === name); }
const fileIn = (rel) => dexec(`cat ${DATA}/${rel} 2>/dev/null || true`);
const CODE = 'ignition/script-python/util/code.py';
async function scanProjects() { await plat('POST', '/scan/projects'); await sleep(3000); }
async function events() { return (await api('GET', '/events')).json.log || []; }
async function restart(mode) {
  // A mode is set only in ignition.conf; the restart is of this suite's own rig gateway.
  dexec(`sed -i '/ignition.config.mode/d' ${DATA}/ignition.conf` + (mode ? ` && echo 'wrapper.java.additional.90=-Dignition.config.mode=${mode}' >> ${DATA}/ignition.conf` : ''));
  rsh(`docker restart ${E.CONTAINER} >/dev/null`);
  const up = await until(async () => {
    const r = spawnSync('curl', ['-s', '-m3', GW + '/system/gwinfo'], { encoding: 'utf8' });
    return /ContextStatus=RUNNING/.test(r.stdout || '');
  }, 300000, 5000);
  if (!up) throw new Error('gateway did not come back');
  await sleep(8000);
  await login();
}
const credId = {};

// ── phases ─────────────────────────────────────────────────────────────────────────────────────
async function sep1() {
  phase = 'sep1';
  await check('init config versioning', async () => {
    const r = await api('POST', '/init', {});
    const s = (await api('GET', '/status')).json;
    return pass(ok(r) && s.initialized && s.layout === 'separate', `init ${r.status} ${r.text.slice(0, 120)} layout=${s.layout}`);
  });
  await check('config change auto-commits only config/', async () => {
    const { files } = await configChange();
    return pass(files.length > 0 && files.every((f) => f.path.startsWith('config/')), files.map((f) => f.path).join(','));
  });
  await check('Changes settle, History lists it, file diff reads it', async () => {
    const { name, commit, files } = await configChange();
    const f = files.find((x) => x.path.includes(name)) || files[0];
    const d = (await api('GET', `/file-diff?hash=${commit.hash}&path=${q(f.path)}`)).json;
    const s = await until(async () => { const j = (await api('GET', '/status')).json; return j.dirty === false ? j : null; }, 20000);
    const h = (await api('GET', '/history?skip=0&limit=50')).json.commits || [];
    return pass(s && h.length >= 3 && (d.new || '').includes('repeatAnnually'), `history=${h.length} dirty=${s ? s.dirty : '?'} diff=${(d.new || '').length}`);
  });
  await check('Git ignore tree below the root', async () => {
    const root = (await api('GET', '/tree?path=')).json.entries || [];
    const cfg = (await api('GET', '/tree?path=config')).json.entries || [];
    const c = root.find((e) => e.name === 'config');
    const res = cfg.find((e) => e.name === 'resources');
    return pass(c && !c.excluded && res && !res.excluded && root.some((e) => e.excluded),
      `config=${c && c.excluded} config/resources=${res && res.excluded} rootExcluded=${root.filter((e) => e.excluded).length}`);
  });
  await check('never-versioned block cannot be undone', async () => {
    const before = (await api('GET', '/ignore')).json.text;
    const r = await api('POST', '/ignore', { text: before + '\n!**/config/local/\n!/ignition.conf\n' });
    const after = (await api('GET', '/ignore')).json.text;
    const tail = after.trim().split('\n').slice(-12).map((l) => l.trim());
    const loc = ((await api('GET', '/tree?path=config')).json.entries || []).find((e) => e.name === 'local');
    await api('POST', '/ignore', { text: before });
    return pass(ok(r) && tail[0] === '**/config/local/' && tail[11] === '*.keystore' && (!loc || loc.excluded),
      `save ${r.status}; tail=${tail.join(' ')}; config/local excluded=${loc && loc.excluded}`);
  });
  await check('HTTPS credentials saved (writer, reader)', async () => {
    const w = await api('POST', '/credentials', { type: 'HTTPS', host: BR, username: 'writer', mode: 'inline', password: E.W_PW });
    const rd = await api('POST', '/credentials', { type: 'HTTPS', host: BR, username: 'reader', mode: 'inline', password: E.R_PW });
    credId.writer = w.json.id; credId.reader = rd.json.id;
    return pass(ok(w) && ok(rd) && credId.writer && credId.reader, `${w.status} ${rd.status}`);
  });
  await check('config remote: push goes main → main, as writer, to dev', async () => {
    const n = logLines().length;
    const s = await api('POST', '/remote', { uri: `${SRV.dev}/gateway.git`, branch: 'main', mode: 'inline', username: 'writer', password: E.W_PW });
    const p = await api('POST', '/push', {});
    const local = cgit('.', 'rev-parse HEAD');
    const lines = logLines().slice(n);
    return pass(ok(s) && ok(p) && p.json.from === 'main' && p.json.to === 'main' && bareRev('dev', 'gateway', 'main') === local
      && lines.some((l) => /-dev writer POST .*git-receive-pack 200/.test(l)),
    `save ${s.status} ${s.text.slice(0, 150)} push ${p.status} ${p.text.slice(0, 150)}`);
  });
  await check("an internal user source never commits its users' password hashes", async () => {
    const before = await topCommit();
    const r = await plat('POST', '/resources/ignition/user-source', [{ name: 'RegtestUsers', enabled: true,
      config: { profile: { type: 'INTERNAL' }, settings: {} } }]);
    const base = `${DATA}/config/resources/core/ignition/user-source`;
    dexec(`cp ${base}/default/users.json ${base}/RegtestUsers/users.json && sed -i 's/"admin"/"regtest"/' ${base}/RegtestUsers/users.json`);
    await plat('POST', '/scan/config');
    const c = await until(async () => { const t = await topCommit(); return t && (!before || t.hash !== before.hash) ? t : null; }, 40000);
    await sleep(5000);
    const tracked = cgit('.', 'ls-files').split('\n').filter((f) => /user-source\/[^/]+\/users\.json$/.test(f));
    const files = c ? ((await api('GET', '/commit-files?hash=' + c.hash)).json.files || []).map((f) => f.path) : [];
    const users = dexec(`test -f ${base}/RegtestUsers/users.json && echo yes || echo no`);
    return pass(ok(r) && c && files.some((f) => f.includes('RegtestUsers')) && !files.some((f) => f.endsWith('users.json'))
      && !tracked.length && users === 'yes', `create ${r.status}; commit files=${files.join(',')}; tracked=${tracked.join(',')}`);
  });
  await check('config remote: branch cfg-main pushes main → cfg-main', async () => {
    const s = await api('POST', '/remote', { uri: `${SRV.dev}/gateway.git`, branch: 'cfg-main', mode: 'inline', username: 'writer' });
    const p = await api('POST', '/push', {});
    const ok2 = ok(p) && p.json.to === 'cfg-main' && bareRev('dev', 'gateway', 'cfg-main') === cgit('.', 'rev-parse HEAD');
    await api('POST', '/remote', { uri: `${SRV.dev}/gateway.git`, branch: 'main', mode: 'inline', username: 'writer' });
    return pass(ok(s) && ok2, `save ${s.status} ${s.text.slice(0, 120)}; push ${p.status} ${JSON.stringify(p.json).slice(0, 150)}`);
  });

  await check('gateway repository: a lone master behind a remote main is renamed', async () => {
    cgit('.', 'branch -m main master');
    const offer = (await api('GET', '/remote')).json.renameMaster;
    const r = await api('POST', '/branch-rename-master', { repo: 'config' });
    const b = cgit('.', 'rev-parse --abbrev-ref HEAD');
    return pass(offer === 'main' && ok(r) && b === 'main', `offer=${offer} rename ${r.status} ${r.text.slice(0, 120)} now=${b}`);
  });

  await check('link Example_A to its remote (Separate)', async () => {
    const r = await api('POST', '/project-init', { project: 'Example_A', url: `${SRV.dev}/example-a.git`, httpsCredentialId: credId.writer, branch: 'main' });
    const p = await project('Example_A');
    return pass(ok(r) && p && p.versioned && p.branch === 'main' && p.remoteUrl, `${r.status} ${r.text.slice(0, 200)} ${JSON.stringify(p || {}).slice(0, 200)}`);
  });
  await check('init Example_B locally, starts on main', async () => {
    const r = await api('POST', '/project-init', { project: 'Example_B' });
    const p = await project('Example_B');
    return pass(ok(r) && p && p.versioned && p.branch === 'main', `${r.status} ${r.text.slice(0, 150)} branch=${p && p.branch}`);
  });
  await check('Branch column ↑1 ↓1', async () => {
    remoteCommit('dev', 'example-a', 'echo note > NOTES.md && git add NOTES.md && git commit -qm "Remote note"');
    cgit('projects/Example_A', 'commit -q --allow-empty -m "Local only"');
    cgit('projects/Example_A', `fetch -q ${bare('dev', 'example-a')} +refs/heads/*:refs/remotes/origin/*`);
    const p = await project('Example_A');
    cgit('projects/Example_A', 'reset -q --hard origin/main');
    const p2 = await project('Example_A');
    return pass(p && p.ahead === 1 && p.behind === 1 && p2.ahead === 0 && p2.behind === 0, `ahead=${p && p.ahead} behind=${p && p.behind}; after reset ${p2 && p2.ahead}/${p2 && p2.behind}`);
  });
  await check('Branches graph for a project', async () => {
    const g = (await api('GET', '/branch-graph?project=Example_A&limit=200')).json;
    return pass(g.scope === 'project' && (g.commits || []).length >= 2 && (g.branches || []).some((b) => b.name === 'main') && g.lanes >= 1,
      `scope=${g.scope} commits=${(g.commits || []).length} lanes=${g.lanes} ${g.error || ''}`);
  });
  await check('Sync — pull brings a remote commit in', async () => {
    const d = await api('POST', '/delivery', { project: 'Example_A', delivery: 'sync-pull', branch: 'main', intervalSeconds: 3600 });
    remoteCommit('dev', 'example-a', `printf "def hello():\\n\\treturn 'v2'\\n" > ${CODE} && git commit -qam "v2"`);
    const s = await api('POST', '/sync-now', { project: 'Example_A' });
    return pass(ok(d) && ok(s) && fileIn(`projects/Example_A/${CODE}`).includes("'v2'"), `delivery ${d.status} ${d.text.slice(0, 120)} sync ${s.text.slice(0, 200)}`);
  });
  await check('switch branch refused against the followed branch', async () => {
    remoteCommit('dev', 'example-a', `git checkout -qb feature-1 && printf "def hello():\\n\\treturn 'feature'\\n" > ${CODE} && git commit -qam feature`, 'git push -q origin feature-1');
    const s = await api('POST', '/sync-now', { project: 'Example_A' });
    const r = await api('POST', '/branch-checkout', { project: 'Example_A', branch: 'origin/feature-1' });
    return pass(r.json.ok === false && r.json.refused === 'followed', `sync: ${s.json.result}; switch: ${r.text.slice(0, 200)}`);
  });
  await check('switch branch refused while dirty', async () => {
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'off' });
    dexec(`echo '# local edit' >> ${DATA}/projects/Example_A/${CODE}`);
    const r = await api('POST', '/branch-checkout', { project: 'Example_A', branch: 'origin/feature-1' });
    cgit('projects/Example_A', `checkout -q -- ${CODE}`);
    return pass(r.json.ok === false && r.json.refused === 'dirty' && (r.json.files || []).some((f) => f.includes('code.py')), r.text.slice(0, 250));
  });
  await check('switch branch succeeds and the content changes', async () => {
    const r = await api('POST', '/branch-checkout', { project: 'Example_A', branch: 'origin/feature-1' });
    const p = await project('Example_A');
    return pass(r.json.ok && p.branch === 'feature-1' && fileIn(`projects/Example_A/${CODE}`).includes("'feature'"), r.text.slice(0, 200));
  });
  await check('pull of a deleted remote branch says so', async () => {
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'sync-pull', branch: 'feature-1', intervalSeconds: 3600 });
    rsh(`git -C ${D}/dev/example-a.git branch -D feature-1 >/dev/null`);
    const s = await api('POST', '/sync-now', { project: 'Example_A' });
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'off' });
    const b = await api('POST', '/branch-checkout', { project: 'Example_A', branch: 'main' });
    return pass(/deleted there/.test(s.json.result || '') && b.json.ok, `sync: ${s.text.slice(0, 250)}; back to main: ${b.text.slice(0, 100)}`);
  });
  await check('Rename local master to main', async () => {
    cgit('projects/Example_B', 'branch -m main master');
    const rr = await api('POST', '/project-remote', { project: 'Example_B', url: `${SRV.dev}/example-b.git` });
    cgit('projects/Example_B', `fetch -q ${bare('dev', 'example-b')} +refs/heads/*:refs/remotes/origin/*`);
    const p = await project('Example_B');
    const r = await api('POST', '/branch-rename-master', { project: 'Example_B' });
    const p2 = await project('Example_B');
    return pass(ok(rr) && p.renameMaster === 'main' && ok(r) && p2.branch === 'main', `remote ${rr.status}; offer=${p.renameMaster} rename ${r.text.slice(0, 150)} branch=${p2.branch}`);
  });
  await check('runner release replaces the project, keeps .git', async () => {
    const t = await api('POST', '/runner', { enabled: true, generateToken: true });
    E.RUNNER_TOKEN = t.json.token;
    await api('POST', '/delivery', { project: 'Example_B', delivery: 'runner-release' });
    const zip = makeZip('Example_B', 'Example_B release 2');
    const r = await call('POST', `${GW}/data/git-config/runner-release?project=Example_B&version=1.0.1`, zip,
      { Authorization: 'Bearer ' + E.RUNNER_TOKEN, 'Content-Type': 'application/zip' });
    const bad = await call('POST', `${GW}/data/git-config/runner-release?project=Example_B`, zip, { Authorization: 'Bearer nope', 'Content-Type': 'application/zip' });
    const v = fileIn('projects/Example_B/com.inductiveautomation.perspective/views/Main/view.json');
    const git = dexec(`test -d ${DATA}/projects/Example_B/.git && echo yes || echo no`);
    return pass(ok(r) && v.includes('release 2') && git === 'yes' && bad.status === 401, `release ${r.status} ${r.text.slice(0, 150)} bad=${bad.status} .git=${git}`);
  });
  await check('runner repo update pulls the branch', async () => {
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'runner-repo', branch: 'main' });
    remoteCommit('dev', 'example-a', `printf "def hello():\\n\\treturn 'v3'\\n" > ${CODE} && git commit -qam v3`);
    const r = await call('POST', `${GW}/data/git-config/runner-sync`, { project: 'Example_A' }, { Authorization: 'Bearer ' + E.RUNNER_TOKEN });
    return pass(ok(r) && fileIn(`projects/Example_A/${CODE}`).includes("'v3'"), `${r.status} ${r.text.slice(0, 200)}`);
  });
  await check('delivery blocked by policy answers 403; loosening it logs a warning', async () => {
    const lock = await api('POST', '/policy', { ...OPEN_POLICY, deliveryModes: ['replace'] });
    const r = await api('POST', '/delivery', { project: 'Example_A', delivery: 'sync-pull', branch: 'main' });
    const t0 = Date.now();
    await api('POST', '/policy', OPEN_POLICY);
    const w = await until(async () => (await events()).find((e) => e.type === 'policy' && e.outcome === 'warning' && Date.parse(e.timestamp) >= t0 - 5000), 20000);
    return pass(ok(lock) && r.status === 403 && w, `lock ${lock.status}; delivery ${r.status} ${r.text.slice(0, 150)}; warn=${w ? w.message.slice(0, 120) : 'none'}`);
  });
  await api('POST', '/delivery', { project: 'Example_A', delivery: 'off' });
  await security('sep1');
  phase = 'sep1';
  // A Separate release leaves its files uncommitted (a Designer commits them); the layout switch
  // that follows refuses uncommitted work, so commit as a Designer user would.
  await check('commit what the release left, both projects clean', async () => {
    for (const p of ['Example_A', 'Example_B']) cgit(`projects/${p}`, 'add -A');
    for (const p of ['Example_A', 'Example_B']) cgit(`projects/${p}`, 'commit -q -m "Designer commit" --allow-empty');
    const a = await project('Example_A'); const b = await project('Example_B');
    return pass(a.changes === 0 && b.changes === 0, `changes A=${a.changes} B=${b.changes}`);
  });
}

const OPEN_POLICY = { pushAllowed: true, designerCommit: true, branchSwitch: true, layoutSwitch: true, forcePushAllowed: true,
  allowInsecureRemotes: false, deliveryModes: ['pull', 'replace', 'runner'], allowedRemoteHosts: [] };
const LOCKED_POLICY = { pushAllowed: false, designerCommit: false, branchSwitch: false, layoutSwitch: false, forcePushAllowed: false,
  allowInsecureRemotes: false, deliveryModes: ['replace'], allowedRemoteHosts: [BR] };

// A project export zip of a fixture (project.json at the root) with its Main view's label set.
function makeZip(fixture, label) {
  const src = path.join(__dirname, 'fixtures', fixture);
  const out = path.join(E.STATE_DIR, fixture + '.zip');
  const r = spawnSync('python3', ['-I', '-c', `
import os, sys, zipfile
fixture_label = 'Example_B main view'
src, out, label = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(out, 'w') as z:
    for d, _, fs in os.walk(src):
        for f in fs:
            p = os.path.join(d, f); rel = os.path.relpath(p, src)
            z.writestr(rel, open(p).read().replace(fixture_label, label))
`, src, out, label], { encoding: 'utf8' });
  if (r.status !== 0) throw new Error('zip: ' + r.stderr);
  return fs.readFileSync(out);
}

async function security(tag) {
  phase = tag + ' security';
  await check('traversal project names refused', async () => {
    const bad = ['..', '../Example_A', 'Example_A/../Example_B', 'a/b', 'Example_A\\..', '.git', '~root'];
    const got = [];
    for (const n of bad) {
      got.push([n, (await api('POST', '/project-init', { project: n })).status]);
      got.push([n + '(graph)', (await api('GET', '/branch-graph?project=' + q(n))).status]);
      got.push([n + '(delivery)', (await api('POST', '/delivery', { project: n, delivery: 'off' })).status]);
    }
    if (E.RUNNER_TOKEN) got.push(['release ../x', (await call('POST', `${GW}/data/git-config/runner-release?project=..%2Fx`, 'x', { Authorization: 'Bearer ' + E.RUNNER_TOKEN })).status]);
    const leaks = got.filter(([, s]) => s < 400);
    return pass(!leaks.length, leaks.length ? 'accepted: ' + JSON.stringify(leaks) : `${got.length} refused`);
  });
  await check('file-diff: absolute, .., .git and never-versioned paths refused (with and without hash)', async () => {
    const head = cgit('.', 'rev-parse HEAD');
    const paths = ['/etc/passwd', '../ignition.conf', 'config/../../ignition.conf', '.git/config', 'config/../.git/config',
      'ignition.conf', 'config/local/README', 'keystore/x', 'db/config.idb', 'projects/../../etc/passwd'];
    const leaks = [];
    for (const p of paths) {
      for (const h of ['', `hash=${head}&`]) {
        const r = await api('GET', `/file-diff?${h}path=${q(p)}`);
        if (!refused(r) && ((r.json.old || '') + (r.json.new || '')).length) leaks.push(`${h ? 'hash ' : ''}${p} → ${r.status} ${r.text.slice(0, 60)}`);
      }
    }
    return pass(!leaks.length, leaks.join('; ') || `${paths.length * 2} refused or empty`);
  });
  await check('remote URLs: local, plain, loopback, link-local, backslash and password URLs refused', async () => {
    const urls = ['file:/x', 'file:x', 'FILE:/x', 'file:///etc', '/tmp/repo', `http://${BR}:${PORT}/gateway.git`, `git://${BR}/gateway.git`,
      'https://127.0.0.1/x.git', 'https://localhost/x.git', 'https://169.254.169.254/x.git', 'https://[::1]/x.git',
      `https://evil.example\\@${BR}:${PORT}/gateway.git`, `https://${BR}:${PORT}\\evil.example/gateway.git`,
      `https://writer:${E.W_PW}@${BR}:${PORT}/gateway.git`, `https://ghp_${'a'.repeat(36)}@${BR}:${PORT}/gateway.git`];
    const leaks = [];
    for (const u of urls) {
      const t = await api('POST', '/remote-test', { uri: u, mode: 'inline', username: 'writer', password: 'x' });
      const pr = await api('POST', '/project-remote', { project: 'Example_B', name: 'evil', url: u });
      if (!refused(t)) leaks.push(`test ${u} → ${t.status}`);
      if (!refused(pr)) leaks.push(`project-remote ${u} → ${pr.status}`);
    }
    const shown = leaks.map((l) => l.replace(E.W_PW, '***'));
    return pass(!leaks.length, shown.join('; ') || `${urls.length * 2} refused`);
  });
  await check("another user's credential cannot be attached", async () => {
    const c = await api('POST', '/credentials', { type: 'HTTPS', host: BR, username: 'reader', mode: 'inline', password: E.R_PW });
    const id = c.json.id;
    const f = dexec(`grep -rl '"id": *${id}[,}]' ${DATA}/config/resources/core/com.operametrix.ignition.git/git-user-https-credential/ 2>/dev/null | head -1`);
    if (!f) return pass(false, 'credential file not found for id ' + id);
    dexec(`sed -i 's/"ignitionUser": *"[^"]*"/"ignitionUser": "someone-else"/' ${f}`);
    await plat('POST', '/scan/config'); await sleep(6000);
    const r = await api('POST', '/project-credential', { project: 'Example_A', httpsCredentialId: id });
    const i = await api('POST', '/project-init', { project: 'Example_B', url: `${SRV.dev}/example-b.git`, httpsCredentialId: id, branch: 'main' });
    return pass(refused(r) && refused(i), `attach ${r.status} ${r.text.slice(0, 120)}; init ${i.status} ${i.text.slice(0, 100)}`);
  });
  await check('secret reference tested against a foreign host refused', async () => {
    const r = await api('POST', '/remote-test', { uri: 'https://git.other.example/x.git', mode: 'reference', providerName: 'any', secretName: 'any' });
    return pass(r.status === 403, `${r.status} ${r.text.slice(0, 150)}`);
  });
  await check('untrusted TLS certificate refused, credential never sent', async () => {
    const n = logLines().length;
    const r = await api('POST', '/remote-test', { uri: `${SRV.untrusted}/gateway.git`, mode: 'inline', username: 'writer', password: E.W_PW });
    const hit = logLines().slice(n).filter((l) => l.includes('-untrusted '));
    return pass(refused(r) && !hit.length && /certif|PKIX|trust|SSL/i.test(r.text), `${r.status} ${r.text.slice(0, 160)} hits=${hit.length}`);
  });
  await check('redirect to another host not followed', async () => {
    const n = logLines().length;
    const r = await api('POST', '/remote-test', { uri: `${SRV.redirect}/gateway.git`, mode: 'inline', username: 'writer', password: E.W_PW });
    const lines = logLines().slice(n);
    const sink = lines.filter((l) => l.includes('-sink '));
    return pass(refused(r) && lines.some((l) => l.includes('-redirect ')) && !sink.length, `${r.status} ${r.text.slice(0, 160)} sink=${sink.length}`);
  });
  await check('unreadable policy reads as locked', async () => {
    const f = dexec(`find ${DATA}/config/resources/core/com.operametrix.ignition.git/git-policy -name config.json | head -1`);
    if (!f) return pass(false, 'no core policy file');
    const orig = dexec(`base64 -w0 ${f}`);
    dexec(`printf '{not json' > ${f}`);
    await plat('POST', '/scan/config');
    const s = await until(async () => { const p = (await api('GET', '/status')).json.policy; return p && p.pushAllowed === false ? p : null; }, 25000);
    dexec(`echo ${orig} | base64 -d > ${f}`);
    await plat('POST', '/scan/config');
    const back = await until(async () => { const p = (await api('GET', '/status')).json.policy; return p && p.pushAllowed ? p : null; }, 25000);
    return pass(s && !s.branchSwitch && !s.layoutSwitch && back, `locked=${!!s} restored=${!!back}`);
  });
}

async function comb() {
  phase = 'comb';
  await check('layout switch refused while a project is dirty', async () => {
    dexec(`echo '# dirty' >> ${DATA}/projects/Example_A/${CODE}`);
    const r = await api('POST', '/layout', { layout: 'combined', restoreHistory: false });
    cgit('projects/Example_A', `checkout -q -- ${CODE}`);
    const s = (await api('GET', '/status')).json;
    return pass(refused(r) && s.layout === 'separate', `${r.status} ${r.text.slice(0, 150)}`);
  });
  await check('switch to Combined', async () => {
    const r = await api('POST', '/layout', { layout: 'combined', restoreHistory: false });
    const s = (await api('GET', '/status')).json;
    const g = dexec(`test -d ${DATA}/projects/Example_A/.git && echo yes || echo no; ls ${DATA}/var/git-archive | wc -l`);
    return pass(ok(r) && s.layout === 'combined' && g.startsWith('no'), `${r.status} ${r.text.slice(0, 150)} ${g.replace('\n', ' archived=')}`);
  });
  await check('config change auto-commits only config/ (Combined)', async () => {
    const { files } = await configChange();
    return pass(files.length && files.every((f) => f.path.startsWith('config/')), files.map((f) => f.path).join(','));
  });
  await check('projects list the gateway repository branch; graph is filtered', async () => {
    const p = await project('Example_A');
    const g = (await api('GET', '/branch-graph?project=Example_A')).json;
    return pass(p && p.versioned && p.branch === 'main' && g.scope === 'gateway' && g.filtered, `branch=${p && p.branch} scope=${g.scope} filtered=${g.filtered} ${g.error || ''}`);
  });
  await check('push the gateway repository main → main', async () => {
    const p = await api('POST', '/push', {});
    return pass(ok(p) && p.json.to === 'main' && bareRev('dev', 'gateway', 'main') === cgit('.', 'rev-parse HEAD'), `${p.status} ${p.text.slice(0, 200)}`);
  });
  await check('gateway branch switch: refused dirty, then succeeds with content', async () => {
    remoteCommit('dev', 'gateway', `git checkout -qb gw-feature && printf "def hello():\\n\\treturn 'gw'\\n" > projects/Example_A/${CODE} && git commit -qam gw`, 'git push -q origin gw-feature');
    cgit('.', `fetch -q ${bare('dev', 'gateway')} +refs/heads/*:refs/remotes/origin/*`);
    dexec(`echo '# dirty' >> ${DATA}/projects/Example_A/${CODE}`);
    const d = await api('POST', '/branch-checkout', { project: 'Example_A', branch: 'origin/gw-feature' });
    cgit('.', `checkout -q -- projects/Example_A/${CODE}`);
    const r = await api('POST', '/branch-checkout', { project: 'Example_A', branch: 'origin/gw-feature' });
    const c = fileIn(`projects/Example_A/${CODE}`);
    const back = await api('POST', '/branch-checkout', { repo: 'config', branch: 'main' });
    return pass(d.json.refused === 'dirty' && r.json.ok && c.includes("'gw'") && back.json.ok, `dirty: ${d.text.slice(0, 120)}; switch: ${r.text.slice(0, 120)}; back: ${back.text.slice(0, 80)}`);
  });
  await check('Sync — replace keeps a gateway-only project and warns', async () => {
    dexec(`cp -r ${DATA}/projects/Example_B ${DATA}/projects/Example_C && sed -i 's/"Example_B"/"Example_C"/' ${DATA}/projects/Example_C/project.json`);
    await scanProjects();
    remoteCommit('dev', 'gateway', 'echo replaced > projects/Example_A/NOTES-replace.md && git add -A && git commit -qm "replace me"');
    const t0 = Date.now();
    const d = await api('POST', '/config-delivery', { mode: 'replace', branch: 'main', intervalSeconds: 3600 });
    const s = await api('POST', '/config-sync-now', {});
    const kept = dexec(`test -f ${DATA}/projects/Example_C/project.json && echo yes || echo no`);
    const got = fileIn('projects/Example_A/NOTES-replace.md');
    const w = (await events()).find((e) => e.outcome === 'warning' && /Example_C/.test(e.message) && Date.parse(e.timestamp) >= t0 - 5000);
    await api('POST', '/config-delivery', { mode: 'off', branch: 'main', intervalSeconds: 3600 });
    // Untracked, Example_C is uncommitted work: a later Sync — pull or layout switch refuses it.
    const del = await plat('DELETE', '/projects/Example_C');
    await sleep(4000);
    return pass(ok(d) && ok(s) && kept === 'yes' && got.includes('replaced') && w && del.status < 300, `delivery ${d.status} sync ${s.text.slice(0, 150)} kept=${kept} warn=${!!w}`);
  });
  await check('Sync — pull merges the branch in (Combined)', async () => {
    remoteCommit('dev', 'gateway', 'echo pulled > projects/Example_A/NOTES-pull.md && git add -A && git commit -qm "pull me"');
    const d = await api('POST', '/config-delivery', { mode: 'pull', branch: 'main', intervalSeconds: 3600 });
    const s = await api('POST', '/config-sync-now', {});
    await api('POST', '/config-delivery', { mode: 'off', branch: 'main', intervalSeconds: 3600 });
    return pass(ok(d) && ok(s) && fileIn('projects/Example_A/NOTES-pull.md').includes('pulled'), `delivery ${d.status} ${d.text.slice(0, 100)} sync ${s.text.slice(0, 200)}`);
  });
  await check('runner release commits that project only (Combined)', async () => {
    const zip = makeZip('Example_B', 'Example_B release 3');
    const before = await topCommit();
    const r = await call('POST', `${GW}/data/git-config/runner-release?project=Example_B&version=1.0.2`, zip, { Authorization: 'Bearer ' + E.RUNNER_TOKEN, 'Content-Type': 'application/zip' });
    const c = await until(async () => { const t = await topCommit(); return t && t.hash !== before.hash ? t : null; }, 20000);
    const files = c ? (await api('GET', '/commit-files?hash=' + c.hash)).json.files || [] : [];
    return pass(ok(r) && files.length && files.every((f) => f.path.startsWith('projects/Example_B/')), `${r.status} ${r.text.slice(0, 120)} files=${files.map((f) => f.path).slice(0, 5).join(',')}`);
  });
  await check('project delivery other than Off / Runner — release refused in Combined', async () => {
    const r = await api('POST', '/delivery', { project: 'Example_A', delivery: 'sync-pull', branch: 'main' });
    return pass(refused(r), `${r.status} ${r.text.slice(0, 160)}`);
  });
  await check('Combined ignore rules: config globs scoped, projects/ rules apply, data.bin an ordinary tick', async () => {
    const text = (await api('GET', '/ignore')).json.text || '';
    dexec(`mkdir -p ${DATA}/projects/Example_A/ignition/script-python/db && printf 'x = 1\\n' > ${DATA}/projects/Example_A/ignition/script-python/db/code.py`);
    const db = ((await api('GET', '/tree?path=' + q('projects/Example_A/ignition/script-python/db'))).json.entries || []).find((e) => e.name === 'code.py');
    const gp = ((await api('GET', '/tree?path=' + q('projects/Example_A/ignition/global-props'))).json.entries || []).find((e) => e.name === 'data.bin');
    const lines = text.split('\n').map((l) => l.trim());
    const ok2 = lines.includes('!/projects/') && !lines.includes('!/projects/**') && lines.includes('/config/**/db/*')
      && lines.indexOf('/projects/*/ignition/global-props/data.bin') > lines.indexOf('# --- end of Combined layout ---');
    cgit('.', 'add projects/Example_A/ignition/script-python/db/code.py');
    cgit('.', 'commit -q -m "db package" --only projects/Example_A/ignition/script-python/db/code.py');
    return pass(ok2 && db && !db.excluded && (!gp || gp.excluded), `rules ok=${ok2} db/code.py excluded=${db && db.excluded} data.bin excluded=${gp && gp.excluded}`);
  });
  await check('switch to Separate refused for a project folder linked outside the data directory', async () => {
    dexec(`rm -rf /tmp/outside-project && mkdir -p /tmp/outside-project && cp ${DATA}/projects/Example_B/project.json /tmp/outside-project/ && ln -s /tmp/outside-project ${DATA}/projects/Linked`);
    const r = await api('POST', '/layout', { layout: 'separate', restoreHistory: true });
    dexec(`rm -f ${DATA}/projects/Linked; rm -rf /tmp/outside-project`);
    const s2 = (await api('GET', '/status')).json;
    const left = dexec(`test -d /tmp/outside-project/.git && echo yes || echo no`);
    return pass(refused(r) && /projects\/Linked/.test(r.text) && s2.layout === 'combined' && left === 'no', `${r.status} ${r.text.slice(0, 200)}`);
  });
  await check('switch to Separate refused for a leftover .git, naming the archive option', async () => {
    cgit('projects/Example_B', 'init -q');
    const r = await api('POST', '/layout', { layout: 'separate', restoreHistory: true });
    return pass(refused(r) && /Example_B already has its own .git/.test(r.text) && /Archive leftover/.test(r.text), `${r.status} ${r.text.slice(0, 220)}`);
  });
  await security('comb');
}

async function sep2() {
  phase = 'sep2';
  await check('switch back to Separate, restoring history', async () => {
    const r = await api('POST', '/layout', { layout: 'separate', restoreHistory: true, archiveLeftovers: true });
    const s = (await api('GET', '/status')).json;
    const g = (await api('GET', '/branch-graph?project=Example_A')).json;
    return pass(ok(r) && s.layout === 'separate' && r.json.restored >= 1 && (g.commits || []).some((c) => /Fixture example-a/.test(c.subject)),
      `${r.status} ${r.text.slice(0, 150)} scope=${g.scope}`);
  });
  await check('the leftover .git was archived, not lost', async () => {
    const a = dexec(`ls ${DATA}/var/git-archive | grep -c '^Example_B-leftover-' || true`);
    return pass(+a >= 1, `archived leftovers=${a}`);
  });
  await check('config change auto-commits only config/ (Separate again)', async () => {
    const { files } = await configChange();
    return pass(files.length && files.every((f) => f.path.startsWith('config/')), files.map((f) => f.path).join(','));
  });
  await check('Sync — pull works again with the kept credential', async () => {
    const d = await api('POST', '/delivery', { project: 'Example_A', delivery: 'sync-pull', branch: 'main', intervalSeconds: 3600 });
    remoteCommit('dev', 'example-a', `printf "def hello():\\n\\treturn 'v4'\\n" > ${CODE} && git commit -qam v4`);
    const s = await api('POST', '/sync-now', { project: 'Example_A' });
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'off' });
    return pass(ok(d) && fileIn(`projects/Example_A/${CODE}`).includes("'v4'"), `delivery ${d.status} ${d.text.slice(0, 120)} sync ${s.text.slice(0, 200)}`);
  });
  await check('push again after the switch back', async () => {
    const p = await api('POST', '/push', {});
    return pass(ok(p) && p.json.to === 'main', `${p.status} ${p.text.slice(0, 200)}`);
  });
}

// Separate, 4.4.0: add a project from its repository; Fetch, Pull and Join for the gateway repository.
async function sep3() {
  phase = 'sep3';
  await check('read a repository: branches and a project name from its title', async () => {
    const r = await api('POST', '/project-add-peek', { url: `${SRV.dev}/example-b.git`, httpsCredentialId: credId.writer });
    return pass(ok(r) && (r.json.branches || []).includes('main') && r.json.defaultBranch === 'main' && r.json.suggestedName === 'Example_B',
      `${r.status} ${r.text.slice(0, 200)}`);
  });
  await check('add a project from its repository: it appears, loads and shows its branch', async () => {
    const r = await api('POST', '/project-add', { name: 'Example_D', url: `${SRV.dev}/example-b.git`, httpsCredentialId: credId.writer });
    const p = await until(async () => { const x = await project('Example_D'); return x && x.versioned ? x : null; }, 30000);
    const loaded = await until(async () => {
      const l = await plat('GET', '/projects/list');
      return /Example_D/.test(l.text) ? l : null;
    }, 60000, 3000);
    return pass(ok(r) && p && p.branch === 'main' && p.remoteUrl && loaded, `${r.status} ${r.text.slice(0, 160)} branch=${p && p.branch} loaded=${!!loaded}`);
  });
  await check('adding the same name again is refused', async () => {
    const r = await api('POST', '/project-add', { name: 'Example_D', url: `${SRV.dev}/example-b.git`, httpsCredentialId: credId.writer });
    return pass(refused(r) && /already a project/.test(r.text), `${r.status} ${r.text.slice(0, 160)}`);
  });
  await check('a branch with no project.json at its root is refused and leaves nothing behind', async () => {
    const r = await api('POST', '/project-add', { name: 'Example_E', url: `${SRV.dev}/gateway.git`, httpsCredentialId: credId.writer });
    const left = dexec(`test -e ${DATA}/projects/Example_E && echo yes || echo no; ls ${DATA}/var/git-module-add 2>/dev/null | wc -l`);
    return pass(refused(r) && /project.json/.test(r.text) && left.startsWith('no') && left.endsWith('0'), `${r.status} ${r.text.slice(0, 160)} left=${left.replace('\n', ' ')}`);
  });
  await check('Add from repository needs a pulling delivery in the policy', async () => {
    await api('POST', '/policy', { ...OPEN_POLICY, deliveryModes: ['off', 'runner'] });
    const r = await api('POST', '/project-add', { name: 'Example_G', url: `${SRV.dev}/example-b.git`, httpsCredentialId: credId.writer });
    await api('POST', '/policy', OPEN_POLICY);
    return pass(r.status === 403 && /Adding a project from its repository is off/.test(r.text), `${r.status} ${r.text.slice(0, 160)}`);
  });
  await check('a file: URL is refused', async () => {
    const r = await api('POST', '/project-add', { name: 'Example_F', url: 'file:///tmp/x.git', httpsCredentialId: credId.writer });
    return pass(r.status === 403, `${r.status} ${r.text.slice(0, 160)}`);
  });
  await plat('DELETE', '/projects/Example_D');
  await sleep(3000);

  await check('Fetch shows the remote ahead; Pull brings it in', async () => {
    await api('POST', '/push', {});
    remoteCommit('dev', 'gateway', 'echo pulled > config/regtest-pulled.md && git add -A && git commit -qm "pull me"');
    const f = await api('POST', '/config-fetch', {});
    const pl = await api('POST', '/config-pull', {});
    return pass(ok(f) && f.json.behind === 1 && !f.json.unrelated && ok(pl) && pl.json.changed && fileIn('config/regtest-pulled.md').includes('pulled'),
      `fetch ${f.text.slice(0, 120)}; pull ${pl.text.slice(0, 160)}`);
  });
  await check('Delete versioning keeps the remote; a new repository joins the remote history', async () => {
    const d = await api('POST', '/deinit', {});
    const kept = (await api('GET', '/remote')).json.configured;
    const i = await api('POST', '/init', {});
    const f = await api('POST', '/config-fetch', {});
    const refusedPush = await api('POST', '/push', {});
    const remoteTip = bareRev('dev', 'gateway', 'main');
    const j = await api('POST', '/config-join', {});
    const parent = cgit('.', 'rev-parse HEAD~1');
    const p = await api('POST', '/push', {});
    const again = await api('POST', '/config-join', {});
    return pass(ok(d) && kept && ok(i) && f.json.unrelated === true && refused(refusedPush) && ok(j) && parent === remoteTip
      && ok(p) && refused(again),
    `deinit ${d.status} kept=${kept} unrelated=${f.json.unrelated} push-before ${refusedPush.status} join ${j.status} ${j.text.slice(0, 120)} parent ok=${parent === remoteTip} push ${p.status} ${p.text.slice(0, 100)} again ${again.status}`);
  });
}

// ── audit ─────────────────────────────────────────────────────────────────────────────────────
// A local audit profile set as the gateway's own (Security > General Settings); the module's
// default setting follows it, so this also proves the default.
const AUDIT = 'GitAudit';
async function auditSetup() {
  const names = (await plat('GET', '/resources/names/ignition/audit-profile')).json;
  if (!JSON.stringify(names).includes(AUDIT)) {
    const r = await plat('POST', '/resources/ignition/audit-profile', [{ name: AUDIT, enabled: true,
      config: { profile: { type: 'local', retentionDays: 1 }, settings: {} } }]);
    if (!ok(r)) throw new Error('audit profile ' + r.status + ' ' + r.text.slice(0, 200));
  }
  const sec = (await plat('GET', '/resources/singleton/ignition/security-properties')).json;
  if (sec.config.gatewayAuditProfile !== AUDIT) {
    const r = await plat('PUT', '/resources/ignition/security-properties', [{ name: 'security-properties',
      collection: 'core', signature: sec.signature, config: { ...sec.config, gatewayAuditProfile: AUDIT } }]);
    if (!ok(r)) throw new Error('gateway audit profile ' + r.status + ' ' + r.text.slice(0, 200));
  }
}
// The module's rows since t0 (its actions all start "git "). Writes are queued, so wait a moment.
async function auditRows(t0, settle = 2500) {
  await sleep(settle);
  const r = await plat('GET', `/audit/log/${AUDIT}?startTime=${t0 - 1000}&actionFilter=git%25&limit=1000`);
  return (r.json.items || []).filter((i) => i.action.startsWith('git '));
}
// Exactly one row of `action` since t0, with `match` (a regexp) in its target + value.
async function oneRow(t0, action, fn, match) {
  const before = Date.now();
  const out = await fn();
  const rows = (await auditRows(Math.min(t0, before))).filter((i) => i.action === action);
  const text = rows.map((i) => `${i.actor} ${i.actionTarget} ${i.actionValue}`).join(' | ');
  return pass(rows.length === 1 && rows[0].actor && (!match || match.test(`${rows[0].actionTarget} ${rows[0].actionValue}`))
    && (out === undefined || out), `${rows.length} row(s): ${text.slice(0, 250)}`);
}

async function audit() {
  phase = 'audit';
  await check('gateway audit profile in use by default', async () => {
    await auditSetup();
    const a = (await api('GET', '/audit')).json;
    return pass(a.enabled && a.profile === '' && a.gatewayHasProfile && (a.profiles || []).includes(AUDIT), JSON.stringify(a));
  });
  await check('push writes one row (user, branch, commit)', async () => {
    await configChange();
    return oneRow(Date.now(), 'git push', async () => ok(await api('POST', '/push', {})), /branch=main; commit=[0-9a-f]{40}/);
  });
  await check('credential added and removed write one row each', async () => {
    const t0 = Date.now();
    const c = await api('POST', '/credentials', { type: 'HTTPS', host: 'audit.example.com', username: 'auditor', mode: 'inline', password: 'x' });
    await api('POST', '/credential-remove', { type: 'HTTPS', id: c.json.id });
    const rows = await auditRows(t0);
    const add = rows.filter((i) => i.action === 'git credential added');
    const rm = rows.filter((i) => i.action === 'git credential removed');
    return pass(add.length === 1 && rm.length === 1 && /auditor@audit.example.com/.test(add[0].actionValue) && add[0].actor === 'admin',
      rows.map((i) => `${i.action}: ${i.actionValue}`).join(' | '));
  });
  await check('policy change writes one row with the settings', async () =>
    oneRow(Date.now(), 'git policy change', async () => ok(await api('POST', '/policy', OPEN_POLICY)), /push on, .*GitHub-merged only off/));
  await check('branch switch writes one row', async () => {
    remoteCommit('dev', 'example-a', 'git checkout -qb audit-branch && git commit -q --allow-empty -m audit', 'git push -q origin audit-branch');
    cgit('projects/Example_A', `fetch -q ${bare('dev', 'example-a')} +refs/heads/*:refs/remotes/origin/*`);
    const r = await oneRow(Date.now(), 'git branch switch',
      async () => (await api('POST', '/branch-checkout', { project: 'Example_A', branch: 'origin/audit-branch' })).json.ok,
      /^Example_A branch=audit-branch; commit=[0-9a-f]{40}/);
    await api('POST', '/branch-checkout', { project: 'Example_A', branch: 'main' });
    return r;
  });
  await check('delivery installed (Sync — pull) writes one row', async () => {
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'sync-pull', branch: 'main', intervalSeconds: 3600 });
    remoteCommit('dev', 'example-a', `printf "def hello():\\n\\treturn 'audit'\\n" > ${CODE} && git commit -qam audit`);
    return oneRow(Date.now(), 'git delivery installed', async () => /pulled/.test((await api('POST', '/sync-now', { project: 'Example_A' })).json.result || ''),
      /^Example_A branch=main; commit=[0-9a-f]{40}; Sync — pull/);
  });
  await check('Pull writes one row when commits come in, none when up to date', async () => {
    await api('POST', '/push', {});
    remoteCommit('dev', 'gateway', 'echo audit > config/regtest-audit-pull.md && git add -A && git commit -qm "audit pull"');
    const t0 = Date.now();
    await api('POST', '/config-fetch', {});
    const a = await api('POST', '/config-pull', {});
    const b = await api('POST', '/config-pull', {});
    const rows = (await auditRows(t0)).filter((i) => i.action === 'git pull');
    return pass(a.json.changed && !b.json.changed && rows.length === 1 && /commit=[0-9a-f]{40}/.test(rows[0].actionValue),
      `pull1 ${a.text.slice(0, 80)} pull2 ${b.text.slice(0, 60)} rows=${rows.length}`);
  });
  await check('project added from repository writes one row', async () => {
    const r = await oneRow(Date.now(), 'git project added from repository', async () =>
      ok(await api('POST', '/project-add', { name: 'Example_D', url: `${SRV.dev}/example-b.git`, httpsCredentialId: credId.writer })),
    /^Example_D branch=main; commit=[0-9a-f]{40}/);
    await plat('DELETE', '/projects/Example_D');
    await sleep(3000);
    return r;
  });
  await check('a busy sync loop with nothing to install writes no rows', async () => {
    const t0 = Date.now();
    // Each sync-now fetches; with nothing new it installs nothing.
    for (let i = 0; i < 12; i++) await api('POST', '/sync-now', { project: 'Example_A' });
    await configChange(); // an auto-commit
    await configChange();
    const rows = await auditRows(t0);
    // Nor any of the platform's own rows for this module's records (none was saved).
    const ours = (await plat('GET', `/audit/log/${AUDIT}?startTime=${t0}&targetFilter=%25com.operametrix%25&limit=1000`)).json.items || [];
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'off' });
    return pass(rows.length === 0 && ours.length === 0, `git rows=${rows.map((i) => i.action).join(',')} module-resource rows=${ours.length}`);
  });
  await check('runner release writes one row', async () => {
    await api('POST', '/delivery', { project: 'Example_B', delivery: 'runner-release' });
    return oneRow(Date.now(), 'git delivery installed', async () => ok(await call('POST', `${GW}/data/git-config/runner-release?project=Example_B&version=9.9.9`,
      makeZip('Example_B', 'Example_B audit release'), { Authorization: 'Bearer ' + E.RUNNER_TOKEN, 'Content-Type': 'application/zip' })), /^Example_B .*Runner — release 9\.9\.9/);
  });
  await check('restore writes one row', async () => {
    const h = (await api('GET', '/history?skip=0&limit=3')).json.commits[1];
    return oneRow(Date.now(), 'git restore', async () => ok(await api('POST', '/restore', { hash: h.hash })), new RegExp(`commit=${h.hash}`));
  });
  await check('mode override created and removed write one row each', async () => {
    await plat('POST', '/mode', { name: 'Dev', title: 'Dev', description: 'regression suite' });
    const t0 = Date.now();
    const c = await api('POST', '/mode-override', { type: 'policy', mode: 'Dev' });
    const r = await api('POST', '/mode-reset', { type: 'policy', mode: 'Dev' });
    const rows = await auditRows(t0);
    const made = rows.filter((i) => i.action === 'git mode override created');
    const gone = rows.filter((i) => i.action === 'git mode override removed');
    return pass(ok(c) && ok(r) && made.length === 1 && gone.length === 1 && /policy in Dev/.test(made[0].actionValue),
      rows.map((i) => `${i.action}: ${i.actionValue}`).join(' | '));
  });
  await check('layout switch writes one row each way', async () => {
    for (const p of ['Example_A', 'Example_B']) { cgit(`projects/${p}`, 'add -A'); cgit(`projects/${p}`, 'commit -q -m "Designer commit" --allow-empty'); }
    const t0 = Date.now();
    const a = await api('POST', '/layout', { layout: 'combined', restoreHistory: false });
    const b = await api('POST', '/layout', { layout: 'separate', restoreHistory: true });
    const rows = (await auditRows(t0)).filter((i) => i.action === 'git layout switch');
    return pass(ok(a) && ok(b) && rows.length === 2 && rows.some((i) => /to combined/.test(i.actionValue))
      && rows.some((i) => /to separate/.test(i.actionValue)), `${a.status} ${b.status} ${rows.map((i) => i.actionValue).join(' | ')}`);
  });
  await check('audit set to Off writes the change, then nothing', async () => {
    const t0 = Date.now();
    const off = await api('POST', '/audit', { enabled: false, profile: '' });
    await api('POST', '/push', {});
    const rows = await auditRows(t0);
    await api('POST', '/audit', { enabled: true, profile: '' });
    return pass(ok(off) && rows.length === 1 && rows[0].action === 'git audit setting change', rows.map((i) => i.action).join(','));
  });
}

// Only deliver commits merged on GitHub: run.sh's throwaway key stands in for GitHub's web-flow
// key (-Dgitmodule.trustedSigningKeys), so "signed by it" is what GitHub's merge button would give.
async function merged() {
  phase = 'merged';
  // The suite's git server stands in for GitHub: its host is listed as a GitHub (Enterprise) host.
  const MERGED = { ...OPEN_POLICY, githubMergedOnly: true, githubHosts: [BR] };
  const warnings = async (t0) => (await events()).filter((e) => e.outcome === 'warning'
    && /Not merged through GitHub/.test(e.message) && Date.parse(e.timestamp) >= t0 - 5000);
  await check('policy option saved and shown, off by default', async () => {
    const before = (await api('GET', '/status')).json.policy.githubMergedOnly;
    const r = await api('POST', '/policy', MERGED);
    const after = (await api('GET', '/status')).json.policy.githubMergedOnly;
    return pass(before === false && ok(r) && after === true, `before=${before} save ${r.status} after=${after}`);
  });
  await check('Sync — pull refuses an unsigned tip, warns once, leaves the project as it was', async () => {
    const t0 = Date.now();
    const d = await api('POST', '/delivery', { project: 'Example_A', delivery: 'sync-pull', branch: 'main', intervalSeconds: 3600 });
    const was = fileIn(`projects/Example_A/${CODE}`);
    remoteCommit('dev', 'example-a', `printf "def hello():\\n\\treturn 'unsigned'\\n" > ${CODE} && git commit -qam unsigned`);
    const s1 = await api('POST', '/sync-now', { project: 'Example_A' });
    const s2 = await api('POST', '/sync-now', { project: 'Example_A' });
    const w = await warnings(t0);
    return pass(ok(d) && /Not merged through GitHub/.test(s1.json.result || '') && fileIn(`projects/Example_A/${CODE}`) === was
      && w.length === 1, `sync1 ${s1.text.slice(0, 160)}; sync2 ${(s2.json.result || '').slice(0, 60)}; warnings=${w.length}`);
  });
  await check('a remote that is not a listed GitHub host is refused', async () => {
    await api('POST', '/policy', { ...OPEN_POLICY, githubMergedOnly: true });
    remoteCommit('dev', 'example-a', `printf "def hello():\\n\\treturn 'not-github'\\n" > ${CODE} && git commit -qam ng`, undefined, true);
    const s1 = await api('POST', '/sync-now', { project: 'Example_A' });
    await api('POST', '/policy', MERGED);
    return pass(/isn't GitHub/.test(s1.json.result || '') && !fileIn(`projects/Example_A/${CODE}`).includes('not-github'), (s1.json.result || s1.text).slice(0, 200));
  });
  await check('Sync — pull installs a signed tip', async () => {
    remoteCommit('dev', 'example-a', `printf "def hello():\\n\\treturn 'signed'\\n" > ${CODE} && git commit -qam signed`, undefined, true);
    const s = await api('POST', '/sync-now', { project: 'Example_A' });
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'off' });
    return pass(ok(s) && fileIn(`projects/Example_A/${CODE}`).includes("'signed'"), `sync ${s.text.slice(0, 200)}`);
  });
  await check('runner repo update refuses an unsigned tip', async () => {
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'runner-repo', branch: 'main' });
    remoteCommit('dev', 'example-a', `printf "def hello():\\n\\treturn 'runner-unsigned'\\n" > ${CODE} && git commit -qam ru`);
    const r = await call('POST', `${GW}/data/git-config/runner-sync`, { project: 'Example_A' }, { Authorization: 'Bearer ' + E.RUNNER_TOKEN });
    await api('POST', '/delivery', { project: 'Example_A', delivery: 'off' });
    return pass(/Not merged through GitHub/.test(r.text) && !fileIn(`projects/Example_A/${CODE}`).includes('runner-unsigned'),
      `${r.status} ${r.text.slice(0, 200)}`);
  });
  await check('config Sync — replace refuses an unsigned tip, installs a signed one', async () => {
    // The branch starts from the gateway's own state, so a replace changes one file only.
    const p = await api('POST', '/push', {});
    remoteCommit('dev', 'gateway', 'git checkout -qb merged-test', 'git push -q origin merged-test');
    remoteCommit('dev', 'gateway', 'git checkout -q merged-test && echo unsigned > config/unsigned.md && git add -A && git commit -qm unsigned', 'git push -q origin merged-test');
    await api('POST', '/config-delivery', { mode: 'replace', branch: 'merged-test', intervalSeconds: 3600 });
    const s1 = await api('POST', '/config-sync-now', {});
    const refusedOk = /Not merged through GitHub/.test(s1.text) && !fileIn('config/unsigned.md');
    remoteCommit('dev', 'gateway', 'git checkout -q merged-test && echo signed > config/signed.md && git add -A && git commit -qm signed', 'git push -q origin merged-test', true);
    const s2 = await api('POST', '/config-sync-now', {});
    const got = fileIn('config/signed.md');
    await api('POST', '/config-delivery', { mode: 'off', branch: 'main', intervalSeconds: 3600 });
    // Back to main, for the phases after this one.
    const back = await api('POST', '/branch-checkout', { repo: 'config', branch: 'main' });
    return pass(ok(p) && back.json.ok && refusedOk && ok(s2) && got.includes('signed'), `unsigned: ${s1.text.slice(0, 160)}; signed: ${s2.text.slice(0, 160)}`);
  });
  await check('Add from repository refuses an unsigned head and leaves nothing', async () => {
    const r = await api('POST', '/project-add', { name: 'Example_G', url: `${SRV.dev}/example-b.git`, httpsCredentialId: credId.writer });
    const left = dexec(`test -e ${DATA}/projects/Example_G && echo yes || echo no`);
    return pass(refused(r) && /Not merged through GitHub/.test(r.text) && left === 'no', `${r.status} ${r.text.slice(0, 160)} left=${left}`);
  });
  await check('turning the option off logs the loosening', async () => {
    const t0 = Date.now();
    const r = await api('POST', '/policy', OPEN_POLICY);
    const w = await until(async () => (await events()).find((e) => e.type === 'policy' && e.outcome === 'warning'
      && /not merged on GitHub/.test(e.message) && Date.parse(e.timestamp) >= t0 - 5000), 20000);
    return pass(ok(r) && w && /^Policy now allows more/.test(w.message), `${r.status} ${w ? w.message : 'no warning'}`);
  });
}

async function modes() {
  phase = 'modes core';
  await check('modes Dev and Prod created and listed', async () => {
    for (const m of ['Dev', 'Prod']) await plat('POST', '/mode', { name: m, title: m, description: 'regression suite' });
    const s = (await api('GET', '/status')).json;
    return pass((s.modes || []).includes('Dev') && (s.modes || []).includes('Prod') && s.policy.from === 'core', `modes=${s.modes} from=${s.policy.from}`);
  });
  await check('Create Override for Prod from core (page route)', async () => {
    const r = await api('POST', '/mode-override', { type: 'policy', mode: 'Prod' });
    const d = (await api('GET', '/definition?type=policy&collection=Prod')).json;
    return pass(ok(r) && (d.defined || []).includes('Prod'), `${r.status} ${r.text.slice(0, 120)} defined=${d.defined}`);
  });
  await check('Save to Prod changes only Prod; Remove Override deletes it', async () => {
    const s = await api('POST', '/policy?saveTo=Prod', LOCKED_POLICY);
    const d = (await api('GET', '/definition?type=policy&collection=Prod')).json;
    const eff = (await api('GET', '/status')).json.policy;
    const rm = await api('POST', '/mode-reset', { type: 'policy', mode: 'Prod' });
    const d2 = (await api('GET', '/definition?type=policy&collection=Prod')).json;
    return pass(ok(s) && d.pushAllowed === false && eff.pushAllowed === true && ok(rm) && !(d2.defined || []).includes('Prod'),
      `save ${s.status}; prod push=${d.pushAllowed}; core push=${eff.pushAllowed}; reset ${rm.status}; defined=${d2.defined}`);
  });
  await check('per-mode remotes saved to Dev and Prod; policy override saved to Prod', async () => {
    const dv = await api('POST', '/remote?saveTo=Dev', { uri: `${SRV.dev}/gateway.git`, branch: 'main', mode: 'inline', username: 'writer', password: E.W_PW });
    const pr = await api('POST', '/remote?saveTo=Prod', { uri: `${SRV.prod}/gateway.git`, branch: 'main', mode: 'inline', username: 'reader', password: E.R_PW });
    const po = await api('POST', '/policy?saveTo=Prod', LOCKED_POLICY);
    rsh(`git -C ${D}/dev/gateway.git push -q ${D}/prod/gateway.git main`);
    const r = (await api('GET', '/remote')).json;
    return pass(ok(dv) && ok(pr) && ok(po) && r.from === 'core' && (r.defined || []).includes('Prod'), `dev ${dv.status} prod ${pr.status} ${pr.text.slice(0, 100)} policy ${po.status} from=${r.from} defined=${r.defined}`);
  });

  phase = 'modes Dev';
  await restart('Dev');
  await check('Dev: mode shown, remote from Dev, policy from core', async () => {
    const s = (await api('GET', '/status')).json;
    const r = (await api('GET', '/remote')).json;
    return pass(s.policy.mode === 'Dev' && s.policy.from === 'core' && r.from === 'Dev' && s.policy.pushAllowed, `mode=${s.policy.mode} policyFrom=${s.policy.from} remoteFrom=${r.from}`);
  });
  await check('Dev: push uses the Dev credential on the dev server only', async () => {
    await configChange();
    const n = logLines().length;
    const p = await api('POST', '/push', {});
    const lines = logLines().slice(n);
    return pass(ok(p) && lines.some((l) => /-dev writer POST .*receive-pack 200/.test(l)) && !lines.some((l) => l.includes('-prod ')), `${p.status} ${p.text.slice(0, 120)} ${lines.map((l) => l.split(' ').slice(0, 3).join(' ')).join('|')}`);
  });
  await check('Dev: commits carry the Gateway-Mode: Dev trailer', async () => {
    await configChange();
    const msg = cgit('.', 'log -1 --format=%B');
    return pass(/Gateway-Mode: Dev/.test(msg), msg.slice(-120));
  });

  phase = 'modes Prod';
  await restart('Prod');
  await check('Prod: locked policy from Prod', async () => {
    const p = (await api('GET', '/status')).json.policy;
    return pass(p.mode === 'Prod' && p.from === 'Prod' && !p.pushAllowed && !p.branchSwitch && Object.keys(p.refused).includes('PUSH'), `mode=${p.mode} from=${p.from} push=${p.pushAllowed}`);
  });
  await check('Prod: push, branch switch, layout switch, blocked delivery all 403', async () => {
    const r = [await api('POST', '/push', {}), await api('POST', '/branch-checkout', { project: 'Example_A', branch: 'main' }),
      await api('POST', '/layout', { layout: 'combined' }), await api('POST', '/delivery', { project: 'Example_A', delivery: 'sync-pull', branch: 'main' }),
      await api('POST', '/config-delivery', { mode: 'pull', branch: 'main' })];
    return pass(r.every((x) => x.status === 403), r.map((x) => x.status).join(','));
  });
  await check('Prod: update from remote uses the Prod reader on the prod server only', async () => {
    const n = logLines().length;
    const u = await api('POST', '/update-from-remote', {});
    const lines = logLines().slice(n);
    // Local config commits made since the seed are not on prod, so the update itself may refuse;
    // what matters here is which credential reached which server.
    return pass((ok(u) || /not on the remote/.test(u.text)) && lines.some((l) => /-prod reader .* 200$/.test(l)) && !lines.some((l) => /-dev /.test(l)), `${u.status} ${u.text.slice(0, 150)} ${lines.map((l) => l.split(' ').slice(0, 2).join(' ')).join('|')}`);
  });
  await check('Prod: commits carry the Gateway-Mode: Prod trailer', async () => {
    await configChange();
    return pass(/Gateway-Mode: Prod/.test(cgit('.', 'log -1 --format=%B')), '');
  });
  await check('Prod: removing the override loosens the policy and logs a warning', async () => {
    const t0 = Date.now();
    const r = await api('POST', '/mode-reset', { type: 'policy', mode: 'Prod' });
    const w = await until(async () => (await events()).find((e) => e.type === 'policy' && e.outcome === 'warning' && Date.parse(e.timestamp) >= t0 - 5000), 20000);
    const p = (await api('GET', '/status')).json.policy;
    return pass(ok(r) && w && p.pushAllowed && p.from === 'core', `${r.status} warn=${w ? w.message.slice(0, 140) : 'none'} from=${p.from}`);
  });
}

(async () => {
  await login();
  const want = (E.ONLY || 'sep1,comb,sep2,sep3,audit,merged,modes').split(',');
  if (!E.RUNNER_TOKEN && !want.includes('sep1')) {
    const t = await api('POST', '/runner', { enabled: true, generateToken: true });
    E.RUNNER_TOKEN = t.json.token;
  }
  for (const [n, f] of [['sep1', sep1], ['comb', comb], ['sep2', sep2], ['sep3', sep3], ['audit', audit], ['merged', merged], ['modes', modes]]) {
    if (want.includes(n)) {
      try { await f(); } catch (e) { rows.push({ phase: n, name: 'phase aborted', res: 'FAIL', detail: e.message.slice(0, 300) }); console.log('FAIL phase ' + n + ': ' + e.message); }
    }
  }
  await browser.close();
  const w = Math.max(...rows.map((r) => (r.phase + '  ' + r.name).length));
  console.log('\n' + 'RESULT  ' + 'CHECK'.padEnd(w) + '\n' + '-'.repeat(w + 8));
  for (const r of rows) console.log(`${r.res.padEnd(6)}  ${(r.phase + '  ' + r.name).padEnd(w)}${r.res === 'FAIL' ? '\n        ' + r.detail : ''}`);
  const fails = rows.filter((r) => r.res === 'FAIL').length;
  console.log(`\n${rows.length - fails} passed, ${fails} failed`);
  fs.writeFileSync(path.join(E.STATE_DIR, 'results.json'), JSON.stringify(rows, null, 1));
  process.exit(fails ? 1 : 0);
})().catch((e) => { console.error('suite: ' + e.message); process.exit(1); });

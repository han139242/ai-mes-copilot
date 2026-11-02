import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { sameProcess, mayRestart, instanceFingerprint, acquireControl, observedState } from './local-runtime.mjs';

test('CLI executes through a directory junction instead of silently exiting', () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'mes-cli-junction-'));
  const alias = path.join(temporary, 'scripts');
  fs.symlinkSync(path.dirname(fileURLToPath(import.meta.url)), alias, 'junction');
  try {
    const result = spawnSync(process.execPath, [path.join(alias, 'local-runtime.mjs'), 'invalid-action'], {
      encoding: 'utf8', timeout: 10000,
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Usage:/);
  } finally {
    fs.rmdirSync(alias);
    fs.rmdirSync(temporary);
  }
});

test('a recycled PID cannot authorize shutdown', () => {
  const saved = { pid: 100, started: '123', hash: 'project-a' };
  assert.equal(sameProcess(saved, { ...saved }), true);
  assert.equal(sameProcess(saved, { ...saved, started: '456' }), false);
  assert.equal(sameProcess(saved, { ...saved, hash: 'project-b' }), false);
  assert.equal(sameProcess(saved, null), false);
  assert.equal(sameProcess(null, null), false);
});

test('recovery is bounded and explicit shutdown never restarts the app', () => {
  assert.equal(mayRestart(0, false), true);
  assert.equal(mayRestart(1, false), true);
  assert.equal(mayRestart(2, false), false);
  assert.equal(mayRestart(0, true), false);
  assert.equal(mayRestart(0, false, 0), false);
});

test('reusing a different database or proxy configuration is rejected by fingerprint', () => {
  const config = {
    backend: { port: 8089 },
    frontend: { port: 5173 },
    database: { port: 3311, name: 'business', dataDirectory: process.cwd() },
  };
  const initial = instanceFingerprint('backend', config);
  assert.notEqual(initial, instanceFingerprint('backend', { ...config, database: { ...config.database, port: 3307 } }));
  assert.notEqual(
    initial,
    instanceFingerprint('backend', { ...config, database: { ...config.database, name: 'test' } }),
  );
  assert.notEqual(
    instanceFingerprint('frontend', config),
    instanceFingerprint('frontend', { ...config, backend: { port: 9999 } }),
  );
});

test('Windows mutex excludes concurrent owners and releases for the next operation', async () => {
  const suffix = `regression-${process.pid}`;
  const release = await acquireControl(suffix);
  try {
    await assert.rejects(acquireControl(suffix), /active|busy/);
  } finally {
    await release();
  }
  const releaseAgain = await acquireControl(suffix);
  await releaseAgain();
});

test('a controller exiting without cleanup does not leave a permanent lock', async () => {
  const suffix = `abandoned-${process.pid}`;
  const moduleUrl = new URL('./local-runtime.mjs', import.meta.url).href;
  const code = `import {acquireControl} from ${JSON.stringify(moduleUrl)}; await acquireControl(${JSON.stringify(suffix)}); process.exit(0);`;
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', code], { timeout: 10000 });
  assert.equal(result.status, 0);
  const release = await acquireControl(suffix);
  await release();
});

test('a dead process can never be reported as ready', () => {
  const seen = { listening: false, healthy: false, supervised: false, registered: false, foreign: false };
  // The reported symptom: state file says ready while nothing listens on the port.
  assert.equal(observedState('ready', seen), 'crashed');
  assert.equal(observedState('starting', seen), 'crashed');
  assert.equal(observedState('recovering', seen), 'crashed');
  assert.equal(observedState('stopped', seen), 'stopped');
  assert.equal(observedState('failed', seen), 'failed');
  assert.equal(observedState('unmanaged', seen), 'stopped');
});

test('a live supervisor without a listener is starting, not ready', () => {
  const seen = { listening: false, healthy: false, supervised: true, registered: false, foreign: false };
  assert.equal(observedState('ready', seen), 'starting');
  assert.equal(observedState('recovering', seen), 'recovering');
  assert.equal(observedState('starting', seen), 'starting');
});

test('a listening port only counts as ready when supervised, registered and healthy', () => {
  const base = { listening: true, healthy: true, supervised: true, registered: true, foreign: false };
  assert.equal(observedState('ready', base), 'ready');
  assert.equal(observedState('starting', { ...base, supervised: false }), 'unmanaged');
  assert.equal(observedState('ready', { ...base, registered: false }), 'unmanaged');
  assert.equal(observedState('ready', { ...base, healthy: false }), 'unresponsive');
  assert.equal(observedState('ready', { ...base, foreign: true }), 'port-conflict');
});

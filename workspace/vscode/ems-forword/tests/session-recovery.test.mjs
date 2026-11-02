import test from 'node:test';
import assert from 'node:assert/strict';
import { restoreSessionUser, recoveryTarget } from '../src/utils/session-recovery.js';

for (const [name, error] of [
  ['gateway failure', { response: { status: 502 } }],
  ['server failure', { response: { status: 500 } }],
  ['connection refused', { code: 'ERR_NETWORK' }],
  ['timeout', { code: 'ECONNABORTED' }],
  ['forbidden', { response: { status: 403 } }],
]) {
  test(`${name} preserves the session and blocks the business route`, async () => {
    let cleared = false;
    const result = await restoreSessionUser({
      loadUser: async () => {
        throw error;
      },
      saveUser: () => assert.fail('failed request cannot update permissions'),
      clearSession: () => {
        cleared = true;
      },
      redirect: '/ai/workbench',
    });
    assert.equal(cleared, false);
    assert.deepEqual(result, { path: '/service-unavailable', query: { redirect: '/ai/workbench' } });
  });
}

test('explicit authentication failures clear the session', async () => {
  for (const error of [{ response: { status: 401 } }, { code: 1002 }, { code: '401' }]) {
    let cleared = 0;
    const result = await restoreSessionUser({
      loadUser: async () => {
        throw error;
      },
      saveUser: () => {},
      clearSession: () => {
        cleared++;
      },
      redirect: '/dashboard',
    });
    assert.equal(cleared, 1);
    assert.equal(result.path, '/login');
  }
});

test('reconnection restores actual server permissions', async () => {
  const user = { roles: ['WAREHOUSE'], permissions: ['wms:inventory:view'] };
  let saved;
  const result = await restoreSessionUser({
    loadUser: async () => ({ data: user }),
    saveUser: (value) => {
      saved = value;
    },
    clearSession: () => assert.fail('valid session must survive'),
    redirect: '/dashboard',
  });
  assert.equal(result, null);
  assert.deepEqual(saved, user);
});

test('reconnection rejects external redirects and recovery loops', () => {
  for (const value of [
    '//example.com',
    'https://example.com',
    '/login',
    '/service-unavailable?redirect=x',
    undefined,
  ]) {
    assert.equal(recoveryTarget(value), '/dashboard');
  }
  assert.equal(recoveryTarget('/ai/workbench?tab=history'), '/ai/workbench?tab=history');
});

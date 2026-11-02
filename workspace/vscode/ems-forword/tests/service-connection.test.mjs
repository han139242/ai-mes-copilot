import test from 'node:test';
import assert from 'node:assert/strict';
import { backendConnection, backendConnectionLabel, recordBackendConnection } from '../src/utils/service-connection.js';

test('sidebar reports observed connection and recovery instead of a fixed running label', () => {
  assert.equal(backendConnection.value, 'unknown');
  assert.equal(backendConnectionLabel.value, '正在连接后端');
  recordBackendConnection(true);
  assert.equal(backendConnectionLabel.value, '后端已连接');
  recordBackendConnection(false);
  assert.equal(backendConnectionLabel.value, '后端连接中断');
  recordBackendConnection(true);
  assert.equal(backendConnectionLabel.value, '后端已连接');
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { aiStatusPresentation, failedAiStatus, shouldRecheckAiStatus } from '../src/utils/ai-status.js';

test('a network outage cannot claim AI was disabled or leave stale query availability', () => {
  const status = failedAiStatus({ state: 'READY', available: true, queryAvailable: true, model: 'deepseek-chat' }, {});
  assert.equal(status.state, 'UNREACHABLE');
  assert.equal(status.available, false);
  assert.equal(status.queryAvailable, false);
  assert.equal(status.model, 'deepseek-chat');
  assert.equal(aiStatusPresentation(status).label, '服务连接中断');
  assert.equal(shouldRecheckAiStatus(status), true);
});

test('permission and session failures are not retryable outages', () => {
  for (const [code, state] of [[401, 'UNAUTHORIZED'], [1002, 'UNAUTHORIZED'], [403, 'FORBIDDEN']]) {
    const status = failedAiStatus({}, { code });
    assert.equal(status.state, state);
    assert.equal(shouldRecheckAiStatus(status), false);
  }
  assert.equal(failedAiStatus({}, { response: { status: 403 } }).state, 'FORBIDDEN');
});

test('only explicit server configuration is displayed as disabled; query fallback remains usable', () => {
  assert.equal(aiStatusPresentation({ state: 'DISABLED' }).label, '服务未启用');
  assert.equal(aiStatusPresentation({ state: 'DISABLED', queryAvailable: true }).label, '业务查询可用');
  assert.equal(aiStatusPresentation({ state: 'READY', available: true }).tone, 'ready');
  assert.equal(aiStatusPresentation({ state: 'PREVIEW' }).label, '框架预览');
  assert.equal(aiStatusPresentation({ state: 'CHECKING' }).label, '正在检查服务');
});

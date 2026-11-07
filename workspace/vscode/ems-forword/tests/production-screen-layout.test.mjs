import test from 'node:test';
import assert from 'node:assert/strict';
import { productionScreenLayout } from '../src/utils/production-screen-layout.js';

test('full HD monitoring uses the full canvas without a mobile toolbar', () => {
  assert.deepEqual(productionScreenLayout(1920, 1080), { scale: 1, offsetX: 0, offsetY: 0, toolbarHeight: 0 });
});

test('ultrawide monitoring centers the canvas instead of stretching the data', () => {
  const layout = productionScreenLayout(2560, 1080);
  assert.equal(layout.scale, 1);
  assert.equal(layout.offsetX, 320);
  assert.equal(layout.offsetY, 0);
});

test('phone and landscape previews retain an unscaled accessible return toolbar', () => {
  for (const [width, height] of [
    [390, 844],
    [844, 390],
    [960, 600],
  ]) {
    const layout = productionScreenLayout(width, height);
    assert.equal(layout.toolbarHeight, 56);
    assert.ok(layout.offsetY >= 56);
    assert.ok(layout.offsetX + 1920 * layout.scale <= width + 0.001);
    assert.ok(layout.offsetY + 1080 * layout.scale <= height + 0.001);
  }
});

test('transient zero-size viewports never produce an invalid canvas transform', () => {
  for (const [width, height] of [
    [0, 0],
    [NaN, undefined],
    [1, 1],
  ]) {
    const layout = productionScreenLayout(width, height);
    assert.ok(Number.isFinite(layout.scale) && layout.scale > 0);
    assert.ok(Number.isFinite(layout.offsetX) && Number.isFinite(layout.offsetY));
  }
});

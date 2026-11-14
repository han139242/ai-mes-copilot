import assert from 'node:assert/strict';
import test from 'node:test';
import { encodeRecording } from '../src/utils/assistant-audio.js';

test('microphone samples produce a mono PCM16 WAV accepted by ASR', () => {
  const samples = new Float32Array(3200);
  samples.set([-1.5, 1.5, 0]);
  const result = encodeRecording([samples], 16000);
  const view = new DataView(result);
  const bytes = Buffer.from(result);
  assert.equal(bytes.toString('ascii', 0, 4), 'RIFF');
  assert.equal(bytes.toString('ascii', 8, 16), 'WAVEfmt ');
  assert.equal(view.getUint32(4, true), bytes.length - 8);
  assert.equal(view.getUint16(20, true), 1);
  assert.equal(view.getUint16(22, true), 1);
  assert.equal(view.getUint32(24, true), 16000);
  assert.equal(view.getUint16(34, true), 16);
  assert.equal(view.getUint32(40, true), samples.length * 2);
  assert.equal(view.getInt16(44, true), -32768);
  assert.equal(view.getInt16(46, true), 32767);
});

test('recording length and unsupported or empty inputs are bounded', () => {
  const result = encodeRecording([new Float32Array(31 * 8000)], 8000);
  assert.equal(result.byteLength, 44 + 30 * 8000 * 2);
  assert.throws(() => encodeRecording([], 16000), /录音太短/);
  assert.throws(() => encodeRecording([], 96000), /采样率/);
});

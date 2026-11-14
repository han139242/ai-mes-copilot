const SAMPLE_RATES = [8000, 16000, 22050, 24000, 32000, 44100, 48000];

export const supportsRecordingRate = (rate) => SAMPLE_RATES.includes(rate);

export function encodeRecording(chunks, rate) {
  if (!supportsRecordingRate(rate)) throw new Error('当前设备的录音采样率暂不支持');
  const samples = Math.min(
    rate * 30,
    chunks.reduce((size, chunk) => size + chunk.length, 0),
  );
  if (samples < rate / 5) throw new Error('录音太短，请重新录制');
  const buffer = new ArrayBuffer(44 + samples * 2);
  const wav = new DataView(buffer);
  const ascii = (start, text) => {
    for (let index = 0; index < text.length; index++) wav.setUint8(start + index, text.charCodeAt(index));
  };
  ascii(0, 'RIFF');
  wav.setUint32(4, buffer.byteLength - 8, true);
  ascii(8, 'WAVEfmt ');
  wav.setUint32(16, 16, true);
  wav.setUint16(20, 1, true);
  wav.setUint16(22, 1, true);
  wav.setUint32(24, rate, true);
  wav.setUint32(28, rate * 2, true);
  wav.setUint16(32, 2, true);
  wav.setUint16(34, 16, true);
  ascii(36, 'data');
  wav.setUint32(40, samples * 2, true);
  let index = 0;
  for (const chunk of chunks) {
    for (const sample of chunk) {
      if (index >= samples) return buffer;
      const value = Math.max(-1, Math.min(1, sample));
      wav.setInt16(44 + index * 2, value * (value < 0 ? 32768 : 32767), true);
      index++;
    }
  }
  return buffer;
}

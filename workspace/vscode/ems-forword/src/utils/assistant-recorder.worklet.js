class AssistantRecorder extends AudioWorkletProcessor {
  remaining = Math.floor(sampleRate * 30);

  process(inputs) {
    const channel = inputs[0]?.[0];
    if (channel?.length && this.remaining > 0) {
      const samples = channel.slice(0, this.remaining);
      this.remaining -= samples.length;
      this.port.postMessage(samples, [samples.buffer]);
    }
    return this.remaining > 0;
  }
}

registerProcessor('mes-assistant-recorder', AssistantRecorder);

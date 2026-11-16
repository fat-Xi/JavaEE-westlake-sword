class VoiceRecorder extends AudioWorkletProcessor {
  constructor() {
    super();
    this.frame = new Int16Array(1600);
    this.used = 0;
    this.samples = 0;
    this.phase = 0;
    this.sum = 0;
    this.count = 0;
    this.recording = true;
    this.port.onmessage = ({ data }) => {
      if (data === "finish") this.finish();
    };
  }

  flush() {
    if (!this.used) return;
    const pcm = this.frame.slice(0, this.used).buffer;
    this.port.postMessage(pcm, [pcm]);
    this.used = 0;
  }

  finish() {
    if (!this.recording) return;
    this.recording = false;
    this.flush();
    this.port.postMessage("finished");
  }

  process(inputs) {
    if (!this.recording) return false;
    const input = inputs[0][0];
    if (!input) return true;
    // Convert the device's sample rate to mono 16 kHz PCM in 100 ms frames.
    for (const sample of input) {
      this.sum += sample;
      this.count++;
      this.phase += 16000;
      if (this.phase < sampleRate) continue;
      const average = Math.max(-1, Math.min(1, this.sum / this.count));
      this.frame[this.used++] = average < 0 ? average * 32768 : average * 32767;
      this.phase -= sampleRate;
      this.sum = 0;
      this.count = 0;
      if (this.used === this.frame.length) this.flush();
      if (++this.samples === 30 * 16000) {
        this.finish();
        return false;
      }
    }
    return true;
  }
}

registerProcessor("voice-recorder", VoiceRecorder);

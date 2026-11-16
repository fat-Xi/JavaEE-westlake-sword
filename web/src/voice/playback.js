import { api } from "../api";

const sampleRate = 16000;

function wave(chunks, bytes) {
  const header = new DataView(new ArrayBuffer(44));
  header.setUint32(0, 0x52494646); // RIFF
  header.setUint32(4, bytes + 36, true);
  header.setUint32(8, 0x57415645); // WAVE
  header.setUint32(12, 0x666d7420); // fmt
  header.setUint32(16, 16, true);
  header.setUint16(20, 1, true);
  header.setUint16(22, 1, true);
  header.setUint32(24, sampleRate, true);
  header.setUint32(28, sampleRate * 2, true);
  header.setUint16(32, 2, true);
  header.setUint16(34, 16, true);
  header.setUint32(36, 0x64617461); // data
  header.setUint32(40, bytes, true);
  return new Blob([header.buffer, ...chunks], { type: "audio/wav" });
}

export async function readSpeech(replyId, signal, onAudio) {
  const stream = await api(
    "/ai/voice/synthesize/stream",
    "POST",
    { replyId },
    { signal, responseType: "stream" },
  );
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  const chunks = [];
  let bytes = 0;
  let pending = "";
  let remainder;
  let completed = false;
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      pending += decoder.decode(part.value, { stream: true });
      let end;
      while ((end = pending.indexOf("\n")) !== -1) {
        const frame = JSON.parse(pending.slice(0, end));
        pending = pending.slice(end + 1);
        if (frame.error) throw new Error(frame.error);
        if (frame.done) completed = true;
        if (!frame.audio) continue;
        const decoded = atob(frame.audio);
        const offset = remainder === undefined ? 0 : 1;
        const pcm = new Uint8Array(decoded.length + offset);
        if (offset) pcm[0] = remainder;
        for (let i = 0; i < decoded.length; i++)
          pcm[i + offset] = decoded.charCodeAt(i);
        const aligned = pcm.length - (pcm.length % 2);
        remainder = aligned < pcm.length ? pcm[aligned] : undefined;
        if (aligned) {
          const samples = pcm.subarray(0, aligned);
          bytes += samples.length;
          chunks.push(samples);
          onAudio(samples);
        }
      }
    }
    if (!completed) throw new Error("语音合成连接提前结束，请重试");
    if (!bytes || remainder !== undefined)
      throw new Error("语音音频不完整，请重试");
    return wave(chunks, bytes);
  } finally {
    if (!completed) await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

export function createSpeechPlayer() {
  const context = new AudioContext();
  let nextStart = 0;
  let lastSource;
  let onFinish;
  void context.resume().catch(() => {});
  return {
    append(pcm) {
      const buffer = context.createBuffer(1, pcm.length / 2, sampleRate);
      const data = new DataView(pcm.buffer, pcm.byteOffset, pcm.length);
      const channel = buffer.getChannelData(0);
      for (let i = 0; i < channel.length; i++)
        channel[i] = data.getInt16(i * 2, true) / 32768;
      const source = context.createBufferSource();
      source.buffer = buffer;
      source.connect(context.destination);
      const start = Math.max(nextStart, context.currentTime + 0.025);
      source.start(start);
      nextStart = start + buffer.duration;
      lastSource = source;
      source.onended = () => {
        source.disconnect();
        if (source === lastSource) {
          lastSource = null;
          onFinish?.();
        }
      };
    },
    pause() {
      return context.suspend();
    },
    resume() {
      return context.resume();
    },
    finish() {
      return lastSource
        ? new Promise((resolve) => {
            onFinish = resolve;
          })
        : Promise.resolve();
    },
    close() {
      onFinish?.();
      void context.close().catch(() => {});
    },
  };
}

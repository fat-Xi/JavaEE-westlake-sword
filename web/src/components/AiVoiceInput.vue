<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import recorderUrl from "../voice/recorder-worklet.js?url";

defineProps({ disabled: Boolean });
const emit = defineEmits(["feedback", "start", "transcript"]);
const maxRecordingSeconds = 30;
const maxSocketBuffer = 256000;
const status = ref("idle"),
  ready = ref(false),
  error = ref(""),
  elapsedSeconds = ref(0);
const buttonLabel = computed(() =>
  status.value === "recording" ? "停止并转写" : "语音输入",
);
const feedback = computed(() => {
  if (error.value) return { message: error.value, error: true };
  switch (status.value) {
    case "permission":
      return { message: "等待麦克风授权…" };
    case "preparing":
      return { message: "正在启动录音…" };
    case "recording":
      return {
        message: `正在录制… ${elapsedSeconds.value} 秒`,
      };
    case "transcribing":
      return { message: "正在识别语音…" };
    default:
      return null;
  }
});
const frames = [];
let socket, stream, audioContext, recorder, timer, deadline, sendTimer;
let finishPending = false;
let attempt = 0;

watch(feedback, (value) => emit("feedback", value), { flush: "sync" });

function releaseMicrophone() {
  stream?.getTracks().forEach((track) => track.stop());
  stream = null;
  if (recorder) {
    recorder.port.onmessage = null;
    recorder.port.close();
    recorder.disconnect();
    recorder = null;
  }
  if (audioContext) {
    void audioContext.close().catch(() => {});
    audioContext = null;
  }
}

function cancel() {
  attempt++;
  clearInterval(timer);
  clearTimeout(deadline);
  clearTimeout(sendTimer);
  frames.length = 0;
  finishPending = false;
  if (socket) {
    socket.onmessage = null;
    socket.onerror = null;
    socket.onclose = null;
    socket.close();
    socket = null;
  }
  releaseMicrophone();
  status.value = "idle";
  ready.value = false;
}

function sendAudio() {
  clearTimeout(sendTimer);
  if (!ready.value || socket?.readyState !== WebSocket.OPEN) return;
  while (frames.length) {
    if (socket.bufferedAmount + frames[0].byteLength > maxSocketBuffer) {
      sendTimer = setTimeout(sendAudio, 40);
      return;
    }
    socket.send(frames.shift());
  }
  if (finishPending) {
    finishPending = false;
    socket.send("finish");
  }
}

function resultDeadline() {
  clearTimeout(deadline);
  deadline = setTimeout(() => fail("识别结果返回超时，请重试"), 16000);
}

function connect(current) {
  const url = new URL("/api/ai/voice/live", location.href);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  socket = new WebSocket(url);
  deadline = setTimeout(
    () =>
      fail(ready.value ? "启动录音超时，请重试" : "实时识别连接超时，请重试"),
    22000,
  );
  socket.onmessage = ({ data }) => {
    if (current !== attempt) return;
    const message = JSON.parse(data);
    if (message.error) fail(message.error);
    else if (message.ready) {
      ready.value = true;
      if (finishPending) resultDeadline();
      else if (status.value !== "preparing") clearTimeout(deadline);
      sendAudio();
    } else if (typeof message.text === "string") {
      emit("transcript", message.text);
      cancel();
    }
  };
  socket.onerror = socket.onclose = () => {
    if (current === attempt) fail("实时语音连接中断，请重试或输入文字");
  };
}

async function start() {
  const current = ++attempt;
  error.value = "";
  if (
    !navigator.mediaDevices?.getUserMedia ||
    !window.AudioContext ||
    !window.AudioWorkletNode
  ) {
    error.value =
      "当前页面无法录音，请使用支持麦克风的浏览器并通过 HTTPS 或本机地址打开。";
    return;
  }
  const permissionsPolicy =
    document.permissionsPolicy ?? document.featurePolicy;
  if (permissionsPolicy?.allowsFeature("microphone") === false) {
    error.value =
      "当前页面无法申请麦克风权限，请用 Edge 或 Chrome 直接打开平台。";
    return;
  }
  emit("start");
  status.value = "permission";
  try {
    const microphone = await navigator.mediaDevices.getUserMedia({
      audio: true,
    });
    if (current !== attempt) {
      microphone.getTracks().forEach((track) => track.stop());
      return;
    }
    stream = microphone;
    status.value = "preparing";
    connect(current);
    const context = new AudioContext();
    audioContext = context;
    await context.audioWorklet.addModule(recorderUrl);
    await context.resume();
    if (current !== attempt) return;
    recorder = new AudioWorkletNode(context, "voice-recorder", {
      channelCount: 1,
      channelCountMode: "explicit",
    });
    recorder.port.onmessage = ({ data }) => {
      if (current !== attempt) return;
      if (data === "finished") {
        status.value = "transcribing";
        clearInterval(timer);
        releaseMicrophone();
        finishPending = true;
        if (ready.value) resultDeadline();
      } else {
        frames.push(data);
      }
      sendAudio();
    };
    context.createMediaStreamSource(microphone).connect(recorder);
    recorder.connect(context.destination);
    const startedAt = performance.now();
    elapsedSeconds.value = 0;
    status.value = "recording";
    if (ready.value) clearTimeout(deadline);
    timer = setInterval(() => {
      elapsedSeconds.value = Math.min(
        maxRecordingSeconds,
        Math.floor((performance.now() - startedAt) / 1000),
      );
      if (elapsedSeconds.value === maxRecordingSeconds) stop();
    }, 200);
  } catch (e) {
    if (current !== attempt) return;
    const microphoneOpened = !!stream;
    cancel();
    if (microphoneOpened) {
      error.value =
        "麦克风已打开，但当前浏览器无法开始录音，请用 Edge 或 Chrome 重试。";
    } else if (
      e.name === "NotFoundError" ||
      e.name === "OverconstrainedError"
    ) {
      error.value = "未检测到可用的麦克风，请连接或启用输入设备后重试。";
    } else if (e.name === "NotAllowedError" || e.name === "SecurityError") {
      error.value = "麦克风访问被拒绝，请检查网站权限和系统麦克风权限后重试。";
    } else if (e.name === "NotReadableError" || e.name === "AbortError") {
      error.value =
        "麦克风暂时无法打开，请检查系统输入设备，或关闭占用麦克风的应用后重试。";
    } else {
      error.value = "无法打开麦克风，请检查系统的声音输入设置后重试。";
    }
  }
}

function stop() {
  clearInterval(timer);
  if (!recorder || status.value !== "recording") return;
  status.value = "transcribing";
  recorder.port.postMessage("finish");
}

function fail(message) {
  cancel();
  error.value = message;
}

onBeforeUnmount(cancel);
defineExpose({ cancel });
</script>

<template>
  <button
    class="voice-trigger"
    :class="{ recording: status === 'recording' }"
    type="button"
    :aria-label="buttonLabel"
    :title="buttonLabel"
    :disabled="disabled || (status !== 'idle' && status !== 'recording')"
    @click="status === 'recording' ? stop() : start()"
  >
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      stroke-linecap="round"
      aria-hidden="true"
    >
      <rect
        v-if="status === 'recording'"
        x="7"
        y="7"
        width="10"
        height="10"
        rx="2"
        fill="currentColor"
        stroke="none"
      />
      <template v-else>
        <rect x="9" y="3" width="6" height="12" rx="3" />
        <path d="M5 11v1a7 7 0 0 0 14 0v-1M12 19v3" />
      </template>
    </svg>
  </button>
</template>

<style scoped>
.voice-trigger {
  display: grid;
  place-items: center;
  flex: 0 0 44px;
  width: 44px;
  height: 44px;
  padding: 0;
  border-radius: 50%;
  border-color: #dcd5fc;
  background: #f3f0ff;
  color: #6049d8;
}
.voice-trigger svg {
  width: 22px;
  height: 22px;
}
.voice-trigger:focus-visible {
  outline: 2px solid #6852e8;
  outline-offset: 3px;
}
.voice-trigger.recording,
.voice-trigger.recording:hover:not(:disabled) {
  border-color: #af3247;
  background: #af3247;
  color: white;
}
</style>

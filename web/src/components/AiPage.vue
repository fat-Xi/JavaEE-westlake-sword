<script setup>
import {
  computed,
  ref,
  inject,
  onMounted,
  onBeforeUnmount,
  nextTick,
} from "vue";
import { api } from "../api";
import AiVoiceInput from "./AiVoiceInput.vue";
import { createSpeechPlayer, readSpeech } from "../voice/playback";
const { user } = inject("app");
const platformScope = "全平台参会服务";
const chat = ref(null),
  pendingQuestion = ref(null);
async function scrollChat() {
  await nextTick();
  if (chat.value) chat.value.scrollTop = chat.value.scrollHeight;
}
const events = ref([]),
  selected = ref(0),
  initialized = ref(false),
  question = ref(""),
  history = ref([]);
const loading = ref(true),
  asking = ref(false),
  error = ref("");
const voice = ref({}),
  voiceInput = ref(null),
  voiceFeedback = ref(null),
  synthesizing = ref(""),
  playing = ref(null),
  liveSpeech = ref(null),
  speechError = ref(null);
const playback = computed(() => liveSpeech.value ?? playing.value);
const voiceBusy = computed(
  () => !!voiceFeedback.value && !voiceFeedback.value.error,
);
const showNoSpeech = computed(
  () =>
    voiceFeedback.value?.error &&
    voiceFeedback.value.message === "没有识别到语音，请重新录制",
);
const inputNotice = computed(() => {
  if (voiceBusy.value || showNoSpeech.value) return "";
  if (voiceFeedback.value?.error) return voiceFeedback.value.message;
  return question.value.length > 500
    ? "识别文字超过 500 字，请删减后发送。"
    : "";
});
const audioUrls = new Map();
let speechRequest, player, livePlayer;
const examples = [
  "推荐适合我的议程",
  "总结会议亮点",
  "我的签到情况",
  "如何报名",
];
const modes = {
  model: "AI 回答",
  platform: "参会助手",
  faq: "资料检索结果",
  refused: "暂无法回答",
};
let controller,
  requestId = 0,
  disposed = false;

let typingTimer, finishTyping;
function stopTyping() {
  clearTimeout(typingTimer);
  finishTyping?.();
  finishTyping = null;
}

// 对真实接口返回的答案逐字展示；离开页面时取消动画。
function typeAnswer(reply) {
  const letters = Array.from(reply.answer || "");
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) {
    reply.shown = reply.answer;
    reply.typing = false;
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    finishTyping = resolve;
    let index = 0;
    const tick = () => {
      const follow =
        chat.value &&
        chat.value.scrollHeight -
          chat.value.scrollTop -
          chat.value.clientHeight <
          80;
      reply.shown += letters[index++] || "";
      if (follow) scrollChat();
      if (index < letters.length) typingTimer = setTimeout(tick, 12);
      else {
        reply.typing = false;
        finishTyping = null;
        resolve();
      }
    };
    tick();
  });
}

async function initialize() {
  loading.value = true;
  error.value = "";
  try {
    const [meetings, voiceStatus] = await Promise.all([
      api("/ai/events"),
      api("/ai/voice/status").catch(() => ({})),
    ]);
    if (disposed) return;
    events.value = meetings;
    voice.value = voiceStatus;
    initialized.value = true;
  } catch (e) {
    if (!disposed) error.value = e.message;
  } finally {
    if (!disposed) loading.value = false;
  }
}

async function ask() {
  const text = question.value.trim();
  if (!text || text.length > 500 || asking.value || voiceBusy.value) return;
  const scopeId = selected.value || null;
  const scope =
    events.value.find((event) => event.id === scopeId)?.title ?? platformScope;
  const current = ++requestId;
  controller = new AbortController();
  asking.value = true;
  pendingQuestion.value = { text, scope };
  scrollChat();
  error.value = "";
  try {
    const reply = await api(
      "/ai/ask",
      "POST",
      { question: text, eventId: scopeId },
      { signal: controller.signal },
    );
    if (current !== requestId || disposed) return;
    history.value = [
      ...history.value,
      { ...reply, question: text, scope, shown: "", typing: true },
    ].slice(-10);
    const activeReply = playback.value?.id ?? synthesizing.value;
    if (
      activeReply &&
      !history.value.some((item) => item.replyId === activeReply)
    )
      stopPlayback();
    for (const [id, url] of audioUrls) {
      if (history.value.some((item) => item.replyId === id)) continue;
      URL.revokeObjectURL(url);
      audioUrls.delete(id);
    }
    question.value = "";
    pendingQuestion.value = null;
    await scrollChat();
    if (current === requestId && !disposed)
      await typeAnswer(history.value.at(-1));
  } catch (e) {
    if (current === requestId && !disposed && e.name !== "AbortError")
      error.value = e.message;
  } finally {
    if (current === requestId && !disposed) {
      asking.value = false;
      pendingQuestion.value = null;
    }
  }
}

function stopPlayback() {
  speechRequest?.abort();
  speechRequest = null;
  synthesizing.value = "";
  livePlayer?.close();
  livePlayer = null;
  liveSpeech.value = null;
  player?.pause();
  player = null;
  playing.value = null;
}

function clearVoice() {
  speechError.value = null;
  stopPlayback();
  for (const url of audioUrls.values()) URL.revokeObjectURL(url);
  audioUrls.clear();
}

async function toggleSpeech() {
  const speech = playback.value;
  if (!speech) return;
  try {
    if (livePlayer) {
      if (speech.paused) await livePlayer.resume();
      else await livePlayer.pause();
    } else if (speech.paused) await player?.play();
    else player?.pause();
    speech.paused = !speech.paused;
  } catch {
    // The user can close the player or choose another reply while it changes state.
  }
}

async function listen(id) {
  if (synthesizing.value || voiceBusy.value) return;
  speechError.value = null;
  stopPlayback();
  let url = audioUrls.get(id);
  if (!url) {
    const current = new AbortController();
    speechRequest = current;
    synthesizing.value = id;
    try {
      const streamPlayer =
        window.AudioContext && window.ReadableStream
          ? createSpeechPlayer()
          : null;
      livePlayer = streamPlayer;
      const audio = streamPlayer
        ? await readSpeech(id, current.signal, (pcm) => {
            if (speechRequest !== current || disposed) return;
            liveSpeech.value ??= { id, paused: false };
            streamPlayer.append(pcm);
          })
        : await api(
            "/ai/voice/synthesize",
            "POST",
            { replyId: id },
            {
              signal: current.signal,
              responseType: "blob",
            },
          );
      if (speechRequest !== current || disposed) return;
      if (!history.value.some((reply) => reply.replyId === id)) return;
      url = URL.createObjectURL(audio);
      audioUrls.set(id, url);
      if (streamPlayer) {
        synthesizing.value = "";
        await streamPlayer.finish();
        return;
      }
    } catch (e) {
      if (speechRequest === current && e.name !== "AbortError") {
        speechError.value = { id, message: e.message };
      }
      return;
    } finally {
      if (speechRequest === current) {
        speechRequest = null;
        synthesizing.value = "";
        livePlayer?.close();
        livePlayer = null;
        liveSpeech.value = null;
      }
    }
  }
  const audio = new Audio(url);
  player = audio;
  playing.value = { id, paused: false };
  audio.onended = () => {
    if (player === audio) stopPlayback();
  };
  const playbackFailed = () => {
    if (player !== audio) return;
    stopPlayback();
    speechError.value = { id, message: "播放失败，请点击朗读重试" };
  };
  audio.onerror = playbackFailed;
  await audio.play().catch(playbackFailed);
}

onMounted(initialize);
onBeforeUnmount(() => {
  disposed = true;
  stopTyping();
  requestId++;
  controller?.abort();
  clearVoice();
});
</script>

<template>
  <div class="assistant-page">
    <div class="ai-heading">
      <span class="eyebrow">HUIXIANG INTELLIGENCE</span>
      <h1>你的参会灵感助手 ✦</h1>
      <p>从了解会议，到规划日程，让每一次提问都有方向。</p>
    </div>
    <section class="assistant-box" aria-label="AI 会议问答">
      <div
        ref="chat"
        class="chat-feed"
        aria-label="问答记录"
        aria-live="polite"
      >
        <p class="chat-bubble">
          你好，{{
            user.nick
          }}。我可以帮你了解会议、查找议程和整理参会资料。想先了解什么？
        </p>
        <template
          v-for="(reply, index) in history"
          :key="reply.replyId || index"
          ><p class="chat-bubble mine">
            <small class="question-scope">{{ reply.scope }}</small>
            {{ reply.question }}
          </p>
          <article class="chat-bubble">
            <small>{{ modes[reply.mode] }}</small>
            <p class="ai-answer" :class="{ typing: reply.typing }">
              {{ reply.shown }}
            </p>
            <small v-if="!reply.typing && reply.notice">{{
              reply.notice
            }}</small>
            <details v-if="!reply.typing && reply.sources?.length">
              <summary>回答来源 · {{ reply.sources.length }}</summary>
              <div v-for="source in reply.sources" :key="source.id">
                <a :href="source.url" target="_blank" rel="noopener"
                  >{{ source.title }} ↗</a
                >
                <p>{{ source.excerpt }}</p>
              </div>
            </details>
            <div
              v-if="voice.synthesize && reply.replyId"
              class="ai-reply-actions"
            >
              <template
                v-if="
                  playback?.id === reply.replyId ||
                  synthesizing === reply.replyId
                "
              >
                <button
                  v-if="playback?.id === reply.replyId"
                  class="ai-reply-action"
                  type="button"
                  :aria-label="playback.paused ? '继续朗读' : '暂停朗读'"
                  :title="playback.paused ? '继续朗读' : '暂停朗读'"
                  @click="toggleSpeech"
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="currentColor"
                    aria-hidden="true"
                  >
                    <path v-if="playback.paused" d="M8 5v14l11-7z" />
                    <path v-else d="M7 5h3v14H7zm7 0h3v14h-3z" />
                  </svg>
                </button>
                <span
                  v-else
                  class="ai-speech-loading"
                  role="status"
                  aria-label="正在生成语音"
                ></span>
                <button
                  class="ai-reply-action"
                  type="button"
                  aria-label="停止朗读"
                  title="停止朗读"
                  @click="stopPlayback"
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="currentColor"
                    aria-hidden="true"
                  >
                    <rect x="6" y="6" width="12" height="12" rx="2" />
                  </svg>
                </button>
              </template>
              <button
                v-else
                class="ai-reply-action"
                type="button"
                aria-label="朗读回答"
                title="朗读回答"
                :disabled="!!synthesizing || voiceBusy"
                @click="listen(reply.replyId)"
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.7"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path
                    d="M3 9h4l5-4v14l-5-4H3zM16 9a4 4 0 0 1 0 6m2.6-8.6a8 8 0 0 1 0 11.2"
                  />
                </svg>
              </button>
              <small
                v-if="speechError?.id === reply.replyId"
                role="alert"
                class="ai-error"
              >
                {{ speechError.message }}
              </small>
            </div>
          </article></template
        >
        <template v-if="asking && pendingQuestion"
          ><p class="chat-bubble mine">
            <small class="question-scope">{{ pendingQuestion.scope }}</small>
            {{ pendingQuestion.text }}
          </p>
          <p class="chat-bubble" role="status">
            正在查阅资料并生成回答…
          </p></template
        >
        <p v-if="error" role="alert" class="notice">
          {{ error
          }}<button v-if="!initialized" @click="initialize">重试</button>
        </p>
      </div>
      <form class="ai-composer" @submit.prevent="ask">
        <div class="actions ai-controls">
          <label
            >咨询会议<select
              v-model="selected"
              :disabled="asking || voiceBusy || !initialized"
            >
              <option :value="0">{{ platformScope }}</option>
              <option v-for="event in events" :key="event.id" :value="event.id">
                {{ event.title }}
              </option>
            </select></label
          >
        </div>
        <div class="ai-shortcuts">
          <button
            v-for="example in examples"
            :key="example"
            type="button"
            :disabled="asking || voiceBusy || !initialized"
            @click="question = example"
          >
            {{ example }}
          </button>
        </div>
        <div class="compact-composer">
          <AiVoiceInput
            v-if="voice.transcribe"
            ref="voiceInput"
            :disabled="asking || !initialized || !!synthesizing"
            @feedback="voiceFeedback = $event"
            @start="stopPlayback"
            @transcript="question = $event"
          />
          <div
            class="ai-input"
            :class="{
              'show-voice-hint': voiceBusy || showNoSpeech,
              'has-notice': inputNotice,
            }"
          >
            <textarea
              v-model="question"
              :disabled="asking || voiceBusy || !initialized"
              required
              maxlength="500"
              :placeholder="voiceBusy || showNoSpeech ? '' : '输入问题…'"
              aria-label="你的问题"
              @focus="showNoSpeech && (voiceFeedback = null)"
              @input="voiceFeedback = null"
              @keydown.ctrl.enter.prevent="ask"
            ></textarea>
            <div v-if="voiceBusy" class="ai-voice-status">
              <span role="status">{{ voiceFeedback.message }}</span>
              <button
                class="ai-voice-cancel"
                type="button"
                @click="voiceInput?.cancel()"
              >
                取消
              </button>
            </div>
            <span
              v-else-if="showNoSpeech"
              class="ai-voice-placeholder"
              role="alert"
            >
              {{ voiceFeedback.message }}
            </span>
            <span v-else-if="inputNotice" class="ai-input-notice" role="alert">
              {{ inputNotice }}
            </span>
          </div>
          <button
            class="primary"
            :disabled="
              asking ||
              voiceBusy ||
              !initialized ||
              !question.trim() ||
              question.length > 500
            "
          >
            {{ asking ? "回答中…" : "发送 ↑" }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.chat-bubble .question-scope {
  display: block;
  margin-bottom: 3px;
  color: #eee9ff;
  line-height: 1.4;
}
.ai-controls {
  align-items: center;
}
.ai-composer .compact-composer {
  align-items: center;
}
.ai-controls label,
.ai-input {
  min-width: 0;
}
.ai-controls select {
  width: min(19rem, 100%);
}
.ai-input {
  position: relative;
  display: grid;
  flex: 1;
}
.ai-input.show-voice-hint textarea {
  color: transparent;
}
.ai-voice-status,
.ai-voice-placeholder,
.ai-input-notice {
  font-size: 13px;
  overflow-wrap: anywhere;
}
.ai-voice-status,
.ai-voice-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  padding: 4px 8px 4px 12px;
}
.ai-voice-status {
  gap: 4px;
  color: #68798f;
}
.ai-voice-placeholder {
  line-height: 1.3;
  color: #895b54;
  pointer-events: none;
}
.ai-voice-status .ai-voice-cancel {
  flex-shrink: 0;
  min-height: 32px;
  padding: 4px 6px;
  border: 0;
  background: transparent;
  color: #6049d8;
}
.ai-input.has-notice {
  border: 1px solid #dce2f2;
  border-radius: 12px;
  background: white;
}
.ai-input.has-notice textarea {
  border: 0;
}
.ai-input-notice {
  padding: 0 14px 8px;
  color: #895b54;
}
.ai-reply-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}
.ai-reply-actions .ai-reply-action {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #64758f;
}
.ai-reply-actions .ai-reply-action:hover:not(:disabled) {
  background: #e3e9f8;
  color: #5142a4;
}
.ai-reply-action svg {
  width: 18px;
  height: 18px;
}
.ai-speech-loading {
  width: 14px;
  height: 14px;
  margin: 0 9px;
  border: 2px solid #aab6c9;
  border-top-color: #5142a4;
  border-radius: 50%;
  animation: ai-speech-spin 0.8s linear infinite;
}
@keyframes ai-speech-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .ai-speech-loading {
    animation: none;
  }
}
.ai-reply-actions .ai-error {
  color: #914737;
}
</style>

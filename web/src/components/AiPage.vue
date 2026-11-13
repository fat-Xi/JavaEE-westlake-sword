<script setup>
import { ref, watch, inject, onMounted, onBeforeUnmount, nextTick } from "vue";
import { api } from "../api";
const { page, eventId, user } = inject("app");
const chat = ref(null),
  pendingText = ref("");
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
    const meetings = await api("/ai/events");
    if (disposed) return;
    events.value = meetings;
    initialized.value = true;
    const current =
      eventId.value ||
      Number(new URLSearchParams(location.search).get("event"));
    selected.value = meetings.some((e) => e.id === current) ? current : 0;
  } catch (e) {
    if (!disposed) error.value = e.message;
  } finally {
    if (!disposed) loading.value = false;
  }
}

async function ask() {
  const text = question.value.trim();
  if (!text || asking.value) return;
  const current = ++requestId;
  controller = new AbortController();
  asking.value = true;
  pendingText.value = text;
  scrollChat();
  error.value = "";
  try {
    const reply = await api(
      "/ai/ask",
      "POST",
      { question: text, eventId: selected.value || null },
      { signal: controller.signal },
    );
    if (current !== requestId || disposed) return;
    history.value = [
      ...history.value,
      { question: text, ...reply, shown: "", typing: true },
    ].slice(-10);
    question.value = "";
    pendingText.value = "";
    await scrollChat();
    await typeAnswer(history.value[history.value.length - 1]);
  } catch (e) {
    if (current === requestId && !disposed && e.name !== "AbortError")
      error.value = e.message;
  } finally {
    if (current === requestId && !disposed) {
      asking.value = false;
      pendingText.value = "";
    }
  }
}

watch(selected, () => {
  stopTyping();
  pendingText.value = "";
  requestId++;
  controller?.abort();
  asking.value = false;
  history.value = [];
  question.value = "";
  error.value = "";
});
onMounted(initialize);
onBeforeUnmount(() => {
  disposed = true;
  stopTyping();
  requestId++;
  controller?.abort();
});
</script>

<template>
  <div class="assistant-page">
    <div class="ai-heading">
      <span class="eyebrow">HUIXIANG INTELLIGENCE</span>
      <h1>你的参会灵感助手 ✦</h1>
      <p>从了解会议，到规划日程，让每一次提问都有方向。</p>
    </div>
    <section class="assistant-box">
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
        <template v-for="(reply, index) in history" :key="index"
          ><p class="chat-bubble mine">{{ reply.question }}</p>
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
          </article></template
        >
        <template v-if="asking && pendingText"
          ><p class="chat-bubble mine">{{ pendingText }}</p>
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
        <div class="ai-shortcuts">
          <button
            v-for="example in examples"
            :key="example"
            type="button"
            :disabled="asking"
            @click="question = example"
          >
            {{ example }}
          </button>
        </div>
        <div class="compact-composer">
          <textarea
            v-model="question"
            :disabled="asking || !initialized"
            required
            maxlength="500"
            placeholder="输入问题…"
            aria-label="你的问题"
            @keydown.ctrl.enter.prevent="ask"
          ></textarea
          ><button
            class="primary"
            :disabled="asking || !initialized || !question.trim()"
          >
            {{ asking ? "回答中…" : "发送 ↑" }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>

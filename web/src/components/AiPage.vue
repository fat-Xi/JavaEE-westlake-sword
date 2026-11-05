<script setup>
import { ref, watch, inject, onMounted, onBeforeUnmount } from "vue";
import { api } from "../api";
const { page, eventId } = inject("app");
const events = ref([]),
  selected = ref(0),
  initialized = ref(false),
  question = ref(""),
  history = ref([]);
const loading = ref(true),
  asking = ref(false),
  error = ref("");
const examples = [
  "现在都有哪些会议？",
  "现在有哪些会议可以报名？",
  "会场在哪里？",
  "如何查看个人日程？",
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
  error.value = "";
  try {
    const reply = await api(
      "/ai/ask",
      "POST",
      { question: text, eventId: selected.value || null },
      { signal: controller.signal },
    );
    if (current !== requestId || disposed) return;
    history.value = [...history.value, { question: text, ...reply }].slice(-10);
    question.value = "";
  } catch (e) {
    if (current === requestId && !disposed && e.name !== "AbortError")
      error.value = e.message;
  } finally {
    if (current === requestId && !disposed) asking.value = false;
  }
}

watch(selected, () => {
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
  requestId++;
  controller?.abort();
});
</script>

<template>
  <section class="panel ai-page" aria-label="AI 会议问答">
    <span class="eyebrow">MEETING ASSISTANT</span>
    <h1>AI 会议问答</h1>
    <p class="muted">
      查询公开会议、报名、个人日程、签到、资料和互动服务，也可以咨询使用平台遇到的问题。
    </p>
    <p v-if="loading" role="status">正在加载可咨询的会议…</p>
    <template v-else>
      <p v-if="error" role="alert" class="ai-error">{{ error }}</p>
      <template v-if="initialized">
        <label
          >咨询会议<select v-model="selected" :disabled="asking">
            <option :value="0">全平台参会服务</option>
            <option v-for="event in events" :key="event.id" :value="event.id">
              {{ event.title }}
            </option>
          </select></label
        >
        <p class="muted">
          无需选择会议即可咨询平台服务；详细会议资料请选择已通过报名的会议。切换范围会清空本页对话。
        </p>
        <p v-if="!events.length" class="muted">
          暂无报名通过的会议，仍可查询公开会议、可报名活动和参会流程。
        </p>
        <div class="actions ai-examples">
          <button
            v-for="example in examples"
            :key="example"
            :disabled="asking"
            @click="question = example"
          >
            {{ example }}
          </button>
        </div>
        <form @submit.prevent="ask">
          <label
            >你的问题<textarea
              v-model="question"
              :disabled="asking"
              required
              maxlength="500"
              placeholder="例如：现在有哪些会议可以报名？如何查看我的报名状态？"
            ></textarea>
          </label>
          <div class="actions">
            <button class="primary" :disabled="asking || !question.trim()">
              {{ asking ? "正在检索并生成回答…" : "发送问题" }}</button
            ><span class="muted">{{ question.length }}/500</span>
          </div>
        </form>
        <p v-if="asking" role="status">
          正在查阅会议资料，请稍候。服务超时会自动提供检索结果。
        </p>
        <section aria-label="问答记录" aria-live="polite" class="ai-history">
          <article
            v-for="(reply, index) in history"
            :key="index"
            class="ai-reply"
          >
            <h2 class="pre">{{ reply.question }}</h2>
            <span class="tag">{{ modes[reply.mode] }}</span>
            <p v-if="reply.notice" class="ai-notice">{{ reply.notice }}</p>
            <p class="pre ai-answer">{{ reply.answer }}</p>
            <section
              v-if="reply.sources.length"
              aria-label="回答来源"
              class="ai-sources"
            >
              <h3>回答来源</h3>
              <article
                v-for="(source, number) in reply.sources"
                :key="source.id"
                class="ai-source"
              >
                <strong>{{ number + 1 }}. {{ source.title }}</strong>
                <a
                  :href="source.url"
                  target="_blank"
                  rel="noopener"
                  :aria-label="'查看来源：' + source.title"
                  >查看来源 ↗</a
                >
                <details>
                  <summary>查看原文片段</summary>
                  <p class="pre">{{ source.excerpt }}</p>
                </details>
              </article>
            </section>
          </article>
          <p v-if="!history.length && !asking" class="empty">
            可以直接提问；也可以点击上面的示例问题。
          </p>
        </section>
        <p class="muted">
          本页仅保留最近 10
          条问答，离开页面后清空。每次独立检索，请尽量写明议程或主题。
        </p>
      </template>
      <div v-else class="empty">
        <p>
          {{
            error
              ? "会议列表加载失败，请重试。"
              : "暂无已通过报名的会议，请先报名并等待审核。"
          }}
        </p>
        <div class="actions">
          <button v-if="error" @click="initialize">重新加载</button
          ><button @click="page = 'events'">前往发现活动</button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ai-page {
  max-width: 1000px;
  margin: 0 auto;
  min-width: 0;
}
.ai-examples {
  margin: 24px 0;
}
.ai-reply {
  margin-top: 28px;
  padding-top: 24px;
  border-top: 1px solid #ccdedb;
  overflow-wrap: anywhere;
}
.ai-reply h2 {
  font-size: 19px;
}
.ai-answer {
  margin: 18px 0;
}
.ai-error,
.ai-notice {
  color: #914737;
}
.ai-source {
  padding: 14px 0;
  border-bottom: 1px solid #e0ece8;
}
.ai-source a {
  display: inline-block;
  margin-left: 14px;
}
.ai-source details {
  margin-top: 8px;
}
.ai-source summary {
  cursor: pointer;
  color: #577679;
}
.ai-source p {
  margin: 10px 0;
}
@media (max-width: 600px) {
  .ai-source a {
    display: block;
    margin: 8px 0;
  }
  .ai-examples button {
    text-align: left;
  }
}
</style>

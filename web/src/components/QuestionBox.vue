<script setup>
import { ref, computed, inject, watch, onBeforeUnmount } from "vue";
import { api, states, postedTime } from "../api";
const props = defineProps({
  eventId: Number,
  talks: Array,
  eventEnded: Boolean,
});
const { run, busy, message } = inject("app");
const rows = ref([]),
  loading = ref(false),
  error = ref("");
const body = ref(""),
  talkId = ref(null),
  talkFilter = ref("ALL"),
  status = ref("ALL"),
  order = ref("hot");
let requestId = 0;

async function load() {
  const current = ++requestId;
  loading.value = true;
  error.value = "";
  try {
    const data = await api(`/events/${props.eventId}/questions`);
    if (current === requestId) rows.value = data;
  } catch (e) {
    if (current === requestId) {
      rows.value = [];
      error.value = e.message;
    }
  } finally {
    if (current === requestId) loading.value = false;
  }
}
const matchesTalk = (q) =>
  talkFilter.value === "ALL" ||
  (talkFilter.value === "MEETING"
    ? q.talk_id == null
    : q.talk_id === Number(talkFilter.value));
const published = computed(() =>
  rows.value
    .filter((q) => {
      if (q.status !== "APPROVED" || !matchesTalk(q)) return false;
      return (
        status.value === "ALL" ||
        (status.value === "OPEN" && !q.closed && !q.answer) ||
        (status.value === "ANSWERED" && !!q.answer) ||
        (status.value === "FEATURED" && q.featured) ||
        (status.value === "CLOSED" && q.closed)
      );
    })
    .sort((a, b) =>
      order.value === "new"
        ? b.id - a.id
        : Number(b.featured) - Number(a.featured) ||
          b.votes - a.votes ||
          b.id - a.id,
    ),
);
const mine = computed(() =>
  rows.value
    .filter((q) => q.mine && q.status !== "APPROVED" && matchesTalk(q))
    .sort((a, b) => b.id - a.id),
);
async function submit() {
  await run(async () => {
    if (!body.value.trim()) throw new Error("请输入问题内容");
    await api("/questions", "POST", {
      eventId: props.eventId,
      talkId: talkId.value,
      body: body.value.trim(),
    });
    body.value = "";
    await load();
    message.value = "问题已提交，审核通过后公开";
  });
}
async function support(q) {
  await run(async () => {
    await api(`/questions/${q.id}/support`, q.supported ? "DELETE" : "POST");
    await load();
    message.value = q.supported ? "已撤销赞同" : "已赞同该问题";
  });
}
watch(
  () => props.eventId,
  () => {
    body.value = "";
    talkId.value = null;
    talkFilter.value = "ALL";
    load();
  },
  { immediate: true },
);
onBeforeUnmount(() => requestId++);
</script>

<template>
  <section aria-label="会议提问箱" class="question-box">
    <div class="question-toolbar">
      <div>
        <h2>会议提问</h2>
        <p class="muted">
          提交议程问题，赞同你关注的话题。审核通过的问题按精选和赞同数排序。
        </p>
      </div>
      <button :disabled="busy || loading" @click="load">
        {{ loading ? "正在加载…" : "刷新问题" }}
      </button>
    </div>
    <form v-if="!eventEnded" class="section-block" @submit.prevent="submit">
      <label
        >关联议程<select v-model="talkId">
          <option :value="null">全会提问（不指定议程）</option>
          <option v-for="talk in talks" :key="talk.id" :value="talk.id">
            {{ talk.title }}
          </option>
        </select></label
      >
      <label
        >问题<textarea
          v-model="body"
          required
          maxlength="500"
          placeholder="写下想向嘉宾或主办方了解的问题"
        ></textarea>
      </label>
      <div class="actions">
        <button class="primary" :disabled="busy || !body.trim()">
          提交问题审核</button
        ><span class="muted">{{ body.length }}/500 · 审核前仅本人可见</span>
      </div>
    </form>
    <p v-else class="notice">
      会议已结束，停止征集新问题和新增赞同，已公开的问题与答复仍可查看。
    </p>
    <div class="form-grid question-filters">
      <label
        >筛选议程<select v-model="talkFilter">
          <option value="ALL">全部议程</option>
          <option value="MEETING">全会提问</option>
          <option v-for="talk in talks" :key="talk.id" :value="talk.id">
            {{ talk.title }}
          </option>
        </select></label
      >
      <label
        >问题状态<select v-model="status">
          <option value="ALL">全部公开问题</option>
          <option value="OPEN">待回答</option>
          <option value="ANSWERED">已回答</option>
          <option value="FEATURED">精选</option>
          <option value="CLOSED">已关闭</option>
        </select></label
      >
      <label
        >排序方式<select v-model="order">
          <option value="hot">精选与赞同优先</option>
          <option value="new">最新发布</option>
        </select></label
      >
    </div>
    <p v-if="error" role="alert">问题加载失败：{{ error }}，请刷新重试。</p>
    <template v-else-if="!loading">
      <section aria-label="公开问题">
        <article
          v-for="q in published"
          :key="q.id"
          :id="'question-' + q.id"
          class="question-row"
        >
          <div class="actions">
            <span class="tag">{{ q.talk_title || "全会提问" }}</span
            ><span v-if="q.featured" class="tag">精选</span
            ><span class="muted">{{
              q.closed ? "已关闭" : q.answer ? "已回答" : "待回答"
            }}</span>
          </div>
          <h3 class="pre">{{ q.body }}</h3>
          <p class="muted">
            {{ q.name }} ·
            <time :datetime="q.created" title="发表时间（北京时间）">{{
              postedTime(q.created)
            }}</time>
          </p>
          <div v-if="q.answer" class="question-answer">
            <strong>主办方答复</strong>
            <p class="pre">{{ q.answer }}</p>
          </div>
          <button
            :disabled="busy || (!q.supported && (q.closed || eventEnded))"
            @click="support(q)"
          >
            {{ q.supported ? "撤销赞同" : "赞同" }} · {{ q.votes }}
          </button>
          <span v-if="q.closed" class="muted question-closed"
            >停止新增赞同，已赞同者仍可撤销</span
          >
        </article>
        <p v-if="!published.length" class="empty">暂无符合条件的公开问题。</p>
      </section>
      <section
        v-if="mine.length"
        aria-label="我的未公开问题"
        class="section-block"
      >
        <h3>我的未公开问题</h3>
        <p class="muted">以下待审或驳回记录仅你本人可见。</p>
        <article v-for="q in mine" :key="q.id" class="question-row">
          <span class="tag">{{ states[q.status] }}</span>
          <p class="muted">
            {{ q.talk_title || "全会提问" }} ·
            <time :datetime="q.created" title="发表时间（北京时间）">{{
              postedTime(q.created)
            }}</time>
          </p>
          <h3 class="pre">{{ q.body }}</h3>
          <p v-if="q.reason" class="question-reason">
            驳回原因：{{ q.reason }}
          </p>
        </article>
      </section>
    </template>
  </section>
</template>

<style scoped>
.question-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.question-toolbar > button {
  flex-shrink: 0;
}
.question-row {
  padding: 22px 0;
  border-bottom: 1px solid #dceae6;
  overflow-wrap: anywhere;
}
.question-filters {
  margin-top: 24px;
}
.question-answer {
  margin: 16px 0;
  padding-left: 16px;
  border-left: 3px solid #87b9ae;
}
.question-answer p {
  margin: 6px 0;
}
.question-reason {
  color: #914737;
}
.question-closed {
  display: inline-block;
  margin-left: 12px;
  font-size: 13px;
}
@media (max-width: 600px) {
  .question-toolbar {
    flex-direction: column;
  }
  .question-closed {
    display: block;
    margin: 10px 0 0;
  }
}
</style>

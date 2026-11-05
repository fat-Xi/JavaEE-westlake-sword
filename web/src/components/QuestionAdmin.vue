<script setup>
import { ref, computed, inject, onMounted } from "vue";
import { api, states, postedTime } from "../api";
const props = defineProps({ statusFilter: String });
const { run, busy, message } = inject("app");
const rows = ref([]),
  events = ref([]),
  eventId = ref(0),
  talkId = ref("ALL"),
  workflow = ref("ALL"),
  reasons = ref({}),
  answers = ref({});
async function load() {
  [rows.value, events.value] = await Promise.all([
    api("/review/questions"),
    api("/admin/events"),
  ]);
  for (const row of rows.value)
    if (!(row.id in answers.value)) answers.value[row.id] = row.answer || "";
}
const talks = computed(() => [
  ...new Map(
    rows.value
      .filter(
        (q) => q.talk_id && (!eventId.value || q.event_id === eventId.value),
      )
      .map((q) => [q.talk_id, { id: q.talk_id, title: q.talk_title }]),
  ).values(),
]);
const visible = computed(() =>
  rows.value.filter((q) => {
    if (props.statusFilter !== "ALL" && q.status !== props.statusFilter)
      return false;
    if (eventId.value && q.event_id !== eventId.value) return false;
    if (
      talkId.value !== "ALL" &&
      (talkId.value === "MEETING"
        ? q.talk_id != null
        : q.talk_id !== Number(talkId.value))
    )
      return false;
    return (
      workflow.value === "ALL" ||
      (workflow.value === "OPEN" && !q.closed && !q.answer) ||
      (workflow.value === "ANSWERED" && !!q.answer) ||
      (workflow.value === "FEATURED" && q.featured) ||
      (workflow.value === "CLOSED" && q.closed)
    );
  }),
);
async function review(row, pass) {
  await run(async () => {
    await api(`/review/questions/${row.id}`, "POST", {
      pass,
      reason: reasons.value[row.id] || "",
    });
    await load();
    message.value = "提问审核状态已更新";
  });
}
async function answer(row) {
  await run(async () => {
    await api(`/review/questions/${row.id}/answer`, "PUT", {
      answer: answers.value[row.id],
    });
    delete answers.value[row.id];
    await load();
    message.value = "主办方答复已发布";
  });
}
async function state(row, key) {
  await run(async () => {
    await api(`/review/questions/${row.id}/state`, "PUT", { [key]: !row[key] });
    await load();
    message.value =
      key === "featured" ? "精选状态已更新" : "问题开放状态已更新";
  });
}
onMounted(() => run(load));
</script>

<template>
  <section class="panel question-admin" aria-label="提问箱管理">
    <div class="actions">
      <h2>会议提问审核与处理</h2>
      <button :disabled="busy" @click="run(load)">刷新提问</button>
    </div>
    <p class="muted">
      筛选会议和议程，按精选与赞同数查看问题；审核、答复及关闭操作会保留历史记录。
    </p>
    <div class="form-grid">
      <label
        >提问所属会议<select v-model="eventId" @change="talkId = 'ALL'">
          <option :value="0">全部会议</option>
          <option v-for="event in events" :key="event.id" :value="event.id">
            {{ event.title }}
          </option>
        </select></label
      >
      <label
        >提问所属议程<select v-model="talkId">
          <option value="ALL">全部议程</option>
          <option value="MEETING">全会提问</option>
          <option v-for="talk in talks" :key="talk.id" :value="talk.id">
            {{ talk.title }}
          </option>
        </select></label
      >
      <label
        >处理状态<select v-model="workflow">
          <option value="ALL">全部处理状态</option>
          <option value="OPEN">待回答</option>
          <option value="ANSWERED">已回答</option>
          <option value="FEATURED">精选</option>
          <option value="CLOSED">已关闭</option>
        </select></label
      >
    </div>
    <article v-for="row in visible" :key="row.id" class="review-row">
      <div class="actions">
        <span class="tag">{{ states[row.status] }}</span
        ><span v-if="row.featured" class="tag">精选</span
        ><span v-if="row.closed" class="tag">已关闭</span
        ><span>{{ row.votes }} 人赞同</span>
      </div>
      <h3 class="pre">{{ row.body }}</h3>
      <p class="muted">
        {{ row.event_title }} · {{ row.talk_title || "全会提问" }}
      </p>
      <p class="muted">
        {{ row.name }} ·
        <time :datetime="row.created" title="发表时间（北京时间）">{{
          postedTime(row.created)
        }}</time>
      </p>
      <p v-if="row.reason" class="question-reason">
        驳回原因：{{ row.reason }}
      </p>
      <label
        >驳回原因<input
          v-model="reasons[row.id]"
          maxlength="200"
          placeholder="填写驳回或下架原因"
      /></label>
      <div class="actions">
        <button :disabled="busy" @click="review(row, true)">通过提问</button
        ><button :disabled="busy" @click="review(row, false)">
          驳回 / 下架提问
        </button>
      </div>
      <template v-if="row.status === 'APPROVED'">
        <div class="actions question-state">
          <button :disabled="busy" @click="state(row, 'featured')">
            {{ row.featured ? "取消精选" : "设为精选" }}</button
          ><button :disabled="busy" @click="state(row, 'closed')">
            {{ row.closed ? "重新开放问题" : "关闭问题" }}
          </button>
        </div>
        <form @submit.prevent="answer(row)">
          <label
            >主办方人工答复<textarea
              v-model="answers[row.id]"
              required
              maxlength="1000"
              placeholder="请输入主办方答复"
            ></textarea></label
          ><button :disabled="busy">
            {{ row.answer ? "更新答复" : "发布答复" }}
          </button>
        </form>
      </template>
    </article>
    <p v-if="!visible.length" class="empty">暂无符合条件的提问。</p>
  </section>
</template>

<style scoped>
.question-admin {
  min-width: 0;
}
.review-row {
  overflow-wrap: anywhere;
}
.question-state {
  margin: 16px 0;
}
.question-reason {
  color: #914737;
}
</style>

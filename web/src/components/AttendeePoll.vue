<script setup>
import { ref, computed, inject, onMounted, onBeforeUnmount } from "vue";
import { api, time } from "../api";
const props = defineProps({ eventId: Number });
const { run, busy, message } = inject("app");
const info = ref({});
const loaded = ref(false);
const deadline = computed(() =>
  rows.value.length
    ? rows.value
        .map((r) => r.ends)
        .sort()
        .at(-1)
    : null,
);
const rows = ref([]),
  choices = ref({}),
  now = ref(Date.now());
function state(r) {
  return !r.enabled
    ? "已关闭"
    : now.value < new Date(r.starts)
      ? "未开始"
      : now.value >= new Date(r.ends)
        ? "已结束"
        : "进行中";
}
const pending = computed(() =>
  rows.value.filter((r) => state(r) === "进行中" && !r.choice.length),
);
const complete = computed(
  () =>
    pending.value.length > 0 && pending.value.every((r) => choices.value[r.id]),
);
function total(r) {
  return r.options.reduce((n, o) => n + o.total, 0);
}
async function load() {
  loaded.value = false;
  info.value = await api(`/events/${props.eventId}/poll-info`);
  rows.value = (await api(`/events/${props.eventId}/polls`)).sort(
    (a, b) => a.id - b.id,
  );
  loaded.value = true;
}
async function submit() {
  await run(async () => {
    await api(`/events/${props.eventId}/polls/vote`, "POST", {
      answers: pending.value.map((r) => ({
        pollId: r.id,
        optionId: choices.value[r.id],
      })),
    });
    await load();
    message.value = "投票成功，提交后不可修改。";
  });
}
let timer;
onMounted(() => {
  run(load);
  timer = setInterval(() => (now.value = Date.now()), 1000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>
<template>
  <section class="attendee-polls">
    <p v-if="!loaded" class="empty" role="status">正在加载投票…</p>
    <div v-else-if="!rows.length" class="poll-empty" role="status">
      <h2>暂无投票</h2>
      <p>本场会议尚未发布投票，发布后你可以在这里查看题目并参与。</p>
      <small>可以先浏览会议议程或参与评论交流。</small>
    </div>
    <template v-else>
      <div class="poll-title-block">
        <h2>{{ info.title || "会议意见征集" }}</h2>
        <small
          >{{ info.publisher || "会议主办方" }} · {{ rows.length }} 道单选题 ·
          {{ deadline ? time(deadline) + " 截止" : "暂未安排投票时间" }} ·
          {{ info.people || 0 }} 人参与</small
        >
      </div>
      <div v-if="pending.length" class="row poll-survey">
        <strong>活动偏好调研</strong
        ><span
          >已完成 {{ pending.filter((r) => choices[r.id]).length }} /
          {{ pending.length }}</span
        >
      </div>
      <article v-for="(r, i) in rows" :key="r.id" class="section-block">
        <div class="row">
          <h3>{{ String(i + 1).padStart(2, "0") }}　{{ r.title }}</h3>
          <span class="badge">{{
            r.choice.length ? "✓ 已投票" : state(r)
          }}</span>
        </div>
        <p class="muted">{{ time(r.starts) }} — {{ time(r.ends) }}</p>
        <label v-for="o in r.options" :key="o.id" class="poll-option"
          ><input
            v-model="choices[r.id]"
            :name="'poll-' + r.id"
            type="radio"
            :value="o.id"
            :disabled="
              busy || !!r.choice.length || state(r) !== '进行中'
            " /><span
            >{{ o.label
            }}{{ r.choice.includes(o.id) ? " · 我的选择" : "" }}</span
          ><template v-if="r.choice.length || state(r) === '已结束'"
            ><strong>{{ o.total }} 票</strong
            ><progress
              :value="o.total"
              :max="total(r) || 1"
            ></progress></template
        ></label>
      </article>
      <div v-if="pending.length" class="row">
        <small
          >已完成 {{ pending.filter((r) => choices[r.id]).length }} /
          {{ pending.length }} 题</small
        ><button class="primary" :disabled="busy || !complete" @click="submit">
          提交全部答案 →
        </button>
      </div>
    </template>
  </section>
</template>

<script setup>
import { ref, inject, onMounted, onBeforeUnmount, computed } from "vue";
import { api, time } from "../api";
import EventCarousel from "./EventCarousel.vue";
const { open, run, search, user } = inject("app");
const summary = ref({});
const rows = ref([]),
  status = ref("全部"),
  sort = ref("推荐优先"),
  now = ref(Date.now());
function topic(row) {
  if (row.meeting_type) return row.meeting_type;
  return row.title.includes("AI")
    ? "人工智能"
    : row.title.includes("云")
      ? "云安全"
      : row.title.includes("签到")
        ? "现场体验"
        : row.title.includes("交流")
          ? "行业交流"
          : "数据安全";
}
function stage(row) {
  return new Date(row.ends).getTime() <= now.value
    ? "已结束"
    : new Date(row.starts).getTime() > now.value
      ? "未开始"
      : "进行中";
}
function score(row) {
  return (user.value.tags || "")
    .split(/[,，\s]+/)
    .filter(Boolean)
    .filter((t) => (row.title + row.content).includes(t)).length;
}
const filtered = computed(() => {
  const result = rows.value.filter(
    (r) =>
      (r.title + r.place + r.content)
        .toLowerCase()
        .includes(search.value.trim().toLowerCase()) &&
      (status.value === "全部" || stage(r) === status.value),
  );
  if (sort.value === "热度最高")
    result.sort((a, b) => b.views - a.views || a.id - b.id);
  else if (sort.value === "时间最近")
    result.sort(
      (a, b) =>
        Math.abs(new Date(a.starts) - now.value) -
        Math.abs(new Date(b.starts) - now.value),
    );
  else result.sort((a, b) => score(b) - score(a) || b.views - a.views);
  return result;
});
// 仅重建列表播放入场动画，轮播与统计保持原位。
const listKey = computed(() =>
  [search.value, status.value, sort.value].join("|"),
);
let timer;
onMounted(() => {
  run(async () => {
    rows.value = await api("/events");
    const names = [
      "西湖论剑 · 数字安全未来峰会",
      "AI 原生应用与可信计算论坛",
      "云上共生 · 企业安全实践",
      "行业交流会 · 报名待审核示例",
      "现场签到体验日 · 正在签到",
    ];
    rows.value.sort(
      (a, b) =>
        (names.includes(a.title) ? names.indexOf(a.title) : 99) -
        (names.includes(b.title) ? names.indexOf(b.title) : 99),
    );
    summary.value = await api("/events/summary");
  });
  timer = setInterval(() => {
    now.value = Date.now();
  }, 60000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>
<template>
  <div class="discover-page">
    <section v-reveal class="discover-copy">
      <span class="eyebrow">EXPLORE · CONNECT · INSPIRE</span>
      <h1>下一次灵感，在这里发生。</h1>
      <p>你好，{{ user.nick }}。开启你的参会之旅 ↗</p>
    </section>
    <EventCarousel :slides="rows.slice(0, 3)" @open="(r) => open(r.id)" />
    <div v-reveal class="discover-stats">
      <div>
        <strong>{{ String(rows.length).padStart(2, "0") }}</strong
        ><span>场精选会议</span>
      </div>
      <div>
        <strong>{{ String(summary.speakers || 0).padStart(2, "0") }}</strong
        ><span>位行业嘉宾</span>
      </div>
      <div>
        <strong>{{ String(summary.talks || 0).padStart(2, "0") }}</strong
        ><span>个专题议程</span>
      </div>
    </div>
    <div v-reveal class="discovery-toolbar">
      <h2>发现值得参与的活动</h2>
      <select v-model="sort" aria-label="活动排序">
        <option>推荐优先</option>
        <option>热度最高</option>
        <option>时间最近</option>
      </select>
    </div>
    <nav class="discovery-filters" aria-label="活动状态">
      <button
        v-for="name in ['全部', '未开始', '进行中', '已结束']"
        :key="name"
        :class="{ active: status === name }"
        @click="status = name"
      >
        {{ name }}
      </button>
    </nav>
    <div :key="listKey" class="activity-grid">
      <article
        v-for="(event, index) in filtered"
        :key="event.id"
        v-reveal
        class="activity-card list-enter"
        :style="{ animationDelay: Math.floor(index / 3) * 75 + 'ms' }"
      >
        <button
          class="activity-cover"
          :class="'cover-' + (event.id % 3)"
          @click="open(event.id)"
        >
          <img
            v-if="event.cover"
            class="live-cover"
            :src="event.cover"
            alt="会议封面"
          />
          <small v-if="!event.cover || event.cover_title || event.meeting_type"
            >{{ topic(event) }} /
            {{ new Date(event.starts).getFullYear() }}</small
          ><span
            v-if="!event.cover || event.cover_title || event.meeting_type"
            class="cover-caption"
            >{{ event.cover_title || topic(event) + " · 新视野" }}</span
          >
        </button>
        <div class="activity-body">
          <span
            class="badge"
            :class="{
              live: stage(event) === '进行中',
              ended: stage(event) === '已结束',
            }"
            >{{ stage(event) }}</span
          >
          <h3>{{ event.title }}</h3>
          <p>
            {{ new Date(event.starts).getMonth() + 1 }}月
            {{ new Date(event.starts).getDate() }}日 · {{ event.place }}
          </p>
          <div class="row">
            <small>◉ {{ event.views || 0 }} 次浏览</small
            ><button class="primary" @click="open(event.id)">查看活动 ↗</button>
          </div>
        </div>
      </article>
      <p v-if="!filtered.length" class="empty list-enter">
        没有匹配的会议，试试其他关键词或筛选条件。
      </p>
    </div>
  </div>
</template>

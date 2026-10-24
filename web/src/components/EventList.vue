<script setup>
// 公开活动列表。搜索只筛选当前已加载的开放活动，不修改数据库。
import { ref, inject, onMounted, computed } from "vue";
import { api, time } from "../api";
const { open, run } = inject("app");
const rows = ref([]);
const query = ref("");
const status = ref("全部活动");
function stage(row) {
  return new Date(row.ends) <= new Date()
    ? "已结束"
    : new Date(row.starts) > new Date()
      ? "未开始"
      : "正在进行";
}
const filtered = computed(() =>
  rows.value.filter(
    (row) =>
      (row.title + row.place).includes(query.value.trim()) &&
      (status.value === "全部活动" || stage(row) === status.value),
  ),
);
onMounted(() =>
  run(async () => {
    rows.value = await api("/events");
  }),
);
</script>
<template>
  <section class="hero discovery-hero">
    <div class="hero-copy">
      <span class="eyebrow">会享 · 让每一次相遇都有价值</span>
      <h1>在这里，遇见<br />下一种可能<span>。</span></h1>
      <p>发现值得参与的交流，让每一次参会更有收获。</p>
      <div class="search-box">
        <span aria-hidden="true">⌕</span
        ><input
          v-model="query"
          placeholder="搜索活动标题或地点"
          aria-label="搜索活动"
        />
      </div>
      <div class="hero-keywords">
        <span>数字安全</span><span>行业交流</span><span>实践分享</span>
      </div>
    </div>
    <div class="hero-visual" aria-hidden="true">
      <div class="orbit orbit-one"></div>
      <div class="orbit orbit-two"></div>
      <div class="visual-core">会<span>CONNECT</span></div>
      <span class="visual-dot dot-one"></span
      ><span class="visual-dot dot-two"></span>
      <div class="visual-note">
        思想汇聚 / 灵感发生<small>WESTLAKE COMMUNITY</small>
      </div>
    </div>
  </section>
  <div class="section-title">
    <h2>探索活动</h2>
    <span>{{ filtered.length }} 场交流，等待你的加入</span>
  </div>
  <nav class="filter-bar" aria-label="筛选活动状态">
    <button
      v-for="name in ['全部活动', '正在进行', '未开始', '已结束']"
      :key="name"
      :class="{ active: status === name }"
      @click="status = name"
    >
      {{ name }}</button
    ><span class="muted">按会议时间排序</span>
  </nav>
  <div class="grid">
    <article
      v-for="(event, index) in filtered"
      :key="event.id"
      class="event-card"
    >
      <div :class="['art', 'tone-' + (index % 3)]">
        <span class="art-mark"
          >{{ String(index + 1).padStart(2, "0") }} / FORUM</span
        >
        <div>
          WESTLAKE · CONNECT<br /><strong>{{
            event.title.replace(/【.*?】/g, "")
          }}</strong>
        </div>
      </div>
      <div class="card-body">
        <span class="tag">{{
          new Date(event.ends) <= new Date()
            ? "已结束"
            : new Date(event.starts) > new Date()
              ? "未开始"
              : "正在进行"
        }}</span>
        <h2>{{ event.title }}</h2>
        <p class="event-meta">◷ {{ time(event.starts) }}</p>
        <p class="event-meta">⌖ {{ event.place }}</p>
        <p class="muted">
          {{ event.views }} 次浏览 · 最多 {{ event.capacity }} 人参与
        </p>
        <button class="primary" @click="open(event.id)">查看活动详情</button>
      </div>
    </article>
  </div>
  <p v-if="!filtered.length" class="empty">没有符合条件的活动。</p>
</template>

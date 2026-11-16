<script setup>
import { ref, watch, inject, onBeforeUnmount } from "vue";
import { api, time } from "../api";

const props = defineProps({ eventId: Number, refreshKey: Number });
const emit = defineEmits(["add"]);
const { busy, page } = inject("app");
const rows = ref([]),
  loading = ref(false),
  error = ref("");
let requestId = 0;

async function refresh() {
  const current = ++requestId;
  loading.value = true;
  error.value = "";
  try {
    const result = await api(`/events/${props.eventId}/recommend`);
    if (current === requestId) rows.value = result;
  } catch (e) {
    if (current === requestId) {
      rows.value = [];
      error.value = e.message;
    }
  } finally {
    if (current === requestId) loading.value = false;
  }
}

watch([() => props.eventId, () => props.refreshKey], refresh, {
  immediate: true,
});
onBeforeUnmount(() => requestId++);
</script>

<template>
  <section class="section-block recommendations" aria-label="为你推荐">
    <div class="recommendation-heading">
      <div>
        <h2>为你推荐</h2>
        <p class="muted">
          根据兴趣标签与收藏热度推荐，优先展示无时间冲突的议程，最多展示 6 项。
        </p>
      </div>
      <button :disabled="loading || busy" @click="refresh">
        {{ loading ? "正在加载…" : "刷新推荐" }}
      </button>
    </div>
    <p v-if="error" role="alert">推荐加载失败：{{ error }}，请刷新重试。</p>
    <p v-else-if="loading" role="status">正在更新推荐议程…</p>
    <template v-else>
      <article v-for="row in rows" :key="row.id" class="recommendation-row">
        <div class="recommendation-body">
          <h3>{{ row.title }}</h3>
          <p>{{ time(row.starts) }} 至 {{ time(row.ends) }}</p>
          <p class="muted">{{ row.speaker }} · {{ row.room }}</p>
          <p class="recommendation-reason">{{ row.reason }}</p>
          <div v-if="row.conflicts.length" class="recommendation-conflict">
            <strong>与个人日程时间冲突</strong>
            <p v-for="conflict in row.conflicts" :key="conflict.id">
              {{ conflict.event_title }} · {{ conflict.title }}（{{
                time(conflict.starts)
              }}
              至 {{ time(conflict.ends) }}）
            </p>
            <p>请先到个人主页调整日程，再刷新推荐。</p>
          </div>
        </div>
        <button
          :disabled="busy || row.conflicts.length > 0"
          @click="emit('add', row.id)"
        >
          {{ row.conflicts.length ? "时间冲突" : "加入日程" }}
        </button>
      </article>
      <p v-if="!rows.length" class="empty">
        暂无可推荐的议程。未结束的议程可能已全部加入个人日程。
      </p>
    </template>
    <p class="muted">你可以在个人主页修改兴趣标签、查看和调整个人日程。</p>
    <button @click="page = 'mine'">前往个人主页</button>
  </section>
</template>

<style scoped>
.recommendation-heading,
.recommendation-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18px;
}
.recommendation-heading h2 {
  margin-bottom: 8px;
}
.recommendation-heading > button,
.recommendation-row > button {
  flex-shrink: 0;
}
.recommendation-row {
  border-bottom: 1px solid #dceae6;
  padding: 20px 0;
}
.recommendation-body {
  min-width: 0;
  overflow-wrap: anywhere;
}
.recommendation-body p {
  margin: 6px 0;
}
.recommendation-reason {
  color: #247c7a;
}
.recommendation-conflict {
  margin-top: 12px;
  color: #914737;
  font-size: 14px;
}
.recommendations > .muted {
  margin-top: 20px;
}
@media (max-width: 600px) {
  .recommendation-heading,
  .recommendation-row {
    flex-direction: column;
    gap: 8px;
  }
}
</style>

<script setup>
// 按当前权限展示后台入口；扫码、统计组件在进入时才加载。
import { computed, inject, ref, defineAsyncComponent } from "vue";
import EventAdmin from "./EventAdmin.vue";
import ReviewPanel from "./ReviewPanel.vue";
import ExhibitReview from "./ExhibitReview.vue";
import RequestPanel from "./RequestPanel.vue";
const AttendanceAdmin = defineAsyncComponent(
  () => import("./AttendanceAdmin.vue"),
);
const StatsPanel = defineAsyncComponent(() => import("./StatsPanel.vue"));
import PollPanel from "./PollPanel.vue";
import AccessPanel from "./AccessPanel.vue";
const { can, user } = inject("app");
const tabs = computed(() =>
  [
    {
      id: "exhibitReview",
      label: "展商会议与互动审核",
      show: true,
      view: ExhibitReview,
    },
    { id: "requests", label: "办会申请审核", show: true, view: RequestPanel },
    {
      id: "events",
      label: "活动与内容",
      show: can("event") || can("content") || can("exhibit"),
      view: EventAdmin,
    },
    { id: "review", label: "内容审核", show: can("review"), view: ReviewPanel },
    {
      id: "checkin",
      label: "签到发布与结果",
      show: can("checkin"),
      view: AttendanceAdmin,
    },
    { id: "polls", label: "会议投票", show: true, view: PollPanel },
    { id: "stats", label: "运营看板", show: can("stats"), view: StatsPanel },
    {
      id: "access",
      label: "用户与权限",
      show: user.value?.role === "ADMIN",
      view: AccessPanel,
    },
  ].filter((t) => t.show),
);
const tab = ref("events");
const current = computed(
  () => tabs.value.find((t) => t.id === tab.value) || tabs.value[0],
);
</script>
<template>
  <span class="eyebrow">MANAGEMENT CENTER</span>
  <h1>管理员控制中心</h1>
  <p class="muted">
    统一负责办会资料、活动维护、报名审核、内容审核、用户权限与运营数据。
  </p>
  <div class="admin-layout">
    <aside class="admin-nav">
      <div class="admin-nav-title">工作空间 <small>WORKSPACE</small></div>
      <div class="tabs">
        <button
          v-for="item in tabs"
          :key="item.id"
          :class="{ active: current?.id === item.id }"
          @click="tab = item.id"
        >
          {{ item.label }}
        </button>
      </div>
    </aside>
    <div class="admin-content">
      <component :is="current.view" v-if="current" :admin="true" />
    </div>
  </div>
  <p v-if="!current" class="empty">当前账号未分配管理权限，请联系管理员。</p>
</template>

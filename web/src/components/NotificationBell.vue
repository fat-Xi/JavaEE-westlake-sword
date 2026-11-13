<script setup>
import { ref, onMounted, onBeforeUnmount } from "vue";
import { api, time } from "../api";
const rows = ref([]),
  open = ref(false),
  error = ref("");
let timer;
async function load() {
  try {
    rows.value = await api("/notes");
    error.value = "";
  } catch (e) {
    error.value = e.message;
  }
}
async function toggle() {
  open.value = !open.value;
  if (open.value) await load();
}
async function mark() {
  try {
    await api("/notes/read", "POST");
    await load();
  } catch (e) {
    error.value = e.message;
  }
}
onMounted(() => {
  load();
  timer = setInterval(load, 15000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>
<template>
  <div class="notification-wrap">
    <button
      class="notification-bell"
      aria-label="消息通知"
      :aria-expanded="open"
      @click="toggle"
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.7"
        aria-hidden="true"
      >
        <path
          d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"
        /></svg
      ><span v-if="rows.some((n) => !n.seen)" class="notification-count">{{
        Math.min(99, rows.filter((n) => !n.seen).length)
      }}</span>
    </button>
    <section v-if="open" class="notification-menu">
      <div class="notification-title">
        <strong>消息通知</strong><button @click="mark">全部已读</button
        ><button aria-label="关闭通知" @click="open = false">✕</button>
      </div>
      <p v-if="error">{{ error }}</p>
      <p v-if="!rows.length && !error">暂无消息</p>
      <article v-for="n in rows" :key="n.id" :class="{ unread: !n.seen }">
        <p>{{ n.body }}</p>
        <small>{{
          new Date(n.created).toLocaleString("zh-CN", { hour12: false })
        }}</small>
      </article>
    </section>
  </div>
</template>
<style>
.notification-wrap {
  position: relative;
  margin: 0 20px 0 12px;
  flex-shrink: 0;
}
.notification-bell {
  position: relative !important;
  padding: 7px !important;
  border: 0 !important;
  background: transparent !important;
}
.notification-bell svg {
  width: 22px;
  height: 22px;
  display: block;
}
.notification-count {
  position: absolute;
  right: -4px;
  top: -5px;
  border: 2px solid white;
  border-radius: 15px;
  background: #e36574;
  color: white;
  min-width: 19px;
  font-size: 10px;
  line-height: 15px;
  padding: 0 4px;
}
.notification-menu {
  position: absolute;
  right: 0;
  top: 42px;
  width: 350px;
  max-width: 85vw;
  max-height: 480px;
  overflow: auto;
  background: white;
  border: 1px solid #dce3f0;
  border-radius: 18px;
  box-shadow: 0 12px 35px #26384e18;
  padding: 18px;
  z-index: 100;
  white-space: normal;
}
.notification-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.notification-title strong {
  margin-right: auto;
}
.notification-title button {
  font-size: 11px !important;
  padding: 4px 7px !important;
}
.notification-menu article {
  padding: 13px 0;
  border-bottom: 1px solid #edf0f5;
}
.notification-menu p {
  margin: 4px 0;
  font-size: 13px;
}
.notification-menu small {
  color: #8291a8;
  font-size: 11px;
}
.notification-menu .unread {
  border-left: 3px solid #9880e6;
  padding-left: 10px;
}
.attendee-app header {
  grid-template-columns: 150px 1fr minmax(150px, 270px) 235px;
}
@media (max-width: 800px) {
  .notification-wrap {
    margin: 0 10px 0 0;
  }
  .notification-menu {
    position: fixed;
    right: 12px;
    top: 80px;
  }
  .attendee-app header {
    grid-template-columns: auto 1fr;
  }
}
</style>

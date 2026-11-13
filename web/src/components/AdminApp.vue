<script setup>
import { onMounted, onBeforeUnmount } from "vue";
function receive(e) {
  if (e.origin === location.origin && e.data === "admin-session-ended")
    window.dispatchEvent(new Event("session-ended"));
}
onMounted(() => window.addEventListener("message", receive));
onBeforeUnmount(() => window.removeEventListener("message", receive));
</script>
<template>
  <iframe
    class="admin-frame"
    src="/admin/index.html"
    title="管理员工作台"
  ></iframe>
</template>
<style scoped>
.admin-frame {
  position: fixed;
  inset: 0;
  border: 0;
  width: 100%;
  height: 100dvh;
  background: #f2f6fb;
}
</style>

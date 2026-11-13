<script setup>
// 独立文档保留原型样式作用域，使用同源 Session 与真实接口，不存放演示数据。
import { onMounted, onBeforeUnmount, inject } from "vue";
const { refresh } = inject("app");
function receive(event) {
  if (event.origin !== location.origin) return;
  if (event.data === "exhibitor-session-ended")
    window.dispatchEvent(new Event("session-ended"));
  if (event.data === "exhibitor-profile-updated") refresh();
}
onMounted(() => window.addEventListener("message", receive));
onBeforeUnmount(() => window.removeEventListener("message", receive));
</script>
<template>
  <iframe
    class="exhibitor-frame"
    src="/exhibitor/index.html"
    title="展商工作台"
  ></iframe>
</template>
<style scoped>
.exhibitor-frame {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100dvh;
  border: 0;
  background: #f2f5fa;
}
</style>

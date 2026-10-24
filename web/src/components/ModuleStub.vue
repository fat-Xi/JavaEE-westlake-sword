<script setup>
// 预留模块统一通过后端权限闸门。将来只替换组件内部实现即可。
import { ref, onMounted } from "vue";
import { api } from "../api";
const props = defineProps({ eventId: Number, name: String });
const text = ref("正在检查权限…");
onMounted(async () => {
  try {
    text.value = (
      await api(`/events/${props.eventId}/modules/${props.name}`)
    ).message;
  } catch (error) {
    text.value = error.message;
  }
});
</script>
<template>
  <div class="empty">
    <h2>模块暂未启用</h2>
    <p>{{ text }}</p>
  </div>
</template>

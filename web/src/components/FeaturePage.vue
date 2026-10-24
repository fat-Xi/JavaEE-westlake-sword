<script setup>
// 按本期范围只保留空白模块，交由后续开发者实现。
defineProps({ name: String });
</script>
<template>
  <section
    class="panel blank-feature"
    :aria-label="name === 'topic' ? '热点广场' : 'AI 文档'"
  ></section>
</template>

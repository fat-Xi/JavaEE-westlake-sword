<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from "vue";
const props = defineProps({ slides: { type: Array, default: () => [] } });
const emit = defineEmits(["open"]);
const index = ref(0),
  paused = ref(false);
function move(step) {
  index.value =
    (index.value + step + props.slides.length) % props.slides.length;
}
function position(i) {
  const n = props.slides.length;
  if (i === index.value) return "center";
  if (i === (index.value + 1) % n) return "next";
  if (i === (index.value + n - 1) % n) return "prev";
  return "outside";
}
function choose(i) {
  if (i === index.value) emit("open", props.slides[i]);
  else index.value = i;
}
watch(
  () => props.slides.length,
  () => {
    index.value = 0;
  },
);
let timer;
onMounted(() => {
  timer = setInterval(() => {
    if (
      !paused.value &&
      !document.hidden &&
      window.scrollY < 250 &&
      props.slides.length > 1
    )
      move(1);
  }, 5500);
});
onBeforeUnmount(() => clearInterval(timer));
</script>
<template>
  <section
    v-if="slides.length"
    class="event-carousel"
    aria-label="精选会议"
    @mouseenter="paused = true"
    @mouseleave="paused = false"
    @focusin="paused = true"
    @focusout="paused = false"
  >
    <article
      v-for="(slide, i) in slides"
      :key="slide.id || i"
      class="feature-slide"
      :class="[
        position(i),
        'slide-tone-' + (i % 3),
        { 'signature-slide': slide.signatures },
      ]"
      role="button"
      :tabindex="position(i) === 'outside' ? -1 : 0"
      :aria-label="slide.title"
      :aria-hidden="position(i) === 'outside'"
      @click="choose(i)"
      @keydown.enter="choose(i)"
      @keydown.space.prevent="choose(i)"
    >
      <img
        v-if="slide.poster"
        class="live-poster"
        :src="slide.poster"
        alt="会议海报"
      />
      <template v-if="slide.signatures"
        ><h2>签名墙 · 让相遇留下回响</h2>
        <div class="signature-board">
          <article
            v-for="s in slide.signatures.slice(0, 8)"
            :key="s.id"
            class="signature-note"
          >
            <p :class="'font-' + s.font">{{ s.body }}</p>
            <small>{{ s.nick }}</small>
          </article>
          <p v-if="!slide.signatures.length">期待你的第一份祝福</p>
        </div></template
      >
      <template v-else-if="!slide.imageOnly"
        ><div class="slide-orbit" aria-hidden="true"></div>
        <div class="slide-copy">
          <span class="eyebrow"
            >WESTLAKE SUMMIT / {{ new Date().getFullYear() }}</span
          >
          <h1>
            {{
              slide.action
                ? slide.title
                : [
                    "数字向新\n安全共生",
                    "让 AI 与信任\n在这里相遇",
                    "云上共生\n连接无限可能",
                  ][i % 3]
            }}
          </h1>
          <p>
            {{ slide.action ? slide.subtitle || slide.place : slide.title }}
          </p>
          <span class="badge">{{
            new Date(slide.ends) <= new Date()
              ? "已结束"
              : new Date(slide.starts) > new Date()
                ? "未开始"
                : "进行中"
          }}</span>
          <p>{{ slide.action || "点击中央海报，探索活动 →" }}</p>
        </div></template
      >
    </article>
    <button
      v-if="slides.length > 1"
      class="carousel-arrow left"
      aria-label="上一张"
      @click="move(-1)"
    >
      ‹</button
    ><button
      v-if="slides.length > 1"
      class="carousel-arrow right"
      aria-label="下一张"
      @click="move(1)"
    >
      ›
    </button>
  </section>
  <p v-else class="empty">暂无已发布的会议。</p>
</template>

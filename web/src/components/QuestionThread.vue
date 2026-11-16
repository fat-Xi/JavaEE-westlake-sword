<script setup>
import { inject } from "vue";
import { api } from "../api";
import ThumbIcon from "./ThumbIcon.vue";

const props = defineProps({
  question: Object,
  closed: Boolean,
  reload: Function,
});
const { run, busy } = inject("app");
const reactions = [
  { route: "support", label: "赞同", count: "votes", active: "supported" },
  { route: "dislike", label: "点踩", count: "dislikes", active: "disliked" },
];

function react(item) {
  const q = props.question;
  return run(async () => {
    await api(
      `/questions/${q.id}/${item.route}`,
      q[item.active] ? "DELETE" : "POST",
    );
    await props.reload();
  });
}
</script>

<template>
  <div class="question-thread">
    <div class="actions question-actions">
      <button
        v-for="item in reactions"
        :key="item.route"
        class="question-like"
        type="button"
        :aria-label="`${item.label}问题，${question[item.count] ?? 0} 次${item.label}`"
        :aria-pressed="!!question[item.active]"
        :title="
          question[item.active] ? '撤销' + item.label : item.label + '问题'
        "
        :disabled="busy || (closed && !question[item.active])"
        @click="react(item)"
      >
        <ThumbIcon
          :down="item.route === 'dislike'"
          :filled="question[item.active]"
        />
        <span>{{ question[item.count] ?? 0 }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.question-thread .question-like {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 44px;
  min-height: 40px;
  padding: 0 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #63748b;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.question-like svg {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
.question-thread .question-like:hover:not(:disabled) {
  background: #f1efff;
  color: #6049d8;
}
.question-thread .question-like[aria-pressed="true"] {
  color: #6049d8;
}
.question-like:focus-visible {
  outline: 2px solid #6852e8;
  outline-offset: 2px;
}
</style>

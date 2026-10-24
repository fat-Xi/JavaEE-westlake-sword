<script setup>
import { ref, inject, computed, watch } from "vue";
import { api } from "../api";
import ContentForm from "./ContentForm.vue";
import PostList from "./PostList.vue";
const props = defineProps({ meetingId: Number, type: String });
const { run, busy, message } = inject("app");
const posts = ref([]),
  mine = ref([]),
  formKey = ref(0);
const kind = computed(() => props.type || "COMMENT");
const label = computed(() => (kind.value === "COMMENT" ? "评论" : "签名"));
const publicRows = computed(() =>
  posts.value.filter((p) => p.kind === kind.value),
);
const privateRows = computed(() =>
  mine.value.filter(
    (p) =>
      p.event_id === props.meetingId &&
      p.kind === kind.value &&
      p.status !== "APPROVED",
  ),
);
async function load() {
  if (!props.meetingId) return;
  [posts.value, mine.value] = await Promise.all([
    api(`/events/${props.meetingId}/posts`),
    api("/posts/mine"),
  ]);
}
async function saved() {
  await run(async () => {
    await load();
    formKey.value++;
    message.value = "已提交，审核通过后公开展示；也可在个人主页查看记录。";
  });
}
watch(
  () => props.meetingId,
  () => run(load),
  { immediate: true },
);
</script>
<template>
  <section class="panel">
    <div class="row">
      <div>
        <h2>{{ kind === "COMMENT" ? "会议评论区" : "会议签名墙" }}</h2>
        <p class="muted">
          展示本会议所有参会者已通过审核的{{ label }}，按发表时间从新到旧排列。
        </p>
      </div>
      <button :disabled="busy" @click="run(load)">刷新{{ label }}</button>
    </div>
    <h3>全部{{ label }} · {{ publicRows.length }} 条</h3>
    <PostList :rows="publicRows" />
    <p v-if="!publicRows.length" class="empty">
      暂无公开{{ label }}，欢迎留下第一条记录。
    </p>
    <div v-if="privateRows.length" class="section-block">
      <h3>我的审核中 / 未通过{{ label }}</h3>
      <PostList :rows="privateRows" show-status />
    </div>
    <div class="section-block">
      <h2>{{ kind === "COMMENT" ? "发表评论" : "留下签名" }}</h2>
      <p class="muted">
        提交后可在“个人主页 → 我的{{ label }}”查看内容及审核状态。
      </p>
      <ContentForm
        :key="meetingId + ':' + kind + ':' + formKey"
        :event-id="meetingId"
        :options="[kind]"
        @saved="saved"
      />
    </div>
  </section>
</template>

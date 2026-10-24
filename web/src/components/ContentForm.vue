<script setup>
// 共用内容投稿表单。编辑已有内容同样需要重新送审。
import { ref, inject, watch } from "vue";
import LocalImage from "./LocalImage.vue";
import { api, kinds } from "../api";
const props = defineProps({ eventId: Number, options: Array, initial: Object });
const emit = defineEmits(["saved"]);
const { run, busy, message } = inject("app");
const form = ref({});
watch(
  () => [props.initial, props.eventId],
  () => {
    const old = props.initial;
    form.value = {
      eventId: old ? old.event_id : props.eventId,
      kind: old ? old.kind : props.options[0],
      title: old?.title || "",
      body: old?.body || "",
      url: old?.url || "",
      image: old?.image || "",
      product: old?.product || "",
    };
  },
  { immediate: true },
);
async function save() {
  await run(async () => {
    await api(
      "/posts" + (props.initial ? "/" + props.initial.id : ""),
      props.initial ? "PUT" : "POST",
      form.value,
    );
    message.value = "已提交，审核通过后公开展示";
    emit("saved");
  });
}
</script>
<template>
  <form class="editor" @submit.prevent="save">
    <p v-if="initial" class="content-type">
      内容类型：<strong>{{ kinds[form.kind] }}</strong
      >（修改时不可变更）
    </p>
    <label v-else
      >内容类型<select v-model="form.kind">
        <option v-for="kind in options" :key="kind" :value="kind">
          {{ kinds[kind] }}
        </option>
      </select></label
    >
    <label>标题<input v-model="form.title" required maxlength="120" /></label>
    <label
      >正文<textarea v-model="form.body" required maxlength="10000"></textarea>
    </label>
    <template v-if="!['COMMENT', 'WALL'].includes(form.kind)">
      <LocalImage v-model="form.image" @error="message = $event" />
      <label
        >视频 / 相关页面链接<input
          v-model="form.url"
          type="url"
          placeholder="https://..."
      /></label>
      <label
        >产品或成果概述<input v-model="form.product" maxlength="200"
      /></label>
    </template>
    <button class="primary" :disabled="busy">提交审核</button>
  </form>
</template>

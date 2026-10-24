<script setup>
import { ref, inject, onMounted } from "vue";
import { api, time } from "../api";
const props = defineProps({ eventId: Number });
const { run, busy, message } = inject("app");
const rows = ref([]);
async function load() {
  rows.value = (await api(`/meetings/${props.eventId}`)).talks.map((t) => ({
    ...t,
    liveUrl: t.live_url,
    replayUrl: t.replay_url,
    liveOn: !!t.live_on,
  }));
}
async function save(row) {
  await run(async () => {
    await api(`/talks/${row.id}/media`, "PUT", row);
    message.value = "议程直播与回放设置已保存";
    await load();
  });
}
async function upload(row, event) {
  const file = event.target.files[0];
  if (!file) return;
  await run(async () => {
    const data = new FormData();
    data.append("file", file);
    await api("/talks/" + row.id + "/replay", "POST", data);
    await load();
    message.value = "回放已保存到数据库，议程结束后开放播放";
  });
  event.target.value = "";
}
onMounted(() => run(load));
</script>
<template>
  <section class="panel">
    <h2>议程直播与回放管理</h2>
    <p>
      直播使用供应商的合法嵌入播放地址，需手动开启。回放选择本地 MP4/WebM
      上传，文件保存到数据库，议程结束后展示。
    </p>
    <form
      v-for="row in rows"
      :key="row.id"
      class="section-block"
      @submit.prevent="save(row)"
    >
      <h3>{{ row.title }}</h3>
      <p>{{ time(row.starts) }} 至 {{ time(row.ends) }}</p>
      <div class="form-grid">
        <label
          >直播播放地址<input
            v-model="row.liveUrl"
            type="url"
            maxlength="500" /></label
        ><label
          >上传本地回放（MP4/WebM，20MB以内）<input
            type="file"
            accept="video/mp4,video/webm,.mp4,.webm"
            @change="upload(row, $event)"
        /></label>
      </div>
      <p class="muted">
        {{
          row.replayUrl
            ? "已配置回放；上传新文件将替换当前回放。"
            : "暂未上传回放。"
        }}
      </p>
      <div class="checks">
        <label
          ><input v-model="row.liveOn" type="checkbox" />开启此议程直播</label
        >
      </div>
      <button :disabled="busy">保存直播设置</button>
    </form>
    <p v-if="!rows.length">
      先添加议程，再配置直播与回放。新增议程后请重新进入当前活动。
    </p>
  </section>
</template>

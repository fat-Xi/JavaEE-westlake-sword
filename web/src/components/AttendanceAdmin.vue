<script setup>
import { ref, inject, onMounted, computed } from "vue";
import QRCode from "qrcode";
import { api, time } from "../api";
const { run, busy, message } = inject("app");
const events = ref([]),
  eventId = ref(0),
  talks = ref([]),
  sessions = ref([]),
  result = ref(null),
  selected = ref(null);
const form = ref({ talkId: 0, starts: "", ends: "", enabled: true });
const currentTalk = computed(() =>
  talks.value.find((t) => t.id === form.value.talkId),
);
function progress(t) {
  return Date.now() < new Date(t.starts).getTime()
    ? "未开始"
    : Date.now() >= new Date(t.ends).getTime()
      ? "已结束"
      : "正在进行";
}
function chooseTalk() {
  const t = currentTalk.value;
  if (t) {
    form.value.starts = t.starts;
    form.value.ends = t.ends;
  }
}
async function load() {
  if (!eventId.value) return;
  talks.value = (await api(`/meetings/${eventId.value}`)).talks;
  sessions.value = await api(`/attendance?eventId=${eventId.value}`);
  result.value = null;
  selected.value = null;
  for (const row of sessions.value)
    row.qr = await QRCode.toDataURL(
      `${location.origin}/?event=${eventId.value}&attendance=${row.id}&code=${row.code}`,
      { width: 240 },
    );
  form.value = {
    talkId: talks.value[0]?.id || 0,
    starts: talks.value[0]?.starts || "",
    ends: talks.value[0]?.ends || "",
    enabled: true,
  };
}
function edit(row) {
  form.value = {
    talkId: row.talk_id,
    starts: row.starts,
    ends: row.ends,
    enabled: !!row.enabled,
  };
}
async function save() {
  await run(async () => {
    await api("/attendance", "POST", form.value);
    await load();
    message.value = "签到场次已发布，可下载二维码";
  });
}
async function show(row) {
  await run(async () => {
    selected.value = row;
    result.value = await api(`/attendance/${row.id}/results`);
  });
}
onMounted(() =>
  run(async () => {
    events.value = await api("/admin/events");
    eventId.value = events.value[0]?.id || 0;
    await load();
  }),
);
</script>
<template>
  <section class="panel">
    <h2>发布议程签到</h2>
    <p>
      管理员设置签到窗口并发布入口二维码；参会者打开入口后出示自己的凭证，由工作人员核销，入口二维码本身不能冒充个人凭证。
    </p>
    <label
      >会议<select v-model="eventId" @change="run(load)">
        <option v-for="e in events" :key="e.id" :value="e.id">
          {{ e.title }}
        </option>
      </select></label
    >
    <form v-if="talks.length" @submit.prevent="save">
      <div class="form-grid">
        <label
          >议程<select v-model="form.talkId" @change="chooseTalk">
            <option v-for="t in talks" :key="t.id" :value="t.id">
              {{ t.title }} · {{ progress(t) }}
            </option>
          </select></label
        ><label
          >发布状态<select v-model="form.enabled">
            <option :value="true">开放签到</option>
            <option :value="false">关闭签到</option>
          </select></label
        ><label
          >签到开始<input
            v-model="form.starts"
            type="datetime-local"
            step="1"
            required /></label
        ><label
          >签到结束<input
            v-model="form.ends"
            type="datetime-local"
            step="1"
            required
        /></label>
      </div>
      <div v-if="currentTalk" class="schedule-hint">
        <span class="tag">{{ progress(currentTalk) }}</span>
        <strong>{{ currentTalk.title }}</strong>
        <p>
          议程时间：{{ time(currentTalk.starts) }} —
          {{ time(currentTalk.ends) }}
        </p>
        <p>会场：{{ currentTalk.room }} · 嘉宾：{{ currentTalk.speaker }}</p>
      </div>
      <p>
        可提前发布签到。签到窗口最早为议程开始前 2
        小时，最晚到议程结束；这不是发布操作的时间限制。
      </p>
      <button class="primary" :disabled="busy">保存并发布签到</button>
    </form>
    <p v-else>该会议暂无议程，请先添加。</p>
    <div class="section-block">
      <h2>已发布场次与二维码</h2>
      <article v-for="row in sessions" :key="row.id" class="row">
        <img class="qr-image" :src="row.qr" alt="管理员发布的签到入口二维码" />
        <div>
          <h3>{{ row.title }}</h3>
          <p>{{ time(row.starts) }} 至 {{ time(row.ends) }}</p>
          <p>
            {{ row.enabled ? "已开放" : "已关闭" }} · 已签到 {{ row.total }} 人
          </p>
          <div class="actions">
            <a :href="row.qr" :download="row.title + '-签到入口.png'"
              >下载二维码图片</a
            ><button @click="edit(row)">修改时间与状态</button
            ><button @click="show(row)">签到结果看板</button>
          </div>
        </div>
      </article>
      <p v-if="!sessions.length">尚未发布签到场次。</p>
    </div>
    <div v-if="result" class="section-block">
      <h2>{{ selected.title }} · 签到结果</h2>
      <p>
        已签到 {{ result.rows.length }} / 当前有效参会者
        {{ result.eligible }} 人
      </p>
      <div v-for="row in result.rows" :key="row.name" class="row">
        <span>{{ row.nick }} · {{ row.real_name }}（{{ row.name }}）</span
        ><span>{{ time(row.checked) }} · 核销：{{ row.staff }}</span>
      </div>
      <p v-if="!result.rows.length">暂无签到记录</p>
    </div>
  </section>
</template>

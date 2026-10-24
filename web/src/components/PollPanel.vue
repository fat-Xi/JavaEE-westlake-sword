<script setup>
import { ref, inject, onMounted } from "vue";
import { api, time } from "../api";
const props = defineProps({ eventId: Number, admin: Boolean });
const { run, message, busy } = inject("app");
const rows = ref([]),
  events = ref([]),
  selected = ref(props.eventId || 0),
  choices = ref({});
const form = ref({ title: "", options: "", starts: "", ends: "" });
async function load() {
  rows.value = selected.value
    ? await api("/events/" + selected.value + "/polls")
    : [];
}
function state(row) {
  if (!row.enabled) return "已关闭";
  if (Date.now() < new Date(row.starts).getTime()) return "未开始";
  return Date.now() >= new Date(row.ends).getTime() ? "已结束" : "进行中";
}
async function save() {
  await run(async () => {
    await api("/polls", "POST", {
      ...form.value,
      eventId: selected.value,
      options: form.value.options
        .split("\n")
        .map((s) => s.trim())
        .filter(Boolean),
    });
    form.value.title = "";
    form.value.options = "";
    await load();
    message.value = "投票已发布";
  });
}
async function vote(row) {
  await run(async () => {
    await api("/polls/" + row.id + "/vote", "POST", {
      optionId: choices.value[row.id],
    });
    await load();
    message.value = "投票成功";
  });
}
async function toggle(row) {
  await run(async () => {
    await api("/polls/" + row.id, "PUT", { enabled: !row.enabled });
    await load();
  });
}
function total(row) {
  return row.options.reduce((n, o) => n + o.total, 0);
}
onMounted(() =>
  run(async () => {
    if (props.admin) {
      events.value = await api("/admin/events");
      selected.value = events.value[0]?.id || 0;
    }
    await load();
  }),
);
</script>
<template>
  <section :class="admin ? 'panel' : 'poll-section'">
    <h2>{{ admin ? "投票发布与结果" : "议程投票" }}</h2>
    <p class="muted">
      管理员设置主题与选项，报名通过的参会者每题可投一票，提交后不可修改。
    </p>
    <template v-if="admin">
      <label
        >所属会议<select v-model="selected" @change="run(load)">
          <option v-for="e in events" :key="e.id" :value="e.id">
            {{ e.title }}
          </option>
        </select></label
      >
      <form v-if="selected" class="editor" @submit.prevent="save">
        <label
          >投票主题<input v-model="form.title" required maxlength="120"
        /></label>
        <label
          >选项（每行一个，2—8个）<textarea
            v-model="form.options"
            required
            placeholder="云安全实践&#10;数据安全治理"
          ></textarea>
        </label>
        <div class="form-grid">
          <label
            >开始时间<input
              v-model="form.starts"
              type="datetime-local"
              required /></label
          ><label
            >结束时间<input v-model="form.ends" type="datetime-local" required
          /></label>
        </div>
        <button class="primary" :disabled="busy">发布投票</button>
      </form>
    </template>
    <article v-for="row in rows" :key="row.id" class="section-block poll-card">
      <span class="tag">{{ state(row) }}</span>
      <h3>{{ row.title }}</h3>
      <p class="muted">
        {{ time(row.starts) }} — {{ time(row.ends) }} · {{ total(row) }} 人参与
      </p>
      <label v-for="option in row.options" :key="option.id" class="poll-option">
        <input
          v-if="!admin"
          v-model="choices[row.id]"
          type="radio"
          :name="'poll-' + row.id"
          :value="option.id"
          :disabled="!!row.choice.length || state(row) !== '进行中'"
        />
        <span
          >{{ option.label }}
          <strong v-if="row.choice.includes(option.id)">
            · 我的选择</strong
          ></span
        ><span>{{ option.total }} 票</span>
        <progress :value="option.total" :max="total(row) || 1"></progress>
      </label>
      <button v-if="admin" :disabled="busy" @click="toggle(row)">
        {{ row.enabled ? "关闭投票" : "开放投票" }}
      </button>
      <p v-else-if="row.choice.length" class="status-ok">已投票，感谢参与。</p>
      <button
        v-else
        class="primary"
        :disabled="busy || !choices[row.id] || state(row) !== '进行中'"
        @click="vote(row)"
      >
        提交投票
      </button>
    </article>
    <p v-if="!rows.length" class="empty">管理员尚未发布投票。</p>
  </section>
</template>

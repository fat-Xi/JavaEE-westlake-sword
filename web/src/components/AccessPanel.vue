<script setup>
// 仅管理员可用。角色和权限保存后以数据库为准，避免只隐藏按钮。
import { inject, onMounted, ref } from "vue";
import { api, roles, time } from "../api";
const { run, busy, user, message } = inject("app");
const users = ref([]),
  logs = ref([]),
  perms = ref({}),
  reasons = ref({});
async function review(row, pass) {
  await run(async () => {
    await api(`/admin/users/${row.id}/review`, "POST", {
      pass,
      reason: reasons.value[row.id] || "",
    });
    await load();
    message.value = "身份审核完成";
  });
}
const codes = {
  event: "活动和报名管理",
  content: "资讯成果与资料",
  exhibit: "展商内容发布",
  request: "办会申请与附件",
  review: "内容与提问审核",
  checkin: "签到核销",
  stats: "数据看板",
};
const allowed = {
  STAFF: ["checkin"],
  EXHIBITOR: ["exhibit", "request"],
  USER: [],
};
async function load() {
  const [a, b, c] = await Promise.all([
    api("/admin/users"),
    api("/admin/perms"),
    api("/admin/logs"),
  ]);
  users.value = a;
  logs.value = c;
  for (const role of Object.keys(roles))
    perms.value[role] = b.filter((p) => p.role === role).map((p) => p.code);
}
async function role(row) {
  await run(async () => {
    await api(`/admin/users/${row.id}/role`, "PUT", { role: row.role });
    await load();
    message.value = "用户角色已修改，后台权限即时生效";
  });
}
async function save(key) {
  await run(async () => {
    await api(`/admin/perms/${key}`, "PUT", { codes: perms.value[key] });
    await load();
    message.value = "权限已保存，用户刷新页面后菜单更新";
  });
}
onMounted(() => run(load));
</script>
<template>
  <div class="panel function-shell">
    <section class="panel">
      <h2>用户角色管理</h2>
      <div v-for="row in users" :key="row.id" class="row">
        <div>
          <strong>{{ row.nick || row.name }}（账号：{{ row.name }}）</strong>
          <p>
            {{ row.real_name }} · {{ row.gender }} · {{ row.email }} ·
            {{ row.phone }}
          </p>
          <p>
            身份状态：{{ row.status }} {{ row.reason }} · 登录
            {{ row.logins }} 次
          </p>
          <div v-if="row.status === 'PENDING'" class="actions">
            <input
              v-model="reasons[row.id]"
              placeholder="驳回理由"
              maxlength="200"
            /><button :disabled="busy" @click="review(row, true)">
              身份通过</button
            ><button :disabled="busy" @click="review(row, false)">
              身份驳回
            </button>
          </div>
        </div>
        <div class="actions">
          <select v-model="row.role" :disabled="row.id === user.id">
            <option v-for="(label, key) in roles" :key="key" :value="key">
              {{ label }}
            </option></select
          ><button :disabled="busy || row.id === user.id" @click="role(row)">
            保存角色
          </button>
        </div>
      </div>
    </section>
    <section class="panel">
      <h2>角色权限配置</h2>
      <p>
        管理员保留全部管理权限；工作人员只能配置签到权限，展商只能配置申请和展示权限。参会者保留参会功能，不能授予后台权限。
      </p>
      <div v-for="(label, key) in roles" :key="key">
        <form
          v-if="['STAFF', 'EXHIBITOR'].includes(key) && perms[key]"
          class="review-row"
          @submit.prevent="save(key)"
        >
          <h3>{{ label }}</h3>
          <div class="checks">
            <label v-for="code in allowed[key]" :key="code"
              ><input v-model="perms[key]" type="checkbox" :value="code" />{{
                codes[code]
              }}</label
            >
          </div>
          <button :disabled="busy">保存权限</button>
        </form>
      </div>
    </section>
    <section class="panel">
      <h2>最近 200 条操作日志</h2>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>账号</th>
              <th>操作</th>
              <th>对象 / 说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in logs" :key="log.id">
              <td>{{ time(log.created) }}</td>
              <td>{{ log.name }}</td>
              <td>{{ log.action }}</td>
              <td>{{ log.detail }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

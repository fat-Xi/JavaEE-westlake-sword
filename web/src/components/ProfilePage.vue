<script setup>
import { inject, ref, onMounted, computed } from "vue";
import { api, roles, states, time } from "../api";
import ProfileFields from "./ProfileFields.vue";
import PostList from "./PostList.vue";
const { user, refresh, run, busy, message, open } = inject("app");
const profile = ref(null),
  tab = ref("profile"),
  joins = ref([]),
  subs = ref([]),
  plans = ref([]),
  notes = ref([]),
  posts = ref([]);
const records = computed(() =>
  posts.value.filter(
    (p) => p.kind === (tab.value === "comments" ? "COMMENT" : "WALL"),
  ),
);
const password = ref({ oldPassword: "", password: "" });
async function load() {
  profile.value = await api("/profile");
  profile.value.avatar ||= "";
  notes.value = await api("/notes");
  if (user.value.role === "USER" && user.value.status === "APPROVED") {
    [joins.value, subs.value, plans.value, posts.value] = await Promise.all([
      api("/joins"),
      api("/my/subs"),
      api("/my/plans"),
      api("/posts/mine"),
    ]);
  }
}
async function save() {
  await run(async () => {
    await api("/profile", "PUT", profile.value);
    await refresh();
    await load();
    message.value = "个人资料已保存";
  });
}
async function act(path, method) {
  await run(async () => {
    await api(path, method);
    await load();
  });
}
async function changePassword() {
  await run(async () => {
    await api("/password", "PUT", password.value);
    window.dispatchEvent(new Event("session-ended"));
    message.value = "密码已修改，请重新登录";
  });
}
onMounted(() => run(load));
</script>
<template>
  <section v-if="profile" class="panel personal-page">
    <div class="profile-photo">
      <img
        class="avatar large"
        :src="profile.avatar || '/avatar.svg'"
        alt="个人头像"
      />
      <div>
        <h1>{{ roles[user.role] }}个人主页</h1>
        <p>{{ user.nick }} · {{ states[user.status] }}</p>
      </div>
    </div>
    <!-- 审核提示直接绑定用户状态，避免异步加载清空全局消息后消失。 -->
    <p v-if="user.status === 'PENDING'" class="notice" role="status">
      账号已注册，身份资料正在等待管理员审核。审核通过前仅能查看和完善个人资料，暂不能报名或进入会议服务。
    </p>
    <p v-else-if="user.status === 'REJECTED'" class="notice" role="status">
      身份审核未通过：{{
        user.reason || "请补充资料"
      }}。修改个人资料并保存后可重新提交审核。
    </p>
    <button
      @click="
        run(async () => {
          await refresh();
          await load();
        })
      "
    >
      刷新资料与审核状态
    </button>
    <div class="tabs">
      <button
        v-for="[key, label] in [
          ['profile', '个人信息'],
          ['notes', '消息通知'],
          ...(user.role === 'USER' && user.status === 'APPROVED'
            ? [
                ['joins', '我的报名'],
                ['subs', '我的订阅'],
                ['plans', '个人日程'],
                ['comments', '我的评论'],
                ['wall', '我的签名'],
              ]
            : []),
        ]"
        :key="key"
        :class="{ active: tab === key }"
        @click="tab = key"
      >
        {{ label }}
      </button>
    </div>
    <template v-if="tab === 'profile'">
      <p>
        账号：{{ user.name }} · 身份：{{
          roles[user.role]
        }}。账号不可修改，身份及审核状态由管理员维护。
      </p>
      <form @submit.prevent="save">
        <ProfileFields :model="profile" @error="message = $event" /><button
          class="primary"
          :disabled="busy"
        >
          保存个人信息
        </button>
      </form>
      <div class="section-block">
        <h2>修改密码</h2>
        <form @submit.prevent="changePassword">
          <div class="form-grid">
            <label
              >原密码<input
                v-model="password.oldPassword"
                type="password"
                required
                autocomplete="current-password" /></label
            ><label
              >新密码<input
                v-model="password.password"
                type="password"
                required
                minlength="8"
                maxlength="50"
                autocomplete="new-password"
            /></label>
          </div>
          <button :disabled="busy">修改并重新登录</button>
        </form>
      </div>
    </template>
    <template v-if="tab === 'notes'"
      ><button @click="act('/notes/read', 'POST')">全部已读</button>
      <div v-for="note in notes" :key="note.id" class="row">
        <span>{{ note.seen ? "已读" : "未读" }} · {{ note.body }}</span
        ><small>{{ time(note.created) }}</small>
      </div>
      <p v-if="!notes.length" class="empty">暂无消息</p></template
    >
    <template v-if="tab === 'joins'"
      ><div v-for="row in joins" :key="row.id" class="row">
        <div>
          <h3>{{ row.title }}</h3>
          <p>{{ states[row.status] }} {{ row.reason }}</p>
        </div>
        <div class="actions">
          <button @click="open(row.event_id)">查看会议</button
          ><button
            v-if="['PENDING', 'APPROVED'].includes(row.status)"
            @click="act(`/events/${row.event_id}/join`, 'DELETE')"
          >
            取消报名
          </button>
        </div>
      </div>
      <p v-if="!joins.length" class="empty">暂无报名</p></template
    >
    <template v-if="tab === 'subs'"
      ><div v-for="row in subs" :key="row.id" class="row">
        <div>
          <h3>{{ row.title }}</h3>
          <p>
            {{ time(row.starts) }} ·
            {{ row.status === "OPEN" ? "已开放" : "会议已关闭" }}
          </p>
        </div>
        <div class="actions">
          <button v-if="row.status === 'OPEN'" @click="open(row.id)">
            查看会议</button
          ><button @click="act(`/events/${row.id}/sub`, 'DELETE')">
            取消订阅
          </button>
        </div>
      </div>
      <p v-if="!subs.length" class="empty">暂无订阅记录</p></template
    >
    <template v-if="tab === 'plans'"
      ><p>跨会议日程自动检测时间冲突；前往会议议程添加安排。</p>
      <div v-for="row in plans" :key="row.id" class="row">
        <div>
          <h3>{{ row.title }}</h3>
          <p>{{ row.event_title }} · {{ row.room }} · {{ time(row.starts) }}</p>
        </div>
        <button @click="act(`/talks/${row.id}/plan`, 'DELETE')">
          移除日程
        </button>
      </div>
      <p v-if="!plans.length" class="empty">尚未添加个人日程</p></template
    >
    <template v-if="tab === 'comments' || tab === 'wall'">
      <div class="row">
        <h2>
          {{ tab === "comments" ? "我的评论" : "我的签名" }} ·
          {{ records.length }} 条
        </h2>
        <button :disabled="busy" @click="run(load)">刷新发表记录</button>
      </div>
      <p class="muted">
        这里保留你在各个会议发表的记录，包括已通过、待审核和已驳回的内容。
      </p>
      <PostList :rows="records" show-meeting show-status @open="open" />
      <p v-if="!records.length" class="empty">
        尚无发表记录，请在已通过报名的会议内提交。
      </p>
    </template>
  </section>
</template>

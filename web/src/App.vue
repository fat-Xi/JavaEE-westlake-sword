<script setup>
import {
  ref,
  watch,
  nextTick,
  computed,
  provide,
  onMounted,
  onBeforeUnmount,
  defineAsyncComponent,
} from "vue";
import { api, roles } from "./api";
import AdminApp from "./components/AdminApp.vue";
import NotificationBell from "./components/NotificationBell.vue";
import ExhibitorApp from "./components/ExhibitorApp.vue";
import AiPage from "./components/AiPage.vue";
import EventList from "./components/EventList.vue";
import EventDetail from "./components/EventDetail.vue";
import ProfilePage from "./components/ProfilePage.vue";
import ProfileFields from "./components/ProfileFields.vue";
import AdminPanel from "./components/AdminPanel.vue";
import ExhibitorPanel from "./components/ExhibitorPanel.vue";
import MeetingPanel from "./components/MeetingPanel.vue";
import FeaturePage from "./components/FeaturePage.vue";
const CheckinPanel = defineAsyncComponent(
  () => import("./components/CheckinPanel.vue"),
);
const search = ref("");
const user = ref(null),
  page = ref("login"),
  eventId = ref(null),
  message = ref(""),
  busy = ref(false),
  ready = ref(false);
const mode = ref("login"),
  form = ref({
    name: "",
    password: "",
    role: "USER",
    nick: "",
    realName: "",
    gender: "未设置",
    email: "",
    phone: "",
    company: "",
    tags: "",
    avatar: "",
  });
const detailTab = ref("intro"),
  focusPost = ref(null);
// 页面切换回顶部，聊天发送仅滚动聊天区，不滚动整页。
watch(page, async () => {
  await nextTick();
  window.scrollTo({ top: 0, behavior: "instant" });
});
let pending = 0;
// 四个角色的页面树独立，不用同一参会主页叠加后台按钮。
const menus = {
  ADMIN: [["admin", "管理中心"]],
  EXHIBITOR: [["exhibit", "展商工作台"]],
  STAFF: [
    ["checkin", "签到工作台"],
    ["meetings", "会议执行信息"],
  ],
  USER: [
    ["events", "发现活动"],
    ["topic", "热点广场"],
    ["ai", "AI 助手"],
  ],
};
const nav = computed(() =>
  user.value
    ? [
        ...(user.value.status === "APPROVED" ? menus[user.value.role] : []),
        ...(["USER", "STAFF"].includes(user.value.role)
          ? []
          : [["mine", "个人主页"]]),
      ]
    : [],
);
function home() {
  page.value =
    user.value && user.value.status !== "APPROVED"
      ? "mine"
      : nav.value[0]?.[0] || "login";
}
function can(code) {
  return user.value?.role === "ADMIN" || user.value?.perms?.includes(code);
}
function open(id, target = "intro", post = null) {
  if (user.value?.role === "USER" && user.value.status === "APPROVED") {
    search.value = "";
    eventId.value = id;
    detailTab.value = target;
    focusPost.value = post;
    page.value = "detail";
  }
}
async function refresh() {
  user.value = await api("/me");
}
async function run(work) {
  pending++;
  busy.value = true;
  message.value = "";
  try {
    await work();
  } catch (e) {
    message.value = e.message;
  } finally {
    pending--;
    busy.value = pending > 0;
  }
}
async function login() {
  await run(async () => {
    if (mode.value === "register") {
      await api("/register", "POST", form.value);
      mode.value = "login";
      message.value = "注册成功，请登录个人主页等待管理员审核身份。";
    } else {
      await api("/login", "POST", form.value);
      await refresh();
      form.value.password = "";
      home();
      if (new URLSearchParams(location.search).get("page") === "mine")
        page.value = "mine";
      const id = Number(new URLSearchParams(location.search).get("event"));
      if (user.value.role === "USER" && Number.isInteger(id) && id > 0)
        open(
          id,
          new URLSearchParams(location.search).get("tab") ||
            (new URLSearchParams(location.search).has("talk")
              ? "talks"
              : "intro"),
        );
      if (
        user.value.role === "USER" &&
        user.value.status === "APPROVED" &&
        new URLSearchParams(location.search).has("attendance")
      )
        page.value = "detail";
    }
  });
}
// 会话过期和主动退出统一回登录页，避免停留在失效的角色页面。
function resetSession() {
  user.value = null;
  eventId.value = null;
  page.value = "login";
  mode.value = "login";
  form.value.password = "";
}
async function logout() {
  await run(async () => {
    await api("/logout", "POST");
    resetSession();
    history.replaceState(null, "", location.pathname);
  });
}
provide("app", {
  user,
  page,
  eventId,
  message,
  busy,
  run,
  open,
  can,
  refresh,
  search,
  detailTab,
  focusPost,
});
onMounted(async () => {
  window.addEventListener("session-ended", resetSession);
  try {
    await refresh();
    home();
    if (new URLSearchParams(location.search).get("page") === "mine")
      page.value = "mine";
    const shared = new URLSearchParams(location.search);
    if (
      user.value.role === "USER" &&
      user.value.status === "APPROVED" &&
      shared.has("event")
    ) {
      open(
        Number(shared.get("event")),
        shared.get("tab") || (shared.has("talk") ? "talks" : "intro"),
      );
      if (shared.has("attendance")) page.value = "detail";
    }
  } catch {
    page.value = "login";
  } finally {
    ready.value = true;
  }
});
onBeforeUnmount(() =>
  window.removeEventListener("session-ended", resetSession),
);
watch(
  () => [user.value?.id, page.value, eventId.value],
  () => {
    if (user.value && !["ADMIN", "EXHIBITOR"].includes(user.value.role))
      api("/visits", "POST", { page: page.value }).catch(() => {});
  },
);
</script>
<template>
  <AdminApp v-if="ready && user?.role === 'ADMIN'" />
  <ExhibitorApp
    v-else-if="
      ready && user?.role === 'EXHIBITOR' && user.status === 'APPROVED'
    "
  />
  <div
    v-else
    :class="[
      ['USER', 'STAFF'].includes(user?.role)
        ? 'attendee-app'
        : !user
          ? 'auth-app'
          : '',
      { 'staff-app': user?.role === 'STAFF' },
      { 'profile-view': page === 'mine' },
    ]"
  >
    <header>
      <button class="brand" @click="home">
        <img class="brand-logo" src="/logo.png" alt="会享 Logo" />会享
        <small>西湖论剑</small>
      </button>
      <nav v-if="user">
        <button
          v-for="[key, label] in nav"
          :key="key"
          :class="{ active: page === key }"
          @click="page = key"
        >
          {{ label }}
        </button>
      </nav>
      <input
        v-if="user?.role === 'USER' && user.status === 'APPROVED'"
        v-model="search"
        class="header-search"
        placeholder="搜索会议、地点或关键词"
        aria-label="搜索活动"
        @input="page = 'events'"
      />
      <div v-if="user" class="account">
        <NotificationBell
          v-if="user.role === 'USER' || user.role === 'EXHIBITOR'"
        />
        <button
          v-if="user.role !== 'STAFF'"
          class="account-link"
          @click="page = 'mine'"
        >
          <img
            class="avatar"
            :src="user.avatar || '/avatar.svg'"
            alt="个人头像"
          />{{ user.nick
          }}{{ user.role === "USER" ? "" : " · " + roles[user.role] }}</button
        ><button :disabled="busy" @click="logout">退出</button>
      </div>
      <span v-else class="muted">连接会议，让协作更简单</span>
    </header>
    <main>
      <div v-if="message" class="notice" role="status">
        {{ message }}<button @click="message = ''">关闭</button>
      </div>
      <p v-if="!ready" class="empty">正在检查登录状态…</p>
      <section v-else-if="!user" class="login-layout">
        <div class="login-intro">
          <img src="/logo.png" alt="西湖论剑标志" /><span class="eyebrow"
            >WESTLAKE · CONNECT</span
          >
          <h1>一场会议，<br />连接每一种可能。</h1>
          <p>参会交流 · 展商协作 · 会议管理 · 现场服务</p>
          <p class="muted">登录后进入与你的角色对应的工作空间。</p>
        </div>
        <div class="login">
          <span class="eyebrow">WELCOME TO WESTLAKE</span>
          <h1>{{ mode === "login" ? "欢迎回来" : "注册账号" }}</h1>
          <form @submit.prevent="login">
            <template v-if="mode === 'register'"
              ><label
                >注册身份<select v-model="form.role">
                  <option value="USER">参会者</option>
                  <option value="EXHIBITOR">展商</option>
                </select></label
              ><ProfileFields :model="form" @error="message = $event"
            /></template>
            <label
              >账号<input
                v-model="form.name"
                autocomplete="username"
                required
                maxlength="30" /></label
            ><label
              >密码<input
                v-model="form.password"
                type="password"
                :autocomplete="
                  mode === 'login' ? 'current-password' : 'new-password'
                "
                required
                minlength="8"
                maxlength="50" /></label
            ><button class="primary" :disabled="busy">
              {{ mode === "login" ? "登录" : "注册" }}
            </button>
          </form>
          <button
            class="link"
            @click="mode = mode === 'login' ? 'register' : 'login'"
          >
            {{ mode === "login" ? "还没有账号？去注册" : "已有账号，去登录" }}
          </button>
          <p class="muted">
            展商和参会者可自行注册；身份由管理员审核，工作人员由管理员分配。
          </p>
        </div>
      </section>
      <p
        v-else-if="user.role === 'STAFF' && user.status !== 'APPROVED'"
        class="empty"
      >
        工作人员权限尚未开通，请联系管理员。
      </p>
      <ProfilePage v-else-if="page === 'mine' && user.role !== 'STAFF'" />
      <template v-else-if="user.role === 'USER'"
        ><EventList v-if="page === 'events'" /><EventDetail
          v-else-if="page === 'detail'"
          :key="eventId"
          :event-id="eventId" /><AiPage v-else-if="page === 'ai'" /><FeaturePage
          v-else-if="['topic', 'ai'].includes(page)"
          :key="page"
          :name="page"
      /></template>
      <AdminPanel v-else-if="user.role === 'ADMIN'" />
      <ExhibitorPanel v-else-if="user.role === 'EXHIBITOR'" />
      <template v-else-if="user.role === 'STAFF'"
        ><CheckinPanel v-if="page === 'checkin' && can('checkin')" />
        <p v-else-if="page === 'checkin'" class="empty">
          管理员尚未授权签到核销，请联系管理员。
        </p>
        <MeetingPanel v-else
      /></template>
    </main>
    <footer>
      会享 · 西湖论剑活动平台<br />课程设计演示站，演示活动及媒体不代表真实大会安排
    </footer>
  </div>
</template>

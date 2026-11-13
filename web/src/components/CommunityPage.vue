<script setup>
import { ref, inject, computed, watch, nextTick, onBeforeUnmount } from "vue";
import { api, states, postedTime } from "../api";
const props = defineProps({ meetingId: Number, type: String });
const emit = defineEmits(["updated"]);
const { run, busy, message, focusPost, user } = inject("app");
const feed = ref(null);
const flashId = ref(null);
let flashTimer;
let disposed = false;
const posts = ref([]),
  mine = ref([]),
  talks = ref([]),
  body = ref(""),
  tags = ref([]),
  rating = ref(""),
  anonymous = ref(false),
  font = ref("sans");
const wall = computed(() => props.type === "WALL");
const publicRows = computed(() =>
  posts.value.filter((p) => p.kind === props.type),
);
const privateRows = computed(() =>
  mine.value.filter(
    (p) =>
      p.event_id === props.meetingId &&
      p.kind === props.type &&
      p.status !== "APPROVED",
  ),
);
async function load() {
  [posts.value, mine.value, talks.value] = await Promise.all([
    api(`/events/${props.meetingId}/posts`),
    api("/posts/mine"),
    api(`/events/${props.meetingId}/talks`),
  ]);
  await locatePost();
}

// 签名画布与记录列表分开标识，定位始终使用带作者和时间的原文卡片。
async function locatePost() {
  await nextTick();
  if (disposed || !focusPost.value || !feed.value) return;
  const id = Number(focusPost.value);
  const target = feed.value.querySelector(`[id="post-${id}"]`);
  if (!target) return;
  // 等栏目入场动画结束再测量，避免 transform 使坐标发生偏移。
  const animations = [];
  for (let node = feed.value; node; node = node.parentElement) {
    animations.push(
      ...node
        .getAnimations()
        .filter((a) => a.effect?.getTiming().iterations !== Infinity),
    );
  }
  await Promise.allSettled(animations.map((a) => a.finished));
  if (disposed || !target.isConnected || Number(focusPost.value) !== id) return;
  const box = feed.value.getBoundingClientRect();
  const rect = target.getBoundingClientRect();
  feed.value.scrollTo({
    top:
      feed.value.scrollTop +
      rect.top -
      box.top -
      Math.max(16, (feed.value.clientHeight - rect.height) / 2),
    behavior: "instant",
  });
  // 同时定位外层页面，给顶部固定导航和会议栏目留出空间。
  const nav = document.querySelector(".meeting-nav");
  const navStyle = nav && getComputedStyle(nav);
  const offset = nav
    ? (parseFloat(navStyle.top) || 0) + nav.offsetHeight + 16
    : 100;
  window.scrollTo({
    top: window.scrollY + feed.value.getBoundingClientRect().top - offset,
    behavior: "instant",
  });
  clearTimeout(flashTimer);
  flashId.value = null;
  await nextTick();
  flashId.value = id;
  focusPost.value = null;
  flashTimer = setTimeout(() => {
    flashId.value = null;
  }, 1800);
}
watch(focusPost, () => {
  if (focusPost.value) locatePost();
});
onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(flashTimer);
});

function toggleTag(tag) {
  tags.value = tags.value.includes(tag)
    ? tags.value.filter((t) => t !== tag)
    : [...tags.value, tag];
}
async function remove(p) {
  if (!confirm("删除这条记录？")) return;
  await run(async () => {
    await api(`/community/${p.id}`, "DELETE");
    await load();
    emit("updated");
  });
}
async function send() {
  if (!body.value.trim()) return;
  await run(async () => {
    await api("/community", "POST", {
      eventId: props.meetingId,
      kind: props.type,
      body: body.value.trim(),
      tags: [...tags.value, rating.value].filter(Boolean).join(" · "),
      anonymous: anonymous.value,
      font: font.value,
    });
    body.value = "";
    tags.value = [];
    rating.value = "";
    await load();
    emit("updated");
    message.value = "提交成功，审核通过后公开展示；个人主页可查看审核状态。";
  });
}
watch(
  () => [props.meetingId, props.type],
  () => {
    body.value = "";
    tags.value = [];
    rating.value = "";
    run(load);
  },
  { immediate: true },
);
</script>
<template>
  <section class="interaction-panel">
    <div ref="feed" class="interaction-feed">
      <div class="row">
        <h2>{{ wall ? "让相遇，留下回响。" : "在交流中，让观点碰撞。" }}</h2>
        <small v-if="wall">与顶部轮播同步</small>
      </div>
      <div v-if="wall" class="signature-board">
        <article
          v-for="p in publicRows"
          :id="'signature-note-' + p.id"
          :key="p.id"
          :class="['signature-note', 'font-' + p.font]"
        >
          <p>{{ p.body }}</p>
        </article>
        <p v-if="!publicRows.length" class="empty">留下你的第一份祝福</p>
      </div>
      <template v-if="true"
        ><article
          v-for="p in publicRows"
          :id="'post-' + p.id"
          :key="p.id"
          class="comment-card"
          :class="{ 'record-flash': flashId === p.id }"
        >
          <img
            class="avatar"
            :src="p.avatar || '/avatar.svg'"
            alt="评论人头像"
          />
          <div>
            <button
              v-if="p.user_id === user.id || mine.some((m) => m.id === p.id)"
              class="delete-post"
              @click="remove(p)"
            >
              删除</button
            ><strong>{{ p.nick || p.name }}</strong
            ><small>{{ postedTime(p.created) }}</small>
            <p>{{ p.body }}</p>
            <span v-if="p.product" class="badge">{{ p.product }}</span>
          </div>
        </article>
        <p v-if="!publicRows.length" class="empty">
          暂无公开评论，欢迎交流。
        </p></template
      >
      <div v-if="privateRows.length" class="pending-posts">
        <h3>我的待公开记录</h3>
        <article
          v-for="p in privateRows"
          :id="'post-' + p.id"
          :key="p.id"
          :class="{ 'record-flash': flashId === p.id }"
        >
          <p>{{ p.body }}</p>
          <small>{{ states[p.status] }} · {{ p.reason || "审核后展示" }}</small>
        </article>
      </div>
    </div>
    <form class="community-composer" @submit.prevent="send">
      <div v-if="wall" class="signature-tools">
        <label><input v-model="anonymous" type="checkbox" />匿名发布</label
        ><select v-model="font" aria-label="签名字体">
          <option value="sans">简洁黑体</option>
          <option value="serif">典雅宋体</option>
          <option value="hand">手写风格</option>
        </select>
      </div>
      <div v-else class="comment-tags">
        <button
          v-for="t in talks"
          :key="t.id"
          type="button"
          :class="{ active: tags.includes(t.title) }"
          @click="toggleTag(t.title)"
        >
          {{ t.title }}</button
        ><button
          v-for="label in ['超赞 👍', '很有启发 ✨', '期待更多 👀']"
          :key="label"
          type="button"
          :class="{ active: rating === label }"
          @click="rating = rating === label ? '' : label"
        >
          {{ label }}
        </button>
      </div>
      <div class="compact-composer">
        <textarea
          v-model="body"
          required
          :maxlength="wall ? 120 : 2000"
          :aria-label="wall ? '签名内容' : '评论内容'"
          :placeholder="wall ? '留下你的签名或祝福…' : '分享你的参会感受…'"
        ></textarea
        ><button class="primary" :disabled="busy || !body.trim()">
          {{ wall ? "留下签名 ↗" : "发布评论 ↗" }}
        </button>
      </div>
      <small>{{
        wall
          ? "白板仅展示签名内容，不显示昵称"
          : "议程可多选 · 评价词单选 · 审核后公开"
      }}</small>
    </form>
  </section>
</template>

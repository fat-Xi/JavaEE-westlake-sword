<script setup>
import { ref, inject, computed, watch, nextTick, onBeforeUnmount } from "vue";
import { api, states, postedTime } from "../api";
import ThumbIcon from "./ThumbIcon.vue";
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
  font = ref("sans"),
  replyTarget = ref(null),
  replyBody = ref("");
const reportDialog = ref(null);
const reportTarget = ref(null);
const reportReason = ref("");
const reportError = ref("");
const wall = computed(() => props.type === "WALL");
const publicRows = computed(() =>
  posts.value.filter((p) => p.kind === props.type),
);
const commentsById = computed(
  () => new Map(publicRows.value.map((p) => [p.id, p])),
);
const feedRows = computed(() => {
  if (wall.value) return publicRows.value;
  const roots = new Map();
  const replies = new Map();
  // Render the thread under its oldest visible ancestor with a single indent.
  for (const p of [...publicRows.value].reverse()) {
    const parentRoot = roots.get(p.reply_to_id);
    roots.set(p.id, parentRoot ?? p.id);
    if (parentRoot === undefined) continue;
    if (!replies.has(parentRoot)) replies.set(parentRoot, []);
    replies.get(parentRoot).push(p);
  }
  return publicRows.value
    .filter((p) => roots.get(p.id) === p.id)
    .flatMap((p) => [p, ...(replies.get(p.id) ?? [])]);
});
const reactions = [
  { value: "LIKE", label: "点赞", count: "likes" },
  { value: "DISLIKE", label: "点踩", count: "dislikes" },
];
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
  if (
    !confirm(wall.value ? "删除这条签名？" : "删除这条评论及它下面的所有回复？")
  )
    return;
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
    message.value = wall.value ? "签名已提交，审核通过后公开。" : "评论已发布";
  });
}
async function react(p, value) {
  await run(async () => {
    await api(`/community/${p.id}/reaction`, "PUT", {
      value: p.reaction === value ? "NONE" : value,
    });
    posts.value = await api(`/events/${props.meetingId}/posts`);
  });
}
function closeReply() {
  replyTarget.value = null;
  replyBody.value = "";
}
async function openReply(p) {
  if (replyTarget.value !== p.id) replyBody.value = "";
  replyTarget.value = p.id;
  await nextTick();
  feed.value?.querySelector(".comment-reply textarea")?.focus();
}
async function sendReply(p) {
  const content = replyBody.value.trim();
  if (!content || busy.value) return;
  await run(async () => {
    await api("/community", "POST", {
      eventId: props.meetingId,
      kind: "COMMENT",
      body: content,
      replyTo: p.id,
    });
    closeReply();
    await load();
    message.value = "回复已发布";
  });
}
function openReport(p) {
  reportTarget.value = p.id;
  reportReason.value = "";
  reportError.value = "";
  reportDialog.value.showModal();
}
function sendReport() {
  if (!reportReason.value.trim() || busy.value) return;
  return run(async () => {
    const id = reportTarget.value;
    try {
      await api(`/community/${id}/reports`, "POST", {
        reason: reportReason.value.trim(),
      });
    } catch (error) {
      reportError.value = error.message;
      return;
    }
    posts.value = posts.value.map((p) =>
      p.id === id ? { ...p, reported: true } : p,
    );
    reportDialog.value.close();
    message.value = "举报已提交，管理员将在审核后处理。";
  });
}
watch(
  () => [props.meetingId, props.type],
  () => {
    body.value = "";
    tags.value = [];
    rating.value = "";
    closeReply();
    if (reportDialog.value?.open) reportDialog.value.close();
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
          class="signature-note"
        >
          <p :class="'font-' + p.font">{{ p.body }}</p>
        </article>
        <p v-if="!publicRows.length" class="empty">留下你的第一份祝福</p>
      </div>
      <template v-if="true"
        ><article
          v-for="p in feedRows"
          :id="'post-' + p.id"
          :key="p.id"
          class="comment-card"
          :class="{
            'record-flash': flashId === p.id,
            'comment-reply-card': !wall && commentsById.has(p.reply_to_id),
          }"
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
            <div
              v-if="p.reply_to_id || p.title === '评论回复'"
              class="reply-context"
            >
              <template v-if="commentsById.has(p.reply_to_id)">
                回复
                {{
                  commentsById.get(p.reply_to_id).nick ||
                  commentsById.get(p.reply_to_id).name
                }}
              </template>
              <template v-else>原评论已不可见</template>
            </div>
            <p :class="wall ? 'font-' + p.font : undefined">{{ p.body }}</p>
            <span v-if="p.product" class="badge">{{ p.product }}</span>
            <div v-if="!wall" class="comment-actions">
              <button
                v-for="item in reactions"
                :key="item.value"
                class="comment-action"
                type="button"
                :aria-label="`${item.label}评论，${p[item.count]} 次`"
                :aria-pressed="p.reaction === item.value"
                :title="
                  p.reaction === item.value ? '撤销' + item.label : item.label
                "
                :disabled="busy"
                @click="react(p, item.value)"
              >
                <ThumbIcon
                  :down="item.value === 'DISLIKE'"
                  :filled="p.reaction === item.value"
                />
                <span>{{ p[item.count] }}</span>
              </button>
              <button
                type="button"
                class="comment-action"
                :disabled="busy"
                :aria-expanded="replyTarget === p.id"
                @click="openReply(p)"
              >
                回复
              </button>
              <button
                v-if="p.user_id !== user.id"
                type="button"
                class="comment-action"
                :disabled="busy || !!p.reported"
                @click="openReport(p)"
              >
                {{ p.reported ? "已举报" : "举报" }}
              </button>
            </div>
            <form
              v-if="replyTarget === p.id"
              class="comment-reply"
              @submit.prevent="sendReply(p)"
            >
              <textarea
                v-model="replyBody"
                required
                maxlength="2000"
                :aria-label="`回复 ${p.nick || p.name}`"
                :placeholder="`回复 ${p.nick || p.name}…`"
              ></textarea>
              <div class="comment-reply-actions">
                <button
                  type="button"
                  class="comment-action"
                  :disabled="busy"
                  @click="closeReply"
                >
                  取消
                </button>
                <button class="primary" :disabled="busy || !replyBody.trim()">
                  提交回复
                </button>
              </div>
            </form>
          </div>
        </article>
        <p v-if="!publicRows.length" class="empty">
          暂无公开评论，欢迎交流。
        </p></template
      >
      <div v-if="wall && privateRows.length" class="pending-posts">
        <h3>我的未公开记录</h3>
        <article
          v-for="p in privateRows"
          :id="'post-' + p.id"
          :key="p.id"
          :class="{ 'record-flash': flashId === p.id }"
        >
          <p :class="'font-' + p.font">{{ p.body }}</p>
          <small>
            {{ p.reply_to_id || p.title === "评论回复" ? "回复 · " : ""
            }}{{ states[p.status] }} ·
            {{ p.reason || "审核后展示" }}
          </small>
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
          <option value="bold">醒目粗黑</option>
          <option value="round">俏皮圆体</option>
          <option value="brush">毛笔行书</option>
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
          :class="wall ? ['signature-input', 'font-' + font] : undefined"
          required
          :maxlength="wall ? 120 : 2000"
          :aria-label="wall ? '签名内容' : '评论内容'"
          :placeholder="wall ? '留下你的姓名或祝福…' : '分享你的参会感受…'"
        ></textarea
        ><button class="primary" :disabled="busy || !body.trim()">
          {{ wall ? "留下签名 ↗" : "发布评论 ↗" }}
        </button>
      </div>
      <small>{{
        wall
          ? "白板仅展示签名内容，不显示昵称"
          : "议程可多选 · 评价词单选 · 评论发布后公开"
      }}</small>
    </form>
    <dialog
      v-if="!wall"
      ref="reportDialog"
      class="comment-report-dialog"
      aria-labelledby="comment-report-title"
    >
      <form @submit.prevent="sendReport">
        <h3 id="comment-report-title">举报评论</h3>
        <label for="comment-report-reason">举报理由</label>
        <textarea
          id="comment-report-reason"
          v-model="reportReason"
          autofocus
          required
          maxlength="200"
          placeholder="请说明这条评论为何违规"
        ></textarea>
        <p v-if="reportError" role="alert">{{ reportError }}</p>
        <div class="comment-reply-actions">
          <button type="button" :disabled="busy" @click="reportDialog.close()">
            取消
          </button>
          <button class="primary" :disabled="busy || !reportReason.trim()">
            提交举报
          </button>
        </div>
      </form>
    </dialog>
  </section>
</template>

<style scoped>
.signature-input::placeholder {
  font: inherit;
  font-synthesis: inherit;
}
.comment-reply-card {
  margin-inline-start: clamp(12px, 4vw, 48px);
  border-inline-start: 2px solid #e7e4f6;
  padding-inline-start: clamp(10px, 2vw, 16px);
}
.reply-context {
  margin-top: 4px;
  color: #697991;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.comment-actions,
.comment-reply-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.comment-card .comment-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-width: 44px;
  min-height: 40px;
  padding: 0 7px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #63748b;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.comment-action svg {
  width: 17px;
  height: 17px;
}
.comment-card .comment-action:hover:not(:disabled),
.comment-card .comment-action[aria-pressed="true"] {
  background: #f1efff;
  color: #6049d8;
}
.comment-action:focus-visible {
  outline: 2px solid #6852e8;
  outline-offset: 2px;
}
.comment-reply {
  margin-top: 8px;
}
.comment-reply textarea {
  min-height: 60px;
}
.comment-reply-actions {
  justify-content: flex-end;
}
.comment-report-dialog {
  width: min(440px, calc(100% - 32px));
  border: 1px solid #e7e4f6;
  border-radius: 16px;
  padding: 22px;
}
.comment-report-dialog::backdrop {
  background: #20203966;
}
.comment-report-dialog form {
  display: grid;
  gap: 12px;
}
.comment-report-dialog textarea {
  width: 100%;
  min-height: 90px;
}
</style>

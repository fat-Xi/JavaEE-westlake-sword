<script setup>
// 未通过报名者仅加载基本信息；受限资料和视频由后端再次核验。
import {
  ref,
  computed,
  inject,
  onMounted,
  onBeforeUnmount,
  nextTick,
  watch,
} from "vue";
import { api, states, time, kinds } from "../api";
import EventCarousel from "./EventCarousel.vue";
import CommunityPage from "./CommunityPage.vue";
import TicketPage from "./TicketPage.vue";
import AttendeePoll from "./AttendeePoll.vue";
import MediaPlayer from "./MediaPlayer.vue";
const props = defineProps({ eventId: Number });
const { run, busy, message, page, detailTab, user } = inject("app");
const event = ref(null),
  join = ref(null),
  talks = ref([]),
  posts = ref([]),
  files = ref([]),
  media = ref([]),
  questions = ref([]),
  recommendations = ref([]),
  votes = ref([]),
  likes = ref([]);
const subscribed = ref(false),
  tab = ref(detailTab.value || "intro"),
  filter = ref(new URLSearchParams(location.search).get("kind") || "EXHIBIT"),
  question = ref(""),
  mapId = ref(0),
  now = ref(Date.now());
const heroArea = ref(null);
const meetingFeatures = computed(() => {
  try {
    return JSON.parse(event.value?.features || "null");
  } catch {
    return null;
  }
});
// 保留浏览器原生滚动，避免滚轮拦截和定时锁定造成停顿。
const mediaOpen = ref(null),
  postOpen = ref(null);
const questionTalk = ref(0);
const questionFilter = ref("全部提问");
const visibleQuestions = computed(() =>
  questions.value.filter(
    (q) => questionFilter.value !== "我的提问" || q.user_id === user.value.id,
  ),
);
const ticketOpen = ref(false),
  contentArea = ref(null),
  plans = ref([]);
function meetingPosters(row) {
  try {
    const images = JSON.parse(row.posters || "[]");
    if (Array.isArray(images) && images.length) return images;
  } catch {}
  return [row.poster || ""];
}
const slides = computed(() =>
  event.value
    ? [
        ...meetingPosters(event.value).map((poster, i) => ({
          ...event.value,
          id: "poster-" + i,
          poster,
          imageOnly: !!poster,
          action: "查看活动详情 ↓",
        })),
        ...(approved.value
          ? [
              {
                title: "会议议程",
                subtitle: "安排你的参会之旅",
                target: "talks",
                action: "查看议程 ↓",
              },
              {
                subtitle: "把相聚的心意留在这里",
                signatures:
                  stage(event.value) === "未开始"
                    ? undefined
                    : posts.value.filter((p) => p.kind === "WALL"),
                title:
                  stage(event.value) === "未开始"
                    ? "期待与你相聚"
                    : "会享签名墙",
              },
            ]
          : []),
      ]
    : [],
);
const tabs = computed(() => {
  const base = [["intro", "活动详情"]];
  if (!approved.value) return base;
  return [
    ...base,
    ["talks", "会议议程"],
    ["media", "直播和回放"],
    ["content", "展商内容"],
    ["questions", "会议提问"],
    ["polls", "议程投票"],
    ["comments", "评论区"],
    ["wall", "签名墙"],
  ];
});
watch(tab, async () => {
  await nextTick();
  contentArea.value?.scrollIntoView({ behavior: "smooth", block: "start" });
});
function showContent(slide) {
  tab.value = slide.target || "intro";
  contentArea.value?.scrollIntoView({ behavior: "smooth", block: "start" });
}
async function submitQuestion() {
  if (!question.value.trim()) return;
  await run(async () => {
    await api("/questions", "POST", {
      eventId: props.eventId,
      body: question.value.trim(),
      talkId: questionTalk.value || null,
    });
    question.value = "";
    await load();
    message.value = "问题已提交，审核通过后展示";
  });
}
const approved = computed(() => join.value?.status === "APPROVED");
const stage = (row) =>
  now.value < new Date(row.starts).getTime()
    ? "未开始"
    : now.value >= new Date(row.ends).getTime()
      ? "已结束"
      : "正在进行";
const current = computed(() =>
  talks.value.filter((t) => stage(t) === "正在进行"),
);
async function load() {
  event.value = await api(`/events/${props.eventId}`);
  join.value = (await api("/joins")).find((j) => j.event_id === props.eventId);
  subscribed.value = (await api("/subs")).includes(props.eventId);
  talks.value = approved.value
    ? await api(`/events/${props.eventId}/talks`)
    : [];
  plans.value = approved.value
    ? await api(`/events/${props.eventId}/plans`)
    : [];
  if (approved.value) {
    [
      posts.value,
      files.value,
      media.value,
      questions.value,
      recommendations.value,
      votes.value,
      likes.value,
    ] = await Promise.all([
      api(`/events/${props.eventId}/posts`),
      api(`/events/${props.eventId}/files`),
      api(`/events/${props.eventId}/media`),
      api(`/events/${props.eventId}/questions`),
      api(`/events/${props.eventId}/recommend`),
      api("/votes"),
      api("/likes"),
    ]);
  } else {
    posts.value = [];
    files.value = [];
    media.value = [];
    questions.value = [];
    recommendations.value = [];
    tab.value = "intro";
  }
}
async function act(path, method = "POST", data) {
  await run(async () => {
    await api(path, method, data);
    await load();
    message.value = "操作成功";
  });
}
async function apply() {
  const source = new URLSearchParams(location.search).get("channel");
  await act(`/events/${props.eventId}/join`, "POST", {
    channel: source === "share" ? "share" : "direct",
  });
}
async function share(talk) {
  await run(async () => {
    await navigator.clipboard.writeText(
      `${location.origin}/?event=${props.eventId}&talk=${talk.id}&channel=share`,
    );
    message.value = "议程链接已复制到剪贴板";
  });
}
let timer;
let ticks = 0;
onMounted(() =>
  run(async () => {
    await load();
    if (!tabs.value.some(([key]) => key === tab.value)) tab.value = "intro";
    await nextTick();
    if (/^#(?:post|question)-[0-9]+$/.test(location.hash))
      document
        .getElementById(location.hash.slice(1))
        ?.scrollIntoView({ block: "center" });
    if (new URLSearchParams(location.search).get("talk") && approved.value)
      tab.value = "talks";
    if (
      new URLSearchParams(location.search).has("attendance") &&
      approved.value
    )
      ticketOpen.value = true;
    timer = setInterval(() => {
      now.value = Date.now();
      // 定期更新直播开关与回放地址，不要求观众手动刷新。
      if (approved.value && ++ticks % 15 === 0) {
        api(`/events/${props.eventId}/media`)
          .then((data) => (media.value = data))
          .catch(() => {});
      }
    }, 1000);
  }),
);
onBeforeUnmount(() => clearInterval(timer));
</script>
<template>
  <section v-if="event" class="meeting-page">
    <div ref="heroArea" class="meeting-stage">
      <EventCarousel :slides="slides" @open="showContent" />
    </div>
    <div class="meeting-nav">
      <nav aria-label="会议功能">
        <button
          v-for="[key, label] in tabs"
          :key="key"
          :class="{ active: tab === key }"
          @click="
            tab = key;
            mapId = 0;
          "
        >
          {{ label }}
        </button>
      </nav>
      <div class="meeting-actions">
        <button
          class="primary"
          :disabled="
            busy ||
            ['PENDING', 'APPROVED'].includes(join?.status) ||
            stage(event) === '已结束'
          "
          @click="apply"
        >
          {{
            join?.status === "APPROVED"
              ? "✓ 已报名"
              : join?.status === "PENDING"
                ? "审核中"
                : "立即报名"
          }}</button
        ><button
          class="check-button"
          :disabled="!approved"
          @click="ticketOpen = true"
        >
          签到
        </button>
      </div>
    </div>
    <div ref="contentArea" class="meeting-content">
      <template v-if="tab === 'intro'"
        ><span class="eyebrow">ABOUT THE SUMMIT</span>
        <h2>{{ event.title }}</h2>
        <p>{{ time(event.starts) }} · {{ event.place }} · {{ stage(event) }}</p>
        <div v-if="meetingFeatures" class="intro-grid">
          <article v-for="f in meetingFeatures" :key="f.title">
            <h3>{{ f.title }}</h3>
            <p>{{ f.body }}</p>
          </article>
        </div>
        <div v-else class="intro-grid">
          <article>
            <h3>聚焦前沿</h3>
            <p>数据安全、AI 治理与云端可信实践。</p>
          </article>
          <article>
            <h3>连接同行</h3>
            <p>与开发者及行业专家交流真实案例。</p>
          </article>
          <article>
            <h3>落地实践</h3>
            <p>议程分享、展商成果和互动体验。</p>
          </article>
        </div>
        <p class="pre">{{ event.content }}</p>
        <p v-if="!approved" class="notice">
          {{
            join?.status === "PENDING" ? "报名正在审核中" : "尚未通过报名"
          }}：当前仅可查看会议详情，其他服务暂未开放。
        </p></template
      >
      <template v-if="tab === 'media' && approved">
        <div class="section-block">
          <span class="eyebrow">LIVE &amp; REPLAY</span>
          <h2>不错过每一个精彩瞬间。</h2>
          <p>按议程展开观看，点击同一议程可收起。</p>
          <article v-for="item in media" :key="item.id" class="section-block">
            <button
              class="media-toggle"
              @click="mediaOpen = mediaOpen === item.id ? null : item.id"
            >
              {{
                stage(item) === "已结束"
                  ? "回放"
                  : stage(item) === "正在进行"
                    ? "● 直播"
                    : "未开始"
              }}　{{ item.title }}　{{ time(item.starts).slice(11) }}　⌄
            </button>
            <div v-if="mediaOpen === item.id">
              <template
                v-if="stage(item) === '正在进行' && stage(event) !== '已结束'"
                ><MediaPlayer
                  v-if="item.live_on && item.live_url"
                  :src="item.live_url"
                  :title="item.title + '直播'"
                />
                <p v-else>直播未开启</p>
                <p v-if="item.live_on && item.had_live">
                  直播结束后将提供回放，请等待管理员发布。
                </p>
                <p v-else>会议未开放直播，暂无直播回放。</p></template
              ><template v-else-if="stage(item) === '已结束'"
                ><MediaPlayer
                  v-if="item.replay_url"
                  :src="item.replay_url"
                  :title="item.title + '回放'"
                />
                <p v-else>
                  {{
                    item.had_live
                      ? "直播已结束，回放待管理员发布"
                      : "会议未开放直播，暂无回放"
                  }}
                </p></template
              >
              <p v-else>议程未开始，直播未开启。</p>
            </div>
          </article>
          <p v-if="!media.length">尚未安排议程，暂无直播与回放。</p>
        </div>
      </template>
      <template v-if="tab === 'talks' && approved"
        ><h2>
          {{
            stage(event) === "未开始"
              ? "提前了解议程，安排参会计划。"
              : "把感兴趣的议程，加入日程。"
          }}
        </h2>
        <p v-if="!talks.length" class="empty">
          议程安排尚未发布，发布后将在这里展示时间、嘉宾和会场。
        </p>
        <article
          v-for="talk in talks"
          :key="talk.id"
          class="prototype-timeline"
        >
          <div>
            <b>{{ time(talk.starts).slice(11) }}</b
            ><br /><small>— {{ time(talk.ends).slice(11) }}</small>
          </div>
          <div>
            <span class="badge">{{ stage(talk) }}</span>
            <h3>{{ talk.title }}</h3>
            <p>{{ talk.room }} · 嘉宾：{{ talk.speaker }} · {{ talk.tags }}</p>
            <div class="actions">
              <button
                @click="
                  act(
                    `/talks/${talk.id}/plan`,
                    plans.some((p) => p.id === talk.id) ? 'DELETE' : 'POST',
                  )
                "
              >
                {{
                  plans.some((p) => p.id === talk.id)
                    ? "✓ 已加入日程"
                    : "＋ 加入日程"
                }}</button
              ><button @click="mapId = mapId === talk.id ? 0 : talk.id">
                地图导航 ↗
              </button>
            </div>
            <div
              v-if="mapId === talk.id"
              class="blank-feature"
              aria-label="地图导航"
            ></div>
          </div></article
      ></template>
      <template v-if="tab === 'content' && approved"
        ><h2>从展台，到真实应用。</h2>
        <nav class="tabs">
          <button
            v-for="[key, label] in [
              ['EXHIBIT', '展商介绍'],
              ['NEWS', '会议资讯'],
              ['RESULT', '成果展示'],
            ]"
            :key="key"
            :class="{ active: filter === key }"
            @click="
              filter = key;
              postOpen = null;
            "
          >
            {{ label }}
          </button>
        </nav>
        <div class="intro-grid">
          <article
            v-for="post in posts.filter((p) => p.kind === filter)"
            :key="post.id"
            class="vendor-card"
          >
            <h3>{{ post.title }}</h3>
            <p>
              {{ post.product || "展商提交并通过审核的介绍、成果及相关资料。" }}
            </p>
            <button @click="postOpen = postOpen === post.id ? null : post.id">
              {{ postOpen === post.id ? "收起资料 ↑" : "查看资料 ↗" }}
            </button>
            <div v-if="postOpen === post.id">
              <img
                v-if="post.image"
                :src="post.image"
                class="post-image"
                alt="展商资料"
              />
              <p class="pre">{{ post.body }}</p>
              <a v-if="post.url" :href="post.url" target="_blank" rel="noopener"
                >下载资料 ↗</a
              >
            </div>
          </article>
        </div>
        <div v-if="files.length" class="section-block">
          <a
            v-for="file in files"
            :key="file.id"
            :href="`/api/files/${file.id}/download`"
            class="file-link"
            >{{ file.name }} ↗</a
          >
        </div>
        <p v-if="!posts.some((p) => p.kind === filter)" class="empty">
          暂无内容
        </p></template
      >
      <section v-if="tab === 'questions' && approved" class="interaction-panel">
        <div class="interaction-feed">
          <div class="row">
            <div>
              <span class="eyebrow">ASK · SHARE · INSPIRE</span>
              <h2>让好问题，被听见。</h2>
              <p>与参展团队交流，把答案带回实践。</p>
            </div>
          </div>
          <div class="actions">
            <button
              v-for="name in ['全部提问', '我的提问']"
              :key="name"
              :class="{ active: questionFilter === name }"
              @click="questionFilter = name"
            >
              {{ name }}
            </button>
          </div>
          <article
            v-for="q in visibleQuestions"
            :key="q.id"
            class="question-card"
            :id="'question-' + q.id"
          >
            <div class="comment-card">
              <img
                class="avatar"
                :src="q.avatar || '/avatar.svg'"
                alt="提问人头像"
              />
              <h3>{{ q.name || "参会者" }}</h3>
              <small>{{ time(q.created) }}</small>
            </div>
            <p>{{ q.body }}</p>
            <small>{{ states[q.status] }} {{ q.reason }}</small>
            <blockquote v-if="q.answer">主办方答复：{{ q.answer }}</blockquote>
          </article>
          <p v-if="!visibleQuestions.length" class="empty">
            暂无提问，欢迎交流。
          </p>
        </div>
        <form
          class="community-composer question-composer"
          @submit.prevent="submitQuestion"
        >
          <select v-model="questionTalk" aria-label="提问议程">
            <option :value="0">全会提问</option>
            <option v-for="talk in talks" :key="talk.id" :value="talk.id">
              {{ talk.title }}
            </option>
          </select>
          <div class="compact-composer">
            <textarea
              v-model="question"
              required
              maxlength="500"
              aria-label="会议问题"
              placeholder="写下你的问题，审核通过后公开展示…"
            ></textarea
            ><button class="primary" :disabled="busy || !question.trim()">
              提交问题 ↗
            </button>
          </div>
        </form>
      </section>
      <AttendeePoll v-if="tab === 'polls' && approved" :event-id="eventId" />
      <CommunityPage
        v-if="tab === 'comments' && approved"
        :meeting-id="eventId"
        type="COMMENT"
      />
      <CommunityPage
        v-if="tab === 'wall' && approved"
        :meeting-id="eventId"
        @updated="run(load)"
        type="WALL"
      />
    </div>
    <Teleport to="body"
      ><div
        v-if="ticketOpen && approved"
        class="attendee-modal"
        @click.self="ticketOpen = false"
        @keydown.esc="ticketOpen = false"
      >
        <section
          class="ticket-dialog"
          role="dialog"
          aria-modal="true"
          aria-label="会议签到"
        >
          <button class="dialog-close" autofocus @click="ticketOpen = false">
            关闭 ×</button
          ><TicketPage :meeting-id="eventId" />
        </section></div
    ></Teleport>
  </section>
</template>

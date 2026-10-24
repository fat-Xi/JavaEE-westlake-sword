<script setup>
// 未通过报名者仅加载基本信息；受限资料和视频由后端再次核验。
import { ref, computed, inject, onMounted, onBeforeUnmount } from "vue";
import { api, states, time, kinds } from "../api";
import CommunityPage from "./CommunityPage.vue";
import TicketPage from "./TicketPage.vue";
import PollPanel from "./PollPanel.vue";
import MediaPlayer from "./MediaPlayer.vue";
const props = defineProps({ eventId: Number });
const { run, busy, message, page } = inject("app");
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
  tab = ref("intro"),
  filter = ref("EXHIBIT"),
  question = ref(""),
  mapId = ref(0),
  now = ref(Date.now());
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
  talks.value = await api(`/events/${props.eventId}/talks`);
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
    if (new URLSearchParams(location.search).get("talk") && approved.value)
      tab.value = "talks";
    if (
      new URLSearchParams(location.search).has("attendance") &&
      approved.value
    )
      tab.value = "attendance";
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
  <section v-if="event" class="panel function-shell">
    <div class="detail-head">
      <span class="tag">{{ stage(event) }}</span>
      <h1>{{ event.title }}</h1>
      <p>
        {{ time(event.starts) }} 至 {{ time(event.ends) }} · {{ event.place }}
      </p>
      <p v-if="stage(event) === '正在进行'">
        当前议程：{{
          current.length
            ? current.map((t) => t.title).join(" / ")
            : "议程间歇，暂无正在进行的议程"
        }}
      </p>
      <div class="actions">
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
            join
              ? states[join.status] +
                (["REJECTED", "CANCELLED"].includes(join.status)
                  ? " · 重新报名"
                  : "")
              : "立即报名"
          }}</button
        ><button
          @click="act(`/events/${eventId}/sub`, subscribed ? 'DELETE' : 'POST')"
        >
          {{ subscribed ? "取消订阅" : "订阅会议" }}
        </button>
      </div>
      <p v-if="join?.reason">{{ join.reason }}</p>
    </div>
    <nav v-if="approved" class="tabs">
      <button
        v-for="[key, label] in [
          ['intro', '会议介绍与视频'],
          ['talks', '会议议程'],
          ['content', '展商与内容'],
          ['questions', '会议提问'],
          ['polls', '议程投票'],
          ['comments', '会议评论'],
          ['wall', '签名墙'],
          ['attendance', '签到服务'],
        ]"
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
    <template v-if="tab === 'intro'"
      ><h2>会议基本信息</h2>
      <p class="pre">{{ event.content }}</p>
      <p>人数上限 {{ event.capacity }} · {{ event.views }} 次浏览</p>
      <p v-if="!approved" class="notice">
        报名审核通过后开放议程服务、直播回放、资料与互动。当前仅显示会议基本信息。
      </p>
      <template v-else
        ><div class="section-block">
          <h2>
            {{ stage(event) === "已结束" ? "议程回放" : "议程直播与回放" }}
          </h2>
          <article v-for="item in media" :key="item.id" class="section-block">
            <h3>{{ item.title }} · {{ stage(item) }}</h3>
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
          </article>
          <p v-if="!media.length">尚未安排议程，暂无直播与回放。</p>
        </div>
        <div class="section-block">
          <h2>会议资料</h2>
          <div v-for="file in files" :key="file.id" class="row">
            <span>{{ file.name }}</span
            ><a :href="`/api/files/${file.id}/download`">下载资料</a>
          </div>
          <p v-if="!files.length">暂无资料</p>
        </div></template
      >
    </template>
    <template v-if="tab === 'talks' && approved"
      ><h2>会议议程</h2>
      <article v-for="talk in talks" :key="talk.id" class="section-block">
        <span class="tag">{{ stage(talk) }}</span>
        <h3>{{ talk.title }}</h3>
        <p>
          {{ time(talk.starts) }} 至 {{ time(talk.ends) }} ·
          {{ talk.speaker }} · {{ talk.room }}
        </p>
        <div class="actions">
          <button @click="act(`/talks/${talk.id}/plan`)">加入个人日程</button
          ><button @click="share(talk)">分享议程</button
          ><button @click="mapId = mapId === talk.id ? 0 : talk.id">
            地图导航
          </button>
        </div>
        <div
          v-if="mapId === talk.id"
          class="blank-feature"
          :aria-label="talk.title + '地图导航预留区域'"
        ></div>
      </article>
      <div class="section-block">
        <h2>为你推荐</h2>
        <div v-for="row in recommendations" :key="row.id" class="row">
          <div>
            {{ row.title }}
            <p class="muted">{{ row.reason }}</p>
          </div>
          <button @click="act(`/talks/${row.id}/plan`)">加入日程</button>
        </div>
        <p>个人日程和冲突管理请前往个人主页。</p>
        <button @click="page = 'mine'">前往个人主页</button>
      </div></template
    >
    <template v-if="tab === 'content' && approved"
      ><nav class="tabs">
        <button
          v-for="key in ['EXHIBIT', 'RESULT', 'NEWS']"
          :key="key"
          :class="{ active: filter === key }"
          @click="filter = key"
        >
          {{ kinds[key] }}
        </button>
      </nav>
      <article
        v-for="post in posts.filter((p) => p.kind === filter)"
        :key="post.id"
        class="section-block"
      >
        <img
          v-if="post.image"
          :src="post.image"
          alt="内容图片"
          class="post-image"
        />
        <h2>{{ post.title }}</h2>
        <p class="pre">{{ post.body }}</p>
        <p>{{ post.product }}</p>
        <p class="muted">发布者 {{ post.name }}</p>
        <a v-if="post.url" :href="post.url" target="_blank" rel="noopener"
          >相关资料</a
        ><button
          @click="
            act(
              `/posts/${post.id}/like`,
              likes.includes(post.id) ? 'DELETE' : 'POST',
            )
          "
        >
          {{ likes.includes(post.id) ? "取消点赞" : "点赞" }} · {{ post.likes }}
        </button>
      </article>
      <p v-if="!posts.some((p) => p.kind === filter)" class="empty">
        暂无内容
      </p></template
    >
    <template v-if="tab === 'questions' && approved"
      ><h2>会议提问</h2>
      <form
        @submit.prevent="act('/questions', 'POST', { eventId, body: question })"
      >
        <label
          >问题<textarea
            v-model="question"
            required
            maxlength="500"
          ></textarea></label
        ><button :disabled="busy">提交问题审核</button>
      </form>
      <article v-for="q in questions" :key="q.id" class="row">
        <div>
          <h3>{{ q.body }}</h3>
          <p>{{ states[q.status] }} {{ q.reason }}</p>
          <p v-if="q.answer">主办方答复：{{ q.answer }}</p>
        </div>
      </article></template
    >
    <PollPanel v-if="tab === 'polls' && approved" :event-id="eventId" />
    <CommunityPage
      v-if="tab === 'comments' && approved"
      :meeting-id="eventId"
      type="COMMENT"
    />
    <CommunityPage
      v-if="tab === 'wall' && approved"
      :meeting-id="eventId"
      type="WALL"
    />
    <TicketPage v-if="tab === 'attendance' && approved" :meeting-id="eventId" />
  </section>
</template>

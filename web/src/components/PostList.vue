<script setup>
// 评论区、签名墙和个人记录统一展示作者与时间，不泄露邮箱、电话等资料。
import { states } from "../api";
defineProps({ rows: Array, showMeeting: Boolean, showStatus: Boolean });
const emit = defineEmits(["open"]);
// 数据库发表时间通过接口返回UTC，统一换算为项目使用的北京时间。
function posted(value) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(date);
}
</script>
<template>
  <div class="post-list">
    <article
      v-for="row in rows"
      :key="row.id"
      class="community-post"
      :data-post-id="row.id"
    >
      <img
        class="avatar post-avatar"
        :src="row.avatar || '/avatar.svg'"
        :alt="(row.nick || row.name || '参会者') + '的头像'"
        @error="$event.target.src = '/avatar.svg'"
      />
      <div class="post-body">
        <div class="post-meta">
          <strong>{{ row.nick || row.name || "参会者" }}</strong>
          <time :datetime="row.created" title="发表时间（北京时间）">{{
            posted(row.created)
          }}</time>
          <span v-if="showStatus" class="tag">{{ states[row.status] }}</span>
        </div>
        <p v-if="showMeeting" class="post-meeting">
          所属会议：{{ row.event_title }}
        </p>
        <h3>{{ row.title }}</h3>
        <p class="pre">{{ row.body }}</p>
        <p v-if="showStatus && row.status === 'PENDING'" class="muted">
          仅本人可见，审核通过后在会议中公开展示。
        </p>
        <p v-if="showStatus && row.status === 'REJECTED'" class="post-reason">
          未公开 · 驳回原因：{{ row.reason || "请联系管理员" }}
        </p>
        <button
          v-if="showMeeting && row.event_status === 'OPEN'"
          @click="emit('open', row.event_id)"
        >
          查看所属会议
        </button>
        <small v-else-if="showMeeting" class="muted"
          >会议已关闭，发表记录仍保留。</small
        >
      </div>
    </article>
  </div>
</template>

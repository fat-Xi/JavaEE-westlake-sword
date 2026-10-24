<script setup>
// 工作人员只读执行信息；管理员可查看关闭会议、全部内容和资料。
import { inject, ref, onMounted } from "vue";
import { api, time, states, kinds } from "../api";
const props = defineProps({ id: Number });
const { run, user } = inject("app");
const events = ref([]),
  selected = ref(props.id || 0),
  detail = ref(null);
async function load() {
  if (selected.value) detail.value = await api(`/meetings/${selected.value}`);
}
onMounted(() =>
  run(async () => {
    if (!props.id) {
      events.value = await api("/meetings");
      selected.value = events.value[0]?.id || 0;
    }
    await load();
  }),
);
</script>
<template>
  <section class="panel">
    <span class="eyebrow">MEETING BRIEF</span>
    <h2>{{ user.role === "ADMIN" ? "会议完整信息" : "会议执行信息" }}</h2>
    <p v-if="user.role === 'STAFF'" class="muted">
      查看会议安排和会场信息；现场核销请使用导航中的“扫码签到”。
    </p>
    <label v-if="!id"
      >选择会议（含已关闭）<select v-model="selected" @change="run(load)">
        <option v-for="item in events" :key="item.id" :value="item.id">
          {{ item.title }} · {{ item.status === "OPEN" ? "开放" : "关闭" }}
        </option>
      </select></label
    >
    <template v-if="detail"
      ><h2>{{ detail.title }}</h2>
      <dl class="info-grid">
        <dt>活动编号</dt>
        <dd>#{{ detail.id }}</dd>
        <dt>地点</dt>
        <dd>{{ detail.place }}</dd>
        <dt>时间</dt>
        <dd>{{ time(detail.starts) }} 至 {{ time(detail.ends) }}</dd>
        <dt>容量 / 状态</dt>
        <dd>
          {{ detail.capacity }} 人 ·
          {{ detail.status === "OPEN" ? "开放" : "关闭" }}
        </dd>
      </dl>
      <p class="pre">{{ detail.content }}</p>
      <h3>完整议程</h3>
      <div v-for="talk in detail.talks" :key="talk.id" class="row">
        <div>
          <strong>{{ talk.title }}</strong>
          <p>{{ talk.speaker }} · {{ talk.room }}</p>
        </div>
        <span>{{ time(talk.starts) }} 至 {{ time(talk.ends) }}</span>
      </div>
      <p v-if="!detail.talks.length">尚未安排议程</p>
      <template v-if="user.role === 'ADMIN'"
        ><h3>会议资料</h3>
        <div v-for="file in detail.files" :key="file.id" class="row">
          <a :href="`/api/files/${file.id}/download`">{{ file.name }}</a
          ><span>{{ Math.ceil(file.size / 1024) }} KB</span>
        </div>
        <p v-if="!detail.files.length">暂无附件</p>
        <h3>活动全部内容（含未通过）</h3>
        <article v-for="post in detail.posts" :key="post.id" class="review-row">
          <span class="tag"
            >{{ kinds[post.kind] }} · {{ states[post.status] }}</span
          >
          <h3>{{ post.title }}</h3>
          <p class="pre">{{ post.body }}</p>
          <p>{{ post.product }}</p>
          <img
            v-if="post.image"
            :src="post.image"
            alt="内容图片"
            class="post-image"
          /><a v-if="post.url" :href="post.url" target="_blank" rel="noopener"
            >媒体 / 相关链接</a
          >
        </article>
        <p v-if="!detail.posts.length">暂无内容</p>
        <h3>办会申请来源</h3>
        <p v-for="r in detail.request" :key="r.id">
          申请 #{{ r.id }} · {{ r.name }} · {{ r.contact }}
        </p>
        <p v-if="!detail.request.length">由管理员直接创建</p></template
      >
    </template>
    <p v-else>暂无会议信息</p>
  </section>
</template>

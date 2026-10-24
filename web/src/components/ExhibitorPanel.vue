<script setup>
// 展商没有报名、扫码或管理菜单，只处理自己的申请和展商内容。
import { ref, inject, onMounted } from "vue";
import { api, states, kinds } from "../api";
import RequestPanel from "./RequestPanel.vue";
import ContentForm from "./ContentForm.vue";
const { run, can } = inject("app");
const postPage = ref("list");
const tab = ref("requests"),
  events = ref([]),
  eventId = ref(0),
  posts = ref([]),
  editing = ref(null);
async function load() {
  events.value = await api("/events");
  eventId.value ||= events.value[0]?.id || 0;
  posts.value = await api("/posts/mine");
}
async function saved() {
  editing.value = null;
  postPage.value = "list";
  await run(load);
}
onMounted(() => run(load));
</script>
<template>
  <div class="detail-head">
    <span class="eyebrow">EXHIBITOR WORKSPACE</span>
    <h1>展商协作中心</h1>
    <p>提出办会需求，提交会议资料，与管理员协同推进活动。</p>
  </div>
  <div class="tabs">
    <button :class="{ active: tab === 'requests' }" @click="tab = 'requests'">
      办会申请与附件</button
    ><button :class="{ active: tab === 'posts' }" @click="tab = 'posts'">
      会议资讯、成果与展商资料
    </button>
  </div>
  <RequestPanel v-if="tab === 'requests' && can('request')" />
  <p v-else-if="tab === 'requests'" class="empty">
    办会申请权限未开放，请联系管理员。
  </p>
  <section v-if="tab === 'posts' && can('exhibit')" class="panel">
    <nav class="tabs">
      <button
        :class="{ active: postPage === 'list' }"
        @click="
          postPage = 'list';
          editing = null;
        "
      >
        我的投稿资料</button
      ><button
        :class="{ active: postPage === 'new' }"
        @click="
          postPage = 'new';
          editing = null;
        "
      >
        提交新资料
      </button>
    </nav>
    <template v-if="postPage === 'new'"
      ><h2>向会议提交资讯、成果或展商介绍</h2>
      <label
        >选择已开放活动<select v-model="eventId">
          <option v-for="event in events" :key="event.id" :value="event.id">
            {{ event.title }}
          </option>
        </select></label
      ><ContentForm
        v-if="eventId"
        :key="eventId"
        :event-id="eventId"
        :options="['EXHIBIT', 'NEWS', 'RESULT']"
        @saved="saved"
      />
    </template>
    <template v-if="postPage === 'edit' && editing"
      ><button
        @click="
          editing = null;
          postPage = 'list';
        "
      >
        返回投稿列表
      </button>
      <h2>修改{{ kinds[editing.kind] }} · {{ editing.title }}</h2>
      <p class="muted">
        会议：{{
          events.find((e) => e.id === editing.event_id)?.title ||
          "#" + editing.event_id
        }}。内容类型保持原记录，修改后需要重新审核。
      </p>
      <ContentForm
        :key="'edit-' + editing.id"
        :initial="editing"
        :options="[editing.kind]"
        @saved="saved"
    /></template>
    <template v-if="postPage === 'list'"
      ><h2>我的投稿资料</h2>
      <div
        v-for="post in posts.filter((p) =>
          ['EXHIBIT', 'NEWS', 'RESULT'].includes(p.kind),
        )"
        :key="post.id"
        class="row"
      >
        <div>
          <strong>{{ post.title }}</strong>
          <p>
            <span class="tag">{{ kinds[post.kind] }}</span>
            {{ states[post.status] }} · {{ post.reason }}
          </p>
        </div>
        <button
          @click="
            editing = post;
            postPage = 'edit';
          "
        >
          修改并重新送审
        </button>
      </div>
    </template>
  </section>
  <p v-else-if="tab === 'posts'" class="empty">
    展商展示权限未开放，请联系管理员。
  </p>
</template>

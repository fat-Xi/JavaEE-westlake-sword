<script setup>
import { ref, inject, onMounted } from "vue";
import { api } from "../api";
const { run, busy, message } = inject("app");
const data = ref({ meetings: [], interactions: [] });
const selected = ref(null);
const reason = ref("");
const labels = { CHECK: "签到", POLL: "投票", ANSWER: "回答" };
async function load() {
  data.value = await api("/exhibit-review");
}
function choose(row, kind) {
  selected.value = { ...row, kind, content: JSON.parse(row.payload) };
  reason.value = "";
}
async function review(pass) {
  await run(async () => {
    await api(
      `/exhibit-review/${selected.value.kind}/${selected.value.id}`,
      "POST",
      { pass, reason: reason.value },
    );
    selected.value = null;
    await load();
    message.value = pass ? "审核通过，已同步发布" : "已驳回";
  });
}
onMounted(() => run(load));
</script>
<template>
  <section class="panel">
    <div class="row">
      <h2>展商会议与互动审核</h2>
      <button @click="run(load)">刷新</button>
    </div>
    <p>
      审批会议会同步发布封面、海报、议程和分类资料；审批互动会开放签到、投票或公开回答。
    </p>
    <template v-if="!selected"
      ><h3>会议申请</h3>
      <article v-for="r in data.meetings" :key="r.id" class="row">
        <div>
          <strong>{{ r.title }}</strong>
          <p>{{ r.name }} · 待审核</p>
        </div>
        <button @click="choose(r, 'meetings')">查看全部资料</button>
      </article>
      <p v-if="!data.meetings.length">暂无待审核会议</p>
      <h3>互动申请</h3>
      <article v-for="r in data.interactions" :key="r.id" class="row">
        <div>
          <strong>{{ labels[r.kind] }} · {{ r.title }}</strong>
          <p>{{ r.name }}</p>
        </div>
        <button @click="choose(r, 'interactions')">查看并审核</button>
      </article>
      <p v-if="!data.interactions.length">暂无待审核互动</p></template
    >
    <template v-else
      ><button @click="selected = null">返回列表</button>
      <h2>{{ selected.title }}</h2>
      <template v-if="selected.kind === 'meetings'"
        ><img
          :src="selected.content.cover"
          alt="会议封面"
          style="max-width: 360px"
        /><img
          v-for="(poster, i) in selected.content.posters || [
            selected.content.poster,
          ]"
          :key="i"
          :src="poster"
          alt="会议海报"
          style="max-width: 360px"
        />
        <p>
          {{ selected.content.place }} · {{ selected.content.starts }} —
          {{ selected.content.ends }} · {{ selected.content.capacity }}人
        </p>
        <p>
          封面标题：{{ selected.content.coverTitle || "自动生成" }} ·
          会议类型：{{ selected.content.meetingType || "自动识别" }}
        </p>
        <p class="pre">{{ selected.content.content }}</p>
        <article v-for="f in selected.content.features" :key="f.title">
          <h3>{{ f.title }}</h3>
          <p>{{ f.body }}</p>
        </article>
        <h3>议程</h3>
        <article
          v-for="t in selected.content.talks"
          :key="t.title"
          class="section-block"
        >
          <strong>{{ t.title }}</strong>
          <p>
            {{ t.starts }} — {{ t.ends }} · {{ t.room }} · {{ t.speaker }} ·
            {{ t.tags }}
          </p>
        </article>
        <h3>分类资料</h3>
        <article
          v-for="m in selected.content.materials"
          :key="m.title"
          class="section-block"
        >
          <h3>{{ m.type }} · {{ m.title }}</h3>
          <p>{{ m.product }}</p>
          <p class="pre">{{ m.body }}</p>
          <img
            v-if="m.image"
            :src="m.image"
            alt="资料配图"
            style="max-width: 360px"
          />
          <p v-for="f in m.files" :key="f.name">
            <a :href="f.data" :download="f.name">{{ f.name }}</a>
          </p>
        </article></template
      >
      <template v-else
        ><p v-if="selected.content.questionText">
          参会者提问：{{ selected.content.questionText }}
        </p>
        <p v-if="selected.content.talkTitle">
          议程：{{ selected.content.talkTitle }}
        </p>
        <p v-if="selected.content.title">{{ selected.content.title }}</p>
        <p>{{ selected.content.question || selected.content.answer }}</p>
        <p>{{ selected.content.starts }} — {{ selected.content.ends }}</p>
        <p v-for="o in selected.content.options" :key="o">{{ o }}</p>
        <p v-if="selected.content.talkId">
          对应议程ID：{{ selected.content.talkId }}
        </p></template
      >
      <label
        >驳回原因（驳回时必填）<input v-model="reason" maxlength="200"
      /></label>
      <div class="actions">
        <button class="primary" :disabled="busy" @click="review(true)">
          审核通过并发布</button
        ><button :disabled="busy || !reason.trim()" @click="review(false)">
          驳回
        </button>
      </div></template
    >
  </section>
</template>

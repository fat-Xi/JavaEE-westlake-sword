<script setup>
// 管理员统一审核内容与提问，可修订展示资料；人工答复独立于 AI 预留模块。
import { inject, onMounted, ref } from "vue";
import { api, states, kinds } from "../api";
import ContentForm from "./ContentForm.vue";
const editing = ref(null);
const { run, busy, message } = inject("app");
const posts = ref([]),
  questions = ref([]),
  reasons = ref({}),
  answers = ref({}),
  filter = ref("PENDING");
async function load() {
  [posts.value, questions.value] = await Promise.all([
    api("/review/posts"),
    api("/review/questions"),
  ]);
}
async function review(type, row, pass) {
  await run(async () => {
    await api(`/review/${type}/${row.id}`, "POST", {
      pass,
      reason: reasons.value[type + row.id] || "",
    });
    await load();
    message.value = "审核状态已更新";
  });
}
async function answer(row) {
  await run(async () => {
    await api(`/review/questions/${row.id}/answer`, "PUT", {
      answer: answers.value[row.id] || row.answer,
    });
    await load();
    message.value = "主办方答复已发布";
  });
}
onMounted(() => run(load));
</script>
<template>
  <div class="panel function-shell">
    <section v-if="editing" class="panel">
      <h2>修订{{ kinds[editing.kind] }} · {{ editing.title }}</h2>
      <ContentForm
        :key="editing.id"
        :initial="editing"
        :options="[editing.kind]"
        @saved="
          editing = null;
          run(load);
        "
      /><button @click="editing = null">取消修订</button>
    </section>
    <template v-if="!editing"
      ><section class="panel">
        <label
          >审核状态<select v-model="filter">
            <option value="ALL">全部</option>
            <option
              v-for="key in ['PENDING', 'APPROVED', 'REJECTED']"
              :key="key"
              :value="key"
            >
              {{ states[key] }}
            </option>
          </select></label
        >
      </section>
      <section
        v-for="(rows, type) in { posts, questions }"
        :key="type"
        class="panel"
      >
        <h2>{{ type === "posts" ? "图文与互动内容审核" : "会议提问审核" }}</h2>
        <article
          v-for="row in rows.filter(
            (r) => filter === 'ALL' || r.status === filter,
          )"
          :key="row.id"
          class="review-row"
        >
          <span class="tag"
            >{{ states[row.status] }} {{ kinds[row.kind] || "提问" }}</span
          >
          <h3>{{ row.title || row.body }}</h3>
          <p v-if="row.title" class="pre">{{ row.body }}</p>
          <p class="muted">
            活动 #{{ row.event_id }} · 作者 #{{ row.user_id }}
          </p>
          <p v-if="row.product">{{ row.product }}</p>
          <img
            v-if="row.image"
            class="post-image"
            :src="row.image"
            alt="待审图片"
          /><a v-if="row.url" :href="row.url" target="_blank" rel="noopener"
            >检查视频 / 外部链接 ↗</a
          >
          <button
            v-if="
              type === 'posts' &&
              ['NEWS', 'RESULT', 'EXHIBIT'].includes(row.kind)
            "
            @click="editing = row"
          >
            修订资料并重新送审
          </button>
          <div class="actions">
            <input
              v-model="reasons[type + row.id]"
              maxlength="200"
              placeholder="驳回或下架理由"
            /><button :disabled="busy" @click="review(type, row, true)">
              通过</button
            ><button :disabled="busy" @click="review(type, row, false)">
              驳回 / 下架
            </button>
          </div>
          <form
            v-if="type === 'questions' && row.status === 'APPROVED'"
            @submit.prevent="answer(row)"
          >
            <label
              >主办方人工答复<textarea
                v-model="answers[row.id]"
                :placeholder="row.answer || '请输入答复'"
                maxlength="1000"
              ></textarea></label
            ><button :disabled="busy">发布答复</button>
          </form>
        </article>
        <p v-if="!rows.some((r) => filter === 'ALL' || r.status === filter)">
          暂无符合条件的内容
        </p>
      </section></template
    >
  </div>
</template>

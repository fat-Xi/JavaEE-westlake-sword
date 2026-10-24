<script setup>
// 管理员表单共用当前活动选择，避免上传资料或审核报名时选错活动。
import { computed, inject, onMounted, ref } from "vue";
import { api, states, time } from "../api";
import ContentForm from "./ContentForm.vue";
import MeetingPanel from "./MeetingPanel.vue";
import MediaAdmin from "./MediaAdmin.vue";
const detailId = ref(0);
const { can, run, busy, message } = inject("app");
const events = ref([]),
  joins = ref([]),
  talks = ref([]),
  eventId = ref(0),
  editing = ref(false);
const blank = () => ({
  title: "",
  place: "",
  starts: "",
  ends: "",
  capacity: 100,
  content: "",
  status: "OPEN",
  liveUrl: "",
  replayUrl: "",
});
const form = ref(blank());
const talk = ref({
  title: "",
  speaker: "",
  room: "",
  starts: "",
  ends: "",
  tags: "",
});
const reasons = ref({}),
  file = ref(null);
const options = computed(() => [
  ...(can("content") ? ["NEWS", "RESULT"] : []),
  ...(can("exhibit") ? ["EXHIBIT"] : []),
]);
async function load() {
  events.value = await api(can("event") ? "/admin/events" : "/events");
  if (!eventId.value) eventId.value = events.value[0]?.id || 0;
  if (can("event")) joins.value = await api("/admin/joins");
  await loadTalks();
}
async function loadTalks() {
  talks.value = eventId.value
    ? (await api(`/meetings/${eventId.value}`)).talks
    : [];
}
function edit(row) {
  form.value = row
    ? { ...row, liveUrl: row.live_url || "", replayUrl: row.replay_url || "" }
    : blank();
  editing.value = true;
}
async function save() {
  await run(async () => {
    await api(
      "/admin/events" + (form.value.id ? "/" + form.value.id : ""),
      form.value.id ? "PUT" : "POST",
      form.value,
    );
    editing.value = false;
    await load();
    message.value = "活动已保存，订阅者将收到更新通知";
  });
}
async function review(row, pass) {
  await run(async () => {
    await api(`/admin/joins/${row.id}`, "POST", {
      pass,
      reason: reasons.value[row.id] || "",
    });
    await load();
    message.value = "报名审核完成";
  });
}
async function addTalk() {
  await run(async () => {
    await api("/talks", "POST", { ...talk.value, eventId: eventId.value });
    await loadTalks();
    message.value = "议程已添加";
  });
}
async function removeTalk(id) {
  await run(async () => {
    await api(`/talks/${id}`, "DELETE");
    await loadTalks();
  });
}
async function upload() {
  await run(async () => {
    if (!file.value) throw new Error("请先选择文件");
    const data = new FormData();
    data.append("file", file.value);
    await api(`/events/${eventId.value}/files`, "POST", data);
    message.value = "资料上传成功，可到活动详情查看";
  });
}
onMounted(() => run(load));
</script>
<template>
  <div class="panel function-shell">
    <section v-if="detailId">
      <button @click="detailId = 0">收起会议详情</button
      ><MeetingPanel :key="detailId" :id="detailId" />
    </section>
    <template v-if="!detailId"
      ><section v-if="can('event')" class="panel">
        <template v-if="!editing"
          ><div class="row">
            <h2>活动维护</h2>
            <button class="primary" @click="edit(null)">新建活动</button>
          </div>
          <div v-for="event in events" :key="event.id" class="row">
            <div>
              <strong>{{ event.title }}</strong>
              <p>
                {{ time(event.starts) }} ·
                {{ event.status === "OPEN" ? "已开放" : "已关闭" }}
              </p>
            </div>
            <div class="actions">
              <button @click="detailId = event.id">查看完整信息</button
              ><button @click="edit(event)">编辑 / 上下架</button>
            </div>
          </div>
        </template>
        <form v-if="editing" class="editor" @submit.prevent="save">
          <h3>{{ form.id ? "编辑" : "新建" }}活动</h3>
          <label
            >标题<input v-model="form.title" required maxlength="120" /></label
          ><label
            >地点<input v-model="form.place" required maxlength="120"
          /></label>
          <div class="form-grid">
            <label
              >开始时间<input
                v-model="form.starts"
                type="datetime-local"
                step="1"
                required /></label
            ><label
              >结束时间<input
                v-model="form.ends"
                type="datetime-local"
                step="1"
                required /></label
            ><label
              >人数上限<input
                v-model.number="form.capacity"
                type="number"
                min="1"
                required /></label
            ><label
              >状态<select v-model="form.status">
                <option value="OPEN">开放</option>
                <option value="CLOSED">关闭</option>
              </select></label
            >
          </div>
          <label
            >说明<textarea
              v-model="form.content"
              required
              maxlength="20000"
            ></textarea>
          </label>
          <div class="actions">
            <button class="primary" :disabled="busy">保存</button
            ><button type="button" @click="editing = false">取消</button>
          </div>
        </form>
      </section>
      <template v-if="!editing"
        ><section class="panel">
          <label
            >当前管理活动<select v-model="eventId" @change="run(loadTalks)">
              <option v-for="event in events" :key="event.id" :value="event.id">
                {{ event.title }}
              </option>
            </select></label
          >
        </section>
        <template v-if="eventId">
          <section v-if="can('event')" class="panel">
            <h2>议程设置</h2>
            <p class="muted">
              议程必须在活动时间内，同一会场不能重叠。已有参会者收藏的议程不能直接删除。
            </p>
            <div v-for="row in talks" :key="row.id" class="row">
              <span
                >{{ row.title }} · {{ row.room }} · {{ time(row.starts) }}</span
              ><button @click="removeTalk(row.id)">删除</button>
            </div>
            <form class="editor" @submit.prevent="addTalk">
              <div class="form-grid">
                <label
                  >议程标题<input
                    v-model="talk.title"
                    required
                    maxlength="120" /></label
                ><label
                  >嘉宾<input
                    v-model="talk.speaker"
                    required
                    maxlength="60" /></label
                ><label
                  >会场<input
                    v-model="talk.room"
                    required
                    maxlength="60" /></label
                ><label>标签<input v-model="talk.tags" maxlength="200" /></label
                ><label
                  >开始<input
                    v-model="talk.starts"
                    type="datetime-local"
                    step="1"
                    required /></label
                ><label
                  >结束<input
                    v-model="talk.ends"
                    type="datetime-local"
                    step="1"
                    required
                /></label>
              </div>
              <button :disabled="busy">添加议程</button>
            </form>
          </section>
          <MediaAdmin :key="eventId + ':' + talks.length" :event-id="eventId" />
          <section class="section-block">
            <h2>会议内容来源</h2>
            <p>
              会议资讯、成果与展商资料由展商投稿。请在「内容审核」中审核与修订；办会申请通过后可一键进入会议发布。
            </p>
          </section>
          <section v-if="can('content')" class="panel">
            <h2>上传大会资料</h2>
            <form @submit.prevent="upload">
              <input
                type="file"
                accept=".pdf,.ppt,.pptx,.txt,.docx"
                @change="file = $event.target.files[0]"
              />
              <p>支持 PDF、PPT、PPTX、TXT、DOCX，单个文件最多 20MB。</p>
              <button :disabled="busy">上传资料</button>
            </form>
          </section>
          <section v-if="can('event')" class="panel">
            <h2>报名审核</h2>
            <p class="muted">仅待审核记录可处理；名额按已通过人数计算。</p>
            <article
              v-for="row in joins.filter((j) => j.event_id === eventId)"
              :key="row.id"
              class="review-row"
            >
              <p>
                <strong>{{ row.name }}</strong> · {{ states[row.status] }} ·
                {{ row.channel }} {{ row.reason }}
              </p>
              <div v-if="row.status === 'PENDING'" class="actions">
                <input
                  v-model="reasons[row.id]"
                  placeholder="驳回时必填理由"
                  maxlength="200"
                /><button :disabled="busy" @click="review(row, true)">
                  通过</button
                ><button :disabled="busy" @click="review(row, false)">
                  驳回
                </button>
              </div>
            </article>
          </section>
        </template></template
      ></template
    >
  </div>
</template>

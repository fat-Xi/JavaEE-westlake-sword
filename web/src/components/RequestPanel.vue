<script setup>
// 草稿可修改与增删附件；送审后锁定，管理员审核与创建会议是两步操作。
import { ref, computed, inject, onMounted } from "vue";
import LocalImage from "./LocalImage.vue";
import { api, time } from "../api";
const { user, run, busy, message } = inject("app");
const admin = computed(() => user.value.role === "ADMIN");
const pageNo = ref(1);
const rows = ref([]),
  selected = ref(null),
  attachments = ref([]),
  editing = ref(false),
  creating = ref(false),
  reason = ref(""),
  history = ref([]),
  pendingFiles = ref([]);
const blank = () => ({
  title: "",
  place: "",
  starts: "",
  ends: "",
  capacity: 100,
  poll_title: "",
  news: "",
  result: "",
  exhibit: "",
  image: "",
  contact: "",
  content: "",
});
const form = ref(blank()),
  event = ref({});
const labels = {
  DRAFT: "草稿",
  PENDING: "待审核",
  REJECTED: "已驳回",
  APPROVED: "已通过 · 待建会",
  CREATED: "已创建活动",
};
const editable = computed(
  () => selected.value && ["DRAFT", "REJECTED"].includes(selected.value.status),
);
async function load() {
  rows.value = await api("/requests");
  if (selected.value) {
    selected.value = rows.value.find((r) => r.id === selected.value.id) || null;
    if (selected.value) {
      attachments.value = await api(`/requests/${selected.value.id}/files`);
      history.value = await api(`/requests/${selected.value.id}/history`);
    }
  }
}
async function select(row) {
  await run(async () => {
    selected.value = row;
    editing.value = false;
    creating.value = false;
    reason.value = "";
    attachments.value = await api(`/requests/${row.id}/files`);
    history.value = await api(`/requests/${row.id}/history`);
  });
}
function edit(row) {
  form.value = row ? { ...row } : blank();
  pendingFiles.value = [];
  editing.value = true;
  creating.value = false;
}
async function save() {
  await run(async () => {
    const result = await api(
      "/requests" + (form.value.id ? "/" + form.value.id : ""),
      form.value.id ? "PUT" : "POST",
      form.value,
    );
    const id = form.value.id || result.id;
    // 先保留草稿ID；附件失败时可在同一编辑页重试，不生成重复申请。
    form.value.id = id;
    while (pendingFiles.value.length) {
      const data = new FormData();
      data.append("file", pendingFiles.value[0]);
      await api(`/requests/${id}/files`, "POST", data);
      pendingFiles.value.shift();
    }
    history.value = await api(`/requests/${id}/history`);
    rows.value = await api("/requests");
    selected.value = rows.value.find((r) => r.id === id);
    attachments.value = await api(`/requests/${id}/files`);
    editing.value = false;
    message.value = "信息与附件已保存，请确认后提交审核";
  });
}
async function remove(id) {
  await run(async () => {
    await api(`/requests/${selected.value.id}/files/${id}`, "DELETE");
    await load();
  });
}
async function submit() {
  await run(async () => {
    await api(`/requests/${selected.value.id}/submit`, "POST");
    await load();
    message.value = "已送交管理员审核";
  });
}
async function review(pass) {
  await run(async () => {
    await api(`/requests/${selected.value.id}/review`, "POST", {
      pass,
      reason: reason.value,
    });
    await load();
    if (pass) prepare();
    message.value = pass
      ? "资料已通过，请确认会议信息后创建活动"
      : "申请已驳回，展商可修改后重提";
  });
}
function prepare() {
  event.value = {
    ...selected.value,
    status: "CLOSED",
    liveUrl: "",
    replayUrl: "",
  };
  creating.value = true;
}
async function create() {
  await run(async () => {
    await api(`/requests/${selected.value.id}/create`, "POST", event.value);
    creating.value = false;
    await load();
    message.value =
      "活动已创建，附件已转为活动资料。可在活动与内容中继续编辑或开放报名。";
  });
}
async function withdraw() {
  await run(async () => {
    await api(`/requests/${selected.value.id}/withdraw`, "POST");
    await load();
    history.value = await api(`/requests/${selected.value.id}/history`);
    edit(selected.value);
    message.value = "已撤回，可修改后重新送审，撤回记录已保留";
  });
}
onMounted(() => run(load));
</script>
<template>
  <section v-if="!editing && !selected" class="panel">
    <div class="row">
      <div>
        <h2>{{ admin ? "展商办会申请审核" : "我的办会申请" }}</h2>
        <p class="muted">
          填写会议主题、时间、场地及所需资料 → 提交审核 → 管理员确认并创建活动
        </p>
      </div>
      <button v-if="!admin" class="primary" @click="edit(null)">
        新建办会申请</button
      ><button @click="run(load)">刷新</button>
    </div>
    <div
      v-for="row in rows.slice((pageNo - 1) * 6, pageNo * 6)"
      :key="row.id"
      class="row"
    >
      <div>
        <strong>{{ row.title }}</strong>
        <p>
          {{ labels[row.status] }} · {{ time(row.starts) }}
          <span v-if="admin">· {{ row.name }}</span>
        </p>
      </div>
      <button @click="select(row)">查看申请</button>
    </div>
    <p v-if="!rows.length" class="empty">暂无办会申请</p>
    <div v-if="rows.length > 6" class="pagination">
      <button :disabled="pageNo === 1" @click="pageNo--">上一页</button
      ><span>第 {{ pageNo }} / {{ Math.ceil(rows.length / 6) }} 页</span
      ><button :disabled="pageNo * 6 >= rows.length" @click="pageNo++">
        下一页
      </button>
    </div>
  </section>
  <section v-if="editing && !admin" class="panel">
    <h2>{{ form.id ? "修改办会申请" : "填写办会申请" }}</h2>
    <form @submit.prevent="save">
      <div class="form-grid">
        <label
          >会议名称<input
            v-model="form.title"
            required
            maxlength="120" /></label
        ><label
          >拟定地点<input
            v-model="form.place"
            required
            maxlength="120" /></label
        ><label
          >拟定开始时间<input
            v-model="form.starts"
            type="datetime-local"
            step="1"
            required /></label
        ><label
          >拟定结束时间<input
            v-model="form.ends"
            type="datetime-local"
            step="1"
            required /></label
        ><label
          >预计人数<input
            v-model.number="form.capacity"
            type="number"
            min="1"
            required /></label
        ><label
          >联系人及联系方式<input
            v-model="form.contact"
            required
            maxlength="100"
        /></label>
      </div>
      <label
        >会议内容、设备及资料需求<textarea
          v-model="form.content"
          required
          maxlength="20000"
          placeholder="介绍会议目标、议程建议、场地设备需求，以及附件用途"
        ></textarea>
      </label>
      <div class="section-block">
        <h3>同时提交会议内容</h3>
        <p class="muted">
          以下内容随本申请审核。管理员建会后自动发布，作者仍为提交展商。
        </p>
        <label
          >投票大标题（选填）<input
            v-model="form.poll_title"
            maxlength="120"
            placeholder="例如：下一场分享，你想听什么？" /></label
        ><label
          >会议资讯<textarea v-model="form.news" maxlength="10000"></textarea>
        </label>
        <label
          >成果介绍<textarea v-model="form.result" maxlength="10000"></textarea>
        </label>
        <label
          >展商资料<textarea
            v-model="form.exhibit"
            maxlength="10000"
          ></textarea>
        </label>
        <LocalImage v-model="form.image" @error="message = $event" />
      </div>
      <label
        >同时添加会议资料（每份20MB以内）<input
          type="file"
          multiple
          accept=".pdf,.ppt,.pptx,.docx,.txt"
          @change="pendingFiles = Array.from($event.target.files)"
      /></label>
      <p v-for="(item, index) in pendingFiles" :key="index">
        {{ item.name }}
        <button type="button" @click="pendingFiles.splice(index, 1)">
          移除待上传文件
        </button>
      </p>
      <div v-if="form.id">
        <h3>已保存附件</h3>
        <div v-for="item in attachments" :key="item.id" class="row">
          <span>{{ item.name }}</span
          ><button type="button" @click="remove(item.id)">移除</button>
        </div>
      </div>
      <div class="actions">
        <button class="primary" :disabled="busy">保存信息与附件</button
        ><button type="button" @click="editing = false">取消编辑</button>
      </div>
    </form>
  </section>
  <section v-if="selected && !editing" class="panel">
    <button @click="selected = null">返回申请列表</button>
    <button
      v-if="!admin && selected.status === 'PENDING'"
      :disabled="busy"
      @click="withdraw"
    >
      撤回并修改
    </button>
    <template v-if="!creating"
      ><span class="tag">{{ labels[selected.status] }}</span>
      <h2>{{ selected.title }}</h2>
      <dl class="info-grid">
        <dt>提交展商</dt>
        <dd>{{ selected.name }}</dd>
        <dt>拟定地点</dt>
        <dd>{{ selected.place }}</dd>
        <dt>时间</dt>
        <dd>{{ time(selected.starts) }} 至 {{ time(selected.ends) }}</dd>
        <dt>预计人数</dt>
        <dd>{{ selected.capacity }}</dd>
        <dt>联系人</dt>
        <dd>{{ selected.contact }}</dd>
      </dl>
      <p class="pre">{{ selected.content }}</p>
      <img
        v-if="selected.image"
        :src="selected.image"
        class="post-image"
        alt="申请配图"
      />
      <div
        v-for="[key, label] in [
          ['poll_title', '投票大标题'],
          ['news', '会议资讯'],
          ['result', '成果介绍'],
          ['exhibit', '展商资料'],
        ]"
        :key="key"
      >
        <h3>{{ label }}</h3>
        <p class="pre">{{ selected[key] || "未提供" }}</p>
      </div>
      <p v-if="selected.reason" class="notice">
        审核意见：{{ selected.reason }}
      </p>
      <p v-if="selected.event_id">
        正式活动编号：#{{
          selected.event_id
        }}。申请资料保留原始版本，正式活动以管理员修改后的信息为准。
      </p>
      <details>
        <summary>申请操作记录</summary>
        <p v-for="item in history" :key="item.id">
          {{ time(item.created) }} · {{ item.name }} · {{ item.action }}
        </p>
      </details>
      <h3>提交附件</h3>
      <p class="muted">
        附件随整份办会申请审核，通过并建会后成为可下载的活动资料。
      </p>
      <div v-for="item in attachments" :key="item.id" class="row">
        <a :href="`/api/requests/${selected.id}/files/${item.id}/download`"
          >{{ item.name }}（{{ Math.ceil(item.size / 1024) }} KB）</a
        ><button
          v-if="!admin && editable"
          :disabled="busy"
          @click="remove(item.id)"
        >
          移除
        </button>
      </div>
      <p v-if="!attachments.length">暂未上传附件</p>
      <template v-if="!admin && editable">
        <div class="actions top-space">
          <button @click="edit(selected)">修改信息</button
          ><button class="primary" :disabled="busy" @click="submit">
            提交管理员审核
          </button>
        </div></template
      >
      <div v-if="admin && selected.status === 'PENDING'" class="editor">
        <label
          >审核意见（驳回必填）<textarea
            v-model="reason"
            maxlength="200"
          ></textarea>
        </label>
        <div class="actions">
          <button class="primary" :disabled="busy" @click="review(true)">
            通过并进入会议发布</button
          ><button :disabled="busy" @click="review(false)">驳回补充资料</button>
        </div>
      </div>
      <button
        v-if="admin && selected.status === 'APPROVED' && !creating"
        class="primary top-space"
        @click="prepare"
      >
        进入会议发布（自动填入申请资料）
      </button>
    </template>
    <form v-if="creating" class="editor" @submit.prevent="create">
      <h3>发布会议 · 资料已自动填入</h3>
      <p>
        已关联
        {{ attachments.length }}
        份附件；资讯、成果、展商资料将按已审核版本同步发布。
      </p>
      <p>
        审核通过不会自动发布；请确认以下信息。默认创建为关闭状态，后续可开放报名。
      </p>
      <div class="form-grid">
        <label
          >正式标题<input
            v-model="event.title"
            required
            maxlength="120" /></label
        ><label
          >正式地点<input
            v-model="event.place"
            required
            maxlength="120" /></label
        ><label
          >开始<input
            v-model="event.starts"
            type="datetime-local"
            step="1"
            required /></label
        ><label
          >结束<input
            v-model="event.ends"
            type="datetime-local"
            step="1"
            required /></label
        ><label
          >容量<input
            v-model.number="event.capacity"
            type="number"
            min="1"
            required /></label
        ><label
          >发布状态<select v-model="event.status">
            <option value="CLOSED">关闭，稍后发布</option>
            <option value="OPEN">开放报名</option>
          </select></label
        >
      </div>
      <label
        >活动说明<textarea
          v-model="event.content"
          required
          maxlength="20000"
        ></textarea>
      </label>
      <div class="actions">
        <button class="primary" :disabled="busy">创建正式活动</button
        ><button type="button" @click="creating = false">暂不创建</button>
      </div>
    </form>
  </section>
</template>

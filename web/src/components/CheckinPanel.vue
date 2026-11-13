<script setup>
// 个人票据证明参会身份；工作人员选择的场次决定签到记在哪个议程。
import { computed, inject, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { Html5Qrcode } from "html5-qrcode";
import { api, time } from "../api";
const { run, busy } = inject("app");
const events = ref([]),
  eligible = ref(0),
  loading = ref(true),
  token = ref(""),
  rows = ref([]);
const scanning = ref(false),
  starting = ref(false),
  sessions = ref([]),
  sessionId = ref(0);
const cameras = ref([]),
  cameraId = ref(""),
  error = ref(""),
  result = ref(null);
const available = computed(() =>
  sessions.value.filter(
    (row) =>
      row.enabled &&
      new Date(row.starts).getTime() <= clock.value &&
      clock.value < new Date(row.ends).getTime(),
  ),
);
const current = computed(() =>
  sessions.value.find((row) => row.id === sessionId.value),
);
const clock = ref(Date.now());
const canScan = computed(
  () =>
    current.value &&
    current.value.enabled &&
    new Date(current.value.starts).getTime() <= clock.value &&
    clock.value < new Date(current.value.ends).getTime(),
);
let reader = null,
  handling = false,
  disposed = false,
  timer;
function state(row) {
  if (!row.enabled) return "管理员已关闭";
  if (clock.value < new Date(row.starts).getTime()) return "未到签到时间";
  return clock.value >= new Date(row.ends).getTime()
    ? "签到已结束"
    : "正在签到";
}
let requestId = 0;
async function load() {
  const id = ++requestId;
  try {
    events.value = await api("/events");
    const all = await Promise.all(
      events.value
        .filter((e) => e.status === "OPEN")
        .map(async (e) =>
          (await api("/attendance?eventId=" + e.id)).map((row) => ({
            ...row,
            meeting: e.title,
          })),
        ),
    );
    if (disposed || id !== requestId) return;
    sessions.value = all.flat();
    clock.value = Date.now();
    if (!available.value.some((row) => row.id === sessionId.value))
      sessionId.value = available.value[0]?.id || 0;
    await loadResults();
  } finally {
    loading.value = false;
  }
}
async function loadResults() {
  const selected = sessionId.value;
  rows.value = [];
  eligible.value = 0;
  if (!selected) return;
  const data = await api("/attendance/" + selected + "/results");
  if (!disposed && selected === sessionId.value) {
    rows.value = data.rows;
    eligible.value = data.eligible;
  }
}
async function stop() {
  if (reader?.isScanning) await reader.stop();
  scanning.value = false;
}
async function change() {
  await run(async () => {
    await stop();
    token.value = "";
    result.value = null;
    error.value = "";
    await loadResults();
  });
}
function ticket(text) {
  const value = text.trim();
  if (/^https?:\/\//i.test(value))
    throw new Error(
      "这是会议入口二维码，不是参会者个人签到码。请让参会者打开会议 → 签到服务 → 出示个人签到码。",
    );
  if (!/^[a-zA-Z0-9_-]{16,64}$/.test(value))
    throw new Error(
      "未识别到个人票据，请选择参会者签到码，不要扫描其他二维码。",
    );
  return value;
}
async function submit(text) {
  error.value = "";
  try {
    if (!canScan.value)
      throw new Error("当前场次未开放签到，请核对下方时间或联系管理员。");
    token.value = ticket(text);
    const data = await api("/attendance/scan", "POST", {
      sessionId: sessionId.value,
      token: token.value,
    });
    result.value = { ...data, title: current.value.title };
    await load();
    token.value = "";
  } catch (e) {
    result.value = null;
    error.value = e.message || String(e);
  }
}
async function scan() {
  await run(() => submit(token.value));
}
function cameraError(e) {
  const name = e?.name || String(e);
  if (/NotAllowed|Permission|denied/i.test(name))
    return "摄像头权限被拒绝。请在浏览器地址栏的站点权限中允许摄像头，再重试；也可直接识别二维码图片。";
  if (/NotFound|DevicesNotFound/i.test(name))
    return "没有检测到摄像头，请连接摄像头，或使用二维码图片识别。";
  if (/NotReadable|TrackStart/i.test(name))
    return "摄像头可能被其他软件占用，请关闭占用摄像头的软件后重试。";
  return (
    "摄像头启动失败：" +
    (e?.message || String(e)) +
    "。可以改用二维码图片识别。"
  );
}
async function camera() {
  error.value = "";
  await run(async () => {
    starting.value = true;
    try {
      if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia)
        throw new Error(
          "摄像头需要 HTTPS 或本机 localhost/127.0.0.1。手机直接访问电脑的 HTTP 局域网地址不能调用摄像头，可使用图片识别。",
        );
      if (!canScan.value) throw new Error("请先选择正在签到的场次");
      cameras.value = await Html5Qrcode.getCameras();
      if (!cameras.value.length)
        throw new DOMException("No camera", "NotFoundError");
      if (!cameras.value.some((c) => c.id === cameraId.value))
        cameraId.value = (
          cameras.value.find((c) => /back|rear|后置/i.test(c.label)) ||
          cameras.value[0]
        ).id;
      if (disposed) return;
      reader ||= new Html5Qrcode("qr-reader");
      await reader.start(
        cameraId.value,
        {
          fps: 8,
          qrbox: (width, height) => {
            const size = Math.min(
              240,
              Math.floor(Math.min(width, height) * 0.7),
            );
            return { width: size, height: size };
          },
        },
        async (text) => {
          if (handling || disposed) return;
          handling = true;
          try {
            await stop();
            await run(() => submit(text));
          } finally {
            handling = false;
          }
        },
        () => {},
      );
      if (disposed) {
        await stop();
        reader.clear();
        return;
      }
      scanning.value = true;
    } catch (e) {
      error.value = e?.message?.includes("HTTPS") ? e.message : cameraError(e);
      await stop().catch(() => {});
    } finally {
      starting.value = false;
    }
  });
}
async function image(event) {
  const file = event.target.files[0];
  if (!file) return;
  error.value = "";
  await run(async () => {
    try {
      if (
        !["image/png", "image/jpeg", "image/webp"].includes(file.type) ||
        file.size > 10 * 1024 * 1024
      )
        throw new Error("请选择10MB以内的PNG、JPEG或WebP二维码图片");
      if (!canScan.value) throw new Error("请先选择正在签到的场次");
      await stop();
      reader ||= new Html5Qrcode("qr-reader");
      let text;
      try {
        text = await reader.scanFile(file, false);
      } catch {
        throw new Error(
          "图片中没有识别到清晰二维码，请上传完整的个人签到码图片。",
        );
      }
      await submit(text);
    } catch (e) {
      error.value = e.message || String(e);
    } finally {
      event.target.value = "";
    }
  });
}
watch(available, async (list) => {
  if (list.some((row) => row.id === sessionId.value)) return;
  await stop().catch(() => {});
  sessionId.value = list[0]?.id || 0;
  result.value = null;
  token.value = "";
  await run(loadResults);
});
onMounted(async () => {
  await run(load);
  if (!disposed)
    timer = setInterval(() => {
      clock.value = Date.now();
      // 自动发现新发布或已关闭的签到场次，操作期间不刷新。
      if (
        !busy.value &&
        !scanning.value &&
        !starting.value &&
        clock.value - lastRefresh > 15000
      ) {
        lastRefresh = clock.value;
        run(load);
      }
    }, 1000);
});
let lastRefresh = Date.now();
onBeforeUnmount(() => {
  disposed = true;
  clearInterval(timer);
  if (!starting.value)
    stop()
      .then(() => reader?.clear())
      .catch(() => {});
});
</script>
<template>
  <div class="staff-desk">
    <div class="staff-heading">
      <span class="eyebrow">HUIXIANG · CHECK IN</span>
      <h1>让每一次到场，都有记录。</h1>
      <p class="muted">选择正在开放签到的议程，核验参会者个人签到码。</p>
    </div>
    <section v-if="loading" class="panel empty">正在加载签到活动…</section>
    <section v-else-if="!available.length" class="panel staff-empty">
      <h2>暂无可签到的议程</h2>
      <p>目前没有开放中的签到活动，新的签到活动发布后会自动显示。</p>
      <button :disabled="busy" @click="run(load)">刷新活动</button>
    </section>
    <template v-else>
      <section class="panel">
        <div class="row">
          <h2>选择签到议程</h2>
          <button :disabled="busy || scanning || starting" @click="run(load)">
            刷新活动
          </button>
        </div>
        <label
          >需要签到的议程<select
            v-model="sessionId"
            :disabled="busy || scanning || starting"
            @change="change"
          >
            <option v-for="row in available" :key="row.id" :value="row.id">
              {{ row.meeting }} · {{ row.title }}
            </option>
          </select></label
        >
        <div v-if="current" class="staff-schedule">
          <span class="tag">正在签到</span>
          <h3>{{ current.title }}</h3>
          <p>{{ current.meeting }} · {{ current.room }}</p>
          <p>签到时间：{{ time(current.starts) }} — {{ time(current.ends) }}</p>
          <p class="muted">
            议程时间：{{ time(current.talk_starts) }} —
            {{ time(current.talk_ends) }}
          </p>
        </div>
        <div class="staff-metrics">
          <div>
            <span>已签到人数</span><strong>{{ rows.length }}</strong>
          </div>
          <div>
            <span>可参会人数</span><strong>{{ eligible }}</strong>
          </div>
          <div>
            <span>未签到人数</span
            ><strong>{{ Math.max(0, eligible - rows.length) }}</strong>
          </div>
        </div>
        <p class="muted">
          可参会人数按本会议身份和报名均已通过的参会者统计，签到只计入当前议程。
        </p>
      </section>
      <section class="panel">
        <h2>核验参会身份</h2>
        <p class="muted">
          请扫描参会者个人签到码，会议入口二维码不能用于核销。
        </p>
        <p v-if="error" class="notice scan-error" role="alert">{{ error }}</p>
        <div v-if="result" class="status-ok" role="status">
          <strong>{{ result.name }} · {{ result.message }}</strong>
          <p>签到议程：{{ result.title }}</p>
        </div>
        <label v-if="cameras.length > 1"
          >使用摄像头<select
            v-model="cameraId"
            :disabled="scanning || starting || busy"
          >
            <option v-for="item in cameras" :key="item.id" :value="item.id">
              {{ item.label || "摄像头" }}
            </option>
          </select></label
        >
        <div class="actions">
          <button
            v-if="!scanning"
            class="primary"
            :disabled="busy || !canScan"
            @click="camera"
          >
            {{ starting ? "正在打开摄像头…" : "开启摄像头扫码" }}</button
          ><button v-else @click="run(stop)">停止扫码</button>
        </div>
        <div id="qr-reader" class="scan-reader"></div>
        <label
          >识别个人签到码图片<input
            type="file"
            accept="image/png,image/jpeg,image/webp"
            :disabled="busy || !canScan"
            @change="image"
        /></label>
        <p class="muted">图片仅在本机识别，不上传服务器。</p>
        <form @submit.prevent="scan">
          <label
            >手动输入签到票据<input
              v-model="token"
              required
              maxlength="64"
              placeholder="输入参会者个人签到码下方的备用票据"
              :disabled="scanning || starting || busy" /></label
          ><button class="primary" :disabled="busy || !canScan || scanning">
            核验并签到
          </button>
        </form>
      </section>
      <section class="panel">
        <div class="row">
          <h2>签到记录 · {{ rows.length }} 人</h2>
          <button :disabled="busy" @click="run(loadResults)">刷新记录</button>
        </div>
        <div class="table-wrap">
          <table v-if="rows.length">
            <thead>
              <tr>
                <th>参会者</th>
                <th>姓名</th>
                <th>签到时间</th>
                <th>核销人员</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.name">
                <td>{{ row.nick }}</td>
                <td>{{ row.real_name || "未填写" }}</td>
                <td>{{ time(row.checked) }}</td>
                <td>{{ row.staff }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">当前议程暂无签到记录。</p>
        </div>
      </section>
    </template>
  </div>
</template>
<style>
.staff-app > header {
  grid-template-columns: 200px 1fr auto;
}
.staff-app > header .account {
  justify-content: flex-end;
}
.staff-app main {
  max-width: 1240px;
}
.staff-heading {
  margin: 20px 0 36px;
}
.staff-heading h1 {
  font-size: clamp(28px, 3vw, 42px);
  margin: 14px 0;
}
.staff-desk .panel {
  padding: 30px;
  margin-bottom: 24px;
  border: 1px solid #dce2f2;
  border-radius: 24px;
  background: #fff;
}
.staff-empty {
  text-align: center;
  padding: 70px 30px !important;
}
.staff-schedule {
  margin: 24px 0;
  padding: 22px;
  background: #f4f3fc;
  border-radius: 16px;
}
.staff-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}
.staff-metrics > div {
  border: 1px solid #e2e5f3;
  border-radius: 16px;
  padding: 22px;
}
.staff-metrics span {
  color: #7b8ba3;
}
.staff-metrics strong {
  display: block;
  color: #7154e8;
  font-size: 34px;
  margin-top: 12px;
}
.staff-desk select,
.staff-desk input {
  width: 100%;
}
.staff-desk form {
  margin-top: 24px;
}
.staff-desk .scan-reader:empty {
  min-height: 0;
}
@media (max-width: 700px) {
  .staff-app > header {
    display: flex;
    flex-wrap: wrap;
  }
  .staff-desk .panel {
    padding: 20px;
  }
  .staff-metrics {
    gap: 8px;
  }
  .staff-metrics > div {
    padding: 12px;
  }
}
</style>

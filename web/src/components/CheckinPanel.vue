<script setup>
// 个人票据证明参会身份；工作人员选择的场次决定签到记在哪个议程。
import { computed, inject, onMounted, onBeforeUnmount, ref } from "vue";
import { Html5Qrcode } from "html5-qrcode";
import { api, time } from "../api";
const { run, busy } = inject("app");
const events = ref([]),
  eventId = ref(0),
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
async function load() {
  sessions.value = eventId.value
    ? await api("/attendance?eventId=" + eventId.value)
    : [];
  if (!sessions.value.some((row) => row.id === sessionId.value)) {
    sessionId.value =
      (
        sessions.value.find((row) => state(row) === "正在签到") ||
        sessions.value[0]
      )?.id || 0;
  }
  rows.value = sessionId.value
    ? (await api("/attendance/" + sessionId.value + "/results")).rows
    : [];
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
    await load();
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
onMounted(() =>
  run(async () => {
    events.value = await api("/events");
    // 默认定位到有正在签到场次的会议，避免选到尚未开始且无签到的会议。
    const all = await Promise.all(
      events.value.map((e) => api("/attendance?eventId=" + e.id)),
    );
    const index = all.findIndex((list) =>
      list.some((row) => state(row) === "正在签到"),
    );
    eventId.value = events.value[index >= 0 ? index : 0]?.id || 0;
    await load();
    if (!disposed)
      timer = setInterval(() => {
        clock.value = Date.now();
      }, 1000);
  }),
);
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
  <div class="panel function-shell">
    <section class="panel">
      <h2>二维码核销</h2>
      <p>
        先确认本签到台负责的会议和场次，再扫描参会者的个人签到码。签到只记入所选场次，不会自动签到其他议程。
      </p>
      <div class="form-grid">
        <label
          >签到会议<select
            v-model="eventId"
            :disabled="busy || scanning || starting"
            @change="change"
          >
            <option v-for="event in events" :key="event.id" :value="event.id">
              {{ event.title }}
            </option>
          </select></label
        >
        <label
          >核销场次（本签到台负责的议程）<select
            v-model="sessionId"
            :disabled="busy || scanning || starting"
            @change="change"
          >
            <option v-for="row in sessions" :key="row.id" :value="row.id">
              {{ row.title }} · {{ state(row) }}
            </option>
          </select></label
        >
      </div>
      <div v-if="current" class="schedule-hint">
        <strong>{{ current.title }}</strong
        ><span class="tag">{{ state(current) }}</span>
        <p>签到窗口：{{ time(current.starts) }} — {{ time(current.ends) }}</p>
        <p>
          议程：{{ time(current.talk_starts) }} —
          {{ time(current.talk_ends) }} · {{ current.room }}
        </p>
      </div>
      <p v-else class="notice">
        这场会议尚未发布签到，请切换会议或联系管理员发布。
      </p>
      <p class="muted">
        管理员发布的二维码是会议签到入口；核销时请扫描参会者在“会议 →
        签到服务”里出示的个人签到码。
      </p>
      <p v-if="error" class="notice scan-error" role="alert">{{ error }}</p>
      <div v-if="result" class="status-ok" role="status">
        <strong>{{ result.name }} · {{ result.message }}</strong>
        <p>核销场次：{{ result.title }}</p>
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
        <button v-if="!scanning" :disabled="busy || !canScan" @click="camera">
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
      <p class="muted">
        可读取参会者下载的个人签到码或清晰截图，识别后按当前场次核销；图片仅在本机识别，不上传服务器。
      </p>
      <form @submit.prevent="scan">
        <label
          >备用方式：粘贴参会者票据<input
            v-model="token"
            required
            maxlength="500"
            placeholder="个人签到码下方的备用票据"
            :disabled="scanning || starting" /></label
        ><button class="primary" :disabled="busy || !canScan || scanning">
          核验并签到
        </button>
      </form>
    </section>
    <section class="panel">
      <div class="row">
        <h2>已签到名单 · {{ rows.length }} 人</h2>
        <button :disabled="busy" @click="run(load)">刷新名单</button>
      </div>
      <div v-for="row in rows" :key="row.name" class="row">
        <strong>{{ row.nick }} · {{ row.real_name }}（{{ row.name }}）</strong
        ><span>{{ time(row.checked) }}</span>
      </div>
      <p v-if="!rows.length" class="muted">当前场次暂无签到记录。</p>
    </section>
  </div>
</template>

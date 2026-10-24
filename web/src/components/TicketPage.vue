<script setup>
import { ref, inject, onMounted, onBeforeUnmount } from "vue";
import QRCode from "qrcode";
import { api, time } from "../api";
const props = defineProps({ meetingId: Number });
const { run, message } = inject("app");
const events = ref([]),
  eventId = ref(0),
  rows = ref([]),
  qr = ref(""),
  token = ref("");
async function load() {
  rows.value = eventId.value
    ? await api(`/attendance?eventId=${eventId.value}`)
    : [];
  qr.value = "";
  token.value = "";
}
async function ticket() {
  await run(async () => {
    const t = await api(`/events/${eventId.value}/ticket`);
    token.value = t.token;
    qr.value = await QRCode.toDataURL(t.token, { width: 240 });
  });
}
let timer;
onMounted(() =>
  run(async () => {
    events.value = (await api("/joins")).filter((j) => j.status === "APPROVED");
    const shared = Number(new URLSearchParams(location.search).get("event"));
    eventId.value =
      props.meetingId ||
      (events.value.some((j) => j.event_id === shared)
        ? shared
        : events.value[0]?.event_id || 0);
    await load();
    timer = setInterval(async () => {
      if (eventId.value)
        try {
          rows.value = await api(`/attendance?eventId=${eventId.value}`);
        } catch {}
    }, 15000);
  }),
);
onBeforeUnmount(() => clearInterval(timer));
</script>
<template>
  <section class="panel">
    <h1>签到服务</h1>
    <p>
      报名通过后，按管理员发布的议程时间出示个人签到码。工作人员核销后，页面会显示已签到。
    </p>
    <label v-if="events.length && !meetingId"
      >已通过报名的会议<select v-model="eventId" @change="run(load)">
        <option v-for="e in events" :key="e.event_id" :value="e.event_id">
          {{ e.title }}
        </option>
      </select></label
    >
    <p v-if="!events.length" class="empty">
      暂无审核通过的报名，暂不能使用签到服务。
    </p>
    <p
      v-if="events.find((e) => e.event_id === eventId)?.legacy_checked"
      class="status-ok"
    >
      ✓ 历史会议已签到 ·
      {{
        time(events.find((e) => e.event_id === eventId).legacy_checked)
      }}。这是旧版会议签到，不代表各议程已签到。
    </p>
    <div v-for="row in rows" :key="row.id" class="section-block">
      <h2>{{ row.title }}</h2>
      <p>{{ time(row.starts) }} 至 {{ time(row.ends) }}</p>
      <p v-if="row.checked" class="status-ok">
        ✓ 已签到 · {{ time(row.checked) }}
      </p>
      <template v-else
        ><p>
          {{
            !row.enabled
              ? "签到已关闭"
              : new Date(row.starts) > new Date()
                ? "签到未开始"
                : new Date(row.ends) <= new Date()
                  ? "签到已结束"
                  : "签到进行中"
          }}
        </p>
        <button
          v-if="row.enabled && new Date(row.ends) > new Date()"
          @click="ticket"
        >
          出示个人签到码
        </button></template
      >
    </div>
    <p v-if="eventId && !rows.length">管理员尚未发布该会议的签到活动。</p>
    <div v-if="qr" class="section-block">
      <img :src="qr" class="qr-image" alt="个人签到凭证" />
      <p>
        这是个人身份凭证，工作人员选择对应议程后核销。请勿与管理员的签到入口二维码混淆。
      </p>
      <a :href="qr" download="个人签到码.png">下载个人签到码图片</a>
      <details>
        <summary>备用票据</summary>
        <code>{{ token }}</code>
      </details>
    </div>
  </section>
</template>

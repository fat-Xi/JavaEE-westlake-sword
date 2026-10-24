<script setup>
// 统计值全部来自后端，图表只负责呈现；组件卸载时释放图表实例。
import { inject, ref, onMounted, onBeforeUnmount, nextTick } from "vue";
import * as echarts from "echarts/core";
import { PieChart, BarChart } from "echarts/charts";
import {
  TooltipComponent,
  LegendComponent,
  GridComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
// 按需注册图表，避免打包未使用的地图、三维和其它图形功能。
echarts.use([
  PieChart,
  BarChart,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  CanvasRenderer,
]);
import { api } from "../api";
const { run } = inject("app");
const events = ref([]),
  eventId = ref(""),
  data = ref(null),
  chartEl = ref(null),
  trendEl = ref(null);
const labels = {
  users: "平台注册用户",
  logins: "平台累计登录",
  joins: "有效报名记录",
  approved: "审核通过",
  pending: "待审核",
  votes: "会议投票票数",
  checkins: "签到人数（按会议去重）",
};
let channelChart, trendChart;
const channelNames = {
  direct: "直接访问",
  share: "分享链接",
  invite: "历史邀请来源",
  poster: "宣传海报",
};
async function load() {
  data.value = await api(
    "/admin/stats" + (eventId.value ? "?eventId=" + eventId.value : ""),
  );
  await nextTick();
  channelChart ||= echarts.init(chartEl.value);
  trendChart ||= echarts.init(trendEl.value);
  channelChart.setOption({
    color: ["#258b87", "#74b9a2", "#f0b95c", "#7b9dc9"],
    tooltip: { trigger: "item" },
    legend: { bottom: 0 },
    series: [
      {
        name: "首次报名来源",
        type: "pie",
        radius: ["40%", "68%"],
        data: data.value.channels.map((c) => ({
          ...c,
          name: channelNames[c.name] || c.name,
        })),
      },
    ],
  });
  trendChart.setOption({
    color: ["#258b87"],
    tooltip: { trigger: "axis" },
    grid: { left: 40, right: 20, bottom: 35, top: 25 },
    xAxis: {
      type: "category",
      data: [...data.value.trend].reverse().map((r) => r.name),
    },
    yAxis: { type: "value", minInterval: 1 },
    series: [
      {
        name: "报名记录",
        type: "bar",
        barMaxWidth: 35,
        data: [...data.value.trend].reverse().map((r) => r.value),
      },
    ],
  });
}
function resize() {
  channelChart?.resize();
  trendChart?.resize();
}
onMounted(() => {
  window.addEventListener("resize", resize);
  run(async () => {
    events.value = await api("/admin/events");
    await load();
  });
});
onBeforeUnmount(() => {
  window.removeEventListener("resize", resize);
  channelChart?.dispose();
  trendChart?.dispose();
});
</script>
<template>
  <section class="panel">
    <div class="row">
      <label
        >数据范围<select v-model="eventId" @change="run(load)">
          <option value="">所有活动</option>
          <option v-for="event in events" :key="event.id" :value="event.id">
            {{ event.title }}
          </option>
        </select></label
      ><button @click="run(load)">刷新数据</button>
    </div>
    <p class="muted">
      指标实时查询数据库。注册和登录为平台累计值，其余数字按活动过滤；有效报名不含已取消记录。演示账号也计入统计。
    </p>
  </section>
  <div v-if="data" class="stats-grid">
    <article v-for="(label, key) in labels" :key="key" class="metric">
      <span>{{ label }}</span
      ><strong>{{ data[key] }}</strong>
    </article>
  </div>
  <div class="form-grid">
    <section class="panel">
      <h2>报名渠道分布</h2>
      <div ref="chartEl" class="chart"></div>
      <p class="muted">按首次报名来源统计，保留取消及驳回记录用于渠道分析。</p>
    </section>
    <section class="panel">
      <h2>报名趋势</h2>
      <div ref="trendEl" class="chart"></div>
      <p class="muted">最近 14 个有报名记录的日期，按首次报名时间计算。</p>
    </section>
  </div>
</template>

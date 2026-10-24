import { request, chromium, expect } from "@playwright/test";
const base = process.env.TEST_URL || "http://127.0.0.1:5174";
let count = 0;
const clients = [];
async function call(c, p, method = "GET", data, code = 200) {
  const r = await c.fetch("/api" + p, { method, data });
  const body = await r.text();
  expect(r.status(), p + body).toBe(code);
  count++;
  return body ? JSON.parse(body) : {};
}
async function login(name) {
  const c = await request.newContext({
    baseURL: base,
    extraHTTPHeaders: { "X-App-Request": "1" },
  });
  clients.push(c);
  await call(c, "/login", "POST", { name, password: "Demo12345!" });
  return c;
}
const a = await login("demo_admin"),
  s = await login("demo_staff"),
  u = await login("demo_user"),
  e = await login("demo_exhibit");
const stamp = Date.now();
const local = (delta) => {
  const d = new Date(Date.now() + delta);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 19);
};
const event = {
  title: "边界回归" + stamp,
  place: "测试会场",
  starts: local(-7200000),
  ends: local(7200000),
  capacity: 20,
  content: "仅测试数据",
  status: "OPEN",
  liveUrl: "",
  replayUrl: "",
};
await call(a, "/admin/events", "POST", event);
const id = (await call(a, "/admin/events")).find(
  (x) => x.title === event.title,
).id;
await call(u, `/events/${id}/join`, "POST", { channel: "invite" }, 400);
await call(u, `/events/${id}/join`, "POST", { channel: "share" });
const join = (await call(u, "/joins")).find((j) => j.event_id === id);
await call(a, `/admin/joins/${join.id}`, "POST", { pass: true });
const times = [
  [-3600000, -1800000],
  [-900000, 3600000],
  [3700000, 7100000],
];
for (let i = 0; i < times.length; i++)
  await call(a, "/talks", "POST", {
    eventId: id,
    title: ["已结束议程", "正在进行议程", "未来议程"][i],
    speaker: "演示讲师",
    room: "厅" + i,
    starts: local(times[i][0]),
    ends: local(times[i][1]),
    tags: "安全",
  });
const talks = (await call(a, `/meetings/${id}`)).talks;
const past = talks[0],
  live = talks[1],
  future = talks[2];
await call(a, `/talks/${past.id}/media`, "PUT", {
  liveUrl: "",
  replayUrl: "https://example.com/replay.mp4",
  liveOn: false,
});
await call(a, `/talks/${future.id}/media`, "PUT", {
  liveUrl: "https://example.com/live.mp4",
  replayUrl: "https://example.com/replay.mp4",
  liveOn: true,
});
let media = await call(u, `/events/${id}/media`);
expect(media.find((t) => t.id === past.id).replay_url).toContain("replay.mp4");
expect(media.find((t) => t.id === future.id).live_url).toBe("");
expect(media.find((t) => t.id === future.id).replay_url).toBe("");
expect(media.find((t) => t.id === live.id).had_live).toBe(false);
await call(a, "/attendance", "POST", {
  talkId: live.id,
  starts: local(-1000),
  ends: local(600000),
  enabled: false,
});
const session = (await call(u, `/attendance?eventId=${id}`))[0];
const ticket = await call(u, `/events/${id}/ticket`);
await call(
  s,
  "/attendance/scan",
  "POST",
  { sessionId: session.id, token: ticket.token },
  400,
);
await call(a, "/attendance", "POST", {
  talkId: live.id,
  starts: local(100000),
  ends: local(600000),
  enabled: true,
});
await call(
  s,
  "/attendance/scan",
  "POST",
  { sessionId: session.id, token: ticket.token },
  400,
);
await call(a, "/attendance", "POST", {
  talkId: live.id,
  starts: local(-600000),
  ends: local(-1000),
  enabled: true,
});
await call(
  s,
  "/attendance/scan",
  "POST",
  { sessionId: session.id, token: ticket.token },
  400,
);
await call(a, "/attendance", "POST", {
  talkId: live.id,
  starts: local(-1000),
  ends: local(600000),
  enabled: true,
});
await call(s, "/attendance/scan", "POST", {
  sessionId: session.id,
  token: ticket.token,
});
await call(s, "/checkin", "POST", { eventId: id, token: ticket.token }, 400);
await call(a, "/checkin", "POST", { eventId: id, token: ticket.token }, 403);
expect((await call(a, `/admin/stats?eventId=${id}`)).checkins).toBe(1);
await call(u, `/events/${id}/sub`, "POST");
await call(a, `/admin/events/${id}`, "PUT", { ...event, status: "CLOSED" });
expect((await call(u, "/my/subs")).find((x) => x.id === id).status).toBe(
  "CLOSED",
);
await call(a, `/admin/events/${id}`, "PUT", event);
const form = {
  title: "撤回并发" + stamp,
  place: "杭州",
  starts: local(86400000),
  ends: local(90000000),
  capacity: 30,
  contact: "测试联系人",
  content: "并发撤回测试",
};
const r = await call(e, "/requests", "POST", form);
await call(e, `/requests/${r.id}/submit`, "POST");
const pair = await Promise.all([
  e.post(`/api/requests/${r.id}/withdraw`),
  a.post(`/api/requests/${r.id}/review`, { data: { pass: true } }),
]);
expect(pair.map((x) => x.status()).sort()).toEqual([200, 400]);
count += 2;
const browser = await chromium.launch({ channel: "msedge", headless: true });
const ctx = await browser.newContext({
  viewport: { width: 1366, height: 900 },
});
const page = await ctx.newPage();
const errors = [];
page.on("pageerror", (err) => errors.push(err.message));
async function uiLogin(name) {
  await page.goto(base);
  if (await page.getByRole("button", { name: "退出", exact: true }).isVisible())
    await page.getByRole("button", { name: "退出", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill(name);
  await page.getByLabel("密码", { exact: true }).fill("Demo12345!");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
}
try {
  await page.goto(base);
  await page.getByRole("button", { name: "还没有账号？去注册" }).click();
  await page.getByLabel("注册身份").selectOption("EXHIBITOR");
  await page.getByLabel("网名", { exact: true }).fill("注册测试网名");
  await page.getByLabel("真实姓名", { exact: true }).fill("李四");
  await page.getByLabel("邮箱", { exact: true }).fill("lisi@example.com");
  await page.getByLabel("电话", { exact: true }).fill("13800138001");
  const username = "ui_v4_" + stamp;
  await page.getByLabel("账号", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill("Demo12345!");
  await page.getByRole("button", { name: "注册", exact: true }).click();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  await uiLogin(username);
  await expect(
    page.getByRole("heading", { name: "展商个人主页" }),
  ).toBeVisible();
  await expect(page.locator("header nav button")).toHaveText(["个人主页"]);
  const uid = (await call(a, "/admin/users")).find(
    (x) => x.name === username,
  ).id;
  await call(a, `/admin/users/${uid}/review`, "POST", { pass: true });
  await uiLogin("demo_exhibit");
  await page.getByRole("button", { name: "新建办会申请" }).click();
  await page.getByLabel("会议名称", { exact: true }).fill("UI附件同页" + stamp);
  await page.getByLabel("拟定地点", { exact: true }).fill("杭州");
  await page.getByLabel("拟定开始时间").fill(local(86400000));
  await page.getByLabel("拟定结束时间").fill(local(90000000));
  await page.getByLabel("联系人及联系方式").fill("李四 13800138001");
  await page.getByLabel("会议内容、设备及资料需求").fill("投影和会场资料");
  await page.locator("input[type=file][multiple]").setInputFiles({
    name: "同页附件.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("附件内容"),
  });
  await page.getByRole("button", { name: "保存信息与附件" }).click();
  await expect(page.getByRole("link", { name: /同页附件.txt/ })).toBeVisible();
  await page.getByRole("button", { name: "提交管理员审核" }).click();
  await expect(page.getByRole("button", { name: "撤回并修改" })).toBeVisible();
  await page.getByRole("button", { name: "撤回并修改" }).click();
  await expect(
    page.getByRole("heading", { name: "修改办会申请" }),
  ).toBeVisible();
  await page.getByLabel("会议名称", { exact: true }).fill("UI撤回修改" + stamp);
  await page.getByRole("button", { name: "保存信息与附件" }).click();
  await page.getByText("申请操作记录", { exact: true }).click();
  await expect(page.getByText(/撤回待审申请，恢复草稿/)).toBeVisible();
  await uiLogin("demo_user");
  await page.goto(`${base}/?event=${id}`);
  await expect(page.getByText("直播未开启", { exact: true })).toBeVisible();
  await expect(
    page.getByText("会议未开放直播，暂无直播回放。", { exact: true }),
  ).toBeVisible();
  await expect(page.locator("video")).toBeVisible();
  await uiLogin("demo_staff");
  await page.getByRole("button", { name: "扫码签到", exact: true }).click();
  await page.locator("select").first().selectOption(String(id));
  await expect(
    page.getByText("已签到名单 · 1 人", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "保存并发布签到" }),
  ).toHaveCount(0);
  expect(errors).toEqual([]);
  console.log(
    `PASS V4 edges: ${count} HTTP assertions; registration, same-form files, withdraw history, media states, staff results.`,
  );
} finally {
  await browser.close();
  for (const c of clients) await c.dispose();
}

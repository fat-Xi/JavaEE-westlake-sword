// V4 回归：仅面向独立测试库。通过 HTTP 建立数据，浏览器验证实际页面。
import { chromium, request, expect } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { mkdir, writeFile } from "node:fs/promises";
const base = process.env.TEST_URL || "http://127.0.0.1:5174";
let count = 0;
const clients = [];
async function client(name) {
  const c = await request.newContext({
    baseURL: base,
    extraHTTPHeaders: { "X-App-Request": "1" },
  });
  clients.push(c);
  if (name) await call(c, "/login", "POST", { name, password: "Demo12345!" });
  return c;
}
async function call(c, path, method = "GET", data, code = 200) {
  const res = await c.fetch("/api" + path, { method, data });
  const text = await res.text();
  expect(res.status(), `${method} ${path}: ${text}`).toBe(code);
  count++;
  return text ? JSON.parse(text) : {};
}
const admin = await client("demo_admin"),
  staff = await client("demo_staff"),
  user = await client("demo_user"),
  pending = await client("demo_pending"),
  exhibit = await client("demo_exhibit");
const stamp = Date.now();
const profile = {
  nick: "测试网名",
  realName: "张三",
  gender: "男",
  email: "test@example.com",
  phone: "13800138000",
  company: "测试公司",
  tags: "安全",
  avatar: "",
};
const member = await client(),
  vendor = await client();
for (const [c, role, suffix] of [
  [member, "USER", "u"],
  [vendor, "EXHIBITOR", "e"],
]) {
  const name = `v4_${stamp}_${suffix}`;
  await call(c, "/register", "POST", {
    name,
    password: "Demo12345!",
    role,
    ...profile,
  });
  await call(c, "/login", "POST", { name, password: "Demo12345!" });
  const me = await call(c, "/me");
  expect(me.status).toBe("PENDING");
  expect(me.role).toBe(role);
  await call(c, "/events", "GET", undefined, 403);
  await call(c, "/profile", "PUT", {
    ...profile,
    nick: "新的网名",
    role: "ADMIN",
    name: "hacked",
  });
  expect((await call(c, "/me")).name).toBe(name);
  if (suffix === "e") {
    await call(admin, `/admin/users/${me.id}/review`, "POST", {
      pass: false,
      reason: "请补充单位",
    });
    await call(c, "/profile", "PUT", { ...profile, company: "补充后的公司" });
    expect((await call(c, "/me")).status).toBe("PENDING");
  }
  await call(admin, `/admin/users/${me.id}/review`, "POST", {
    pass: true,
    reason: "",
  });
  expect((await call(c, "/me")).status).toBe("APPROVED");
}
await call(
  member,
  "/register",
  "POST",
  { name: `bad_${stamp}`, password: "Demo12345!", role: "ADMIN", ...profile },
  400,
);
for (const c of [admin, staff, user, exhibit]) {
  await call(c, "/profile", "PUT", profile);
  expect((await call(c, "/me")).nick).toBe(profile.nick);
  await call(c, "/profile", "PUT", { ...profile, email: "invalid" }, 400);
  await call(
    c,
    "/profile",
    "PUT",
    { ...profile, avatar: "data:image/svg+xml;base64,PHN2Zz4=" },
    400,
  );
}
const events = await call(user, "/events");
const event = events.find((e) => e.title.includes("数字安全创新"));
expect(event.live_url).toBeUndefined();
await call(pending, `/events/${event.id}/media`, "GET", undefined, 403);
await call(pending, `/events/${event.id}/posts`, "GET", undefined, 403);
await call(pending, `/events/${event.id}/files`, "GET", undefined, 403);
await call(pending, `/events/${event.id}/ticket`, "GET", undefined, 403);
await call(
  user,
  "/posts",
  "POST",
  {
    eventId: event.id,
    kind: "NEWS",
    title: "越权投稿",
    body: "禁止",
    url: "",
    image: "",
    product: "",
  },
  403,
);
await call(
  user,
  "/posts",
  "POST",
  {
    eventId: event.id,
    kind: "RESULT",
    title: "越权成果",
    body: "禁止",
    url: "",
    image: "",
    product: "",
  },
  403,
);
await call(user, `/events/${event.id}/sub`, "POST");
expect((await call(user, "/my/subs")).some((e) => e.id === event.id)).toBe(
  true,
);
await call(user, `/events/${event.id}/sub`, "DELETE");
expect((await call(user, "/my/subs")).some((e) => e.id === event.id)).toBe(
  false,
);
const future = new Date(Date.now() + 86400000 * 3).toISOString().slice(0, 19);
const later = new Date(Date.now() + 86400000 * 3 + 7200000)
  .toISOString()
  .slice(0, 19);
const form = {
  title: `V4申请${stamp}`,
  place: "杭州",
  starts: future,
  ends: later,
  capacity: 40,
  contact: "张三 13800138000",
  content: "测试会议资料与申请",
};
const r = await call(vendor, "/requests", "POST", form);
const file = await vendor.post(`/api/requests/${r.id}/files`, {
  multipart: {
    file: {
      name: "说明.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("会议说明"),
    },
  },
});
expect(file.ok()).toBe(true);
count++;
await call(vendor, `/requests/${r.id}/submit`, "POST");
await call(vendor, `/requests/${r.id}`, "PUT", form, 400);
await call(exhibit, `/requests/${r.id}/withdraw`, "POST", undefined, 403);
await call(vendor, `/requests/${r.id}/withdraw`, "POST");
await call(admin, `/requests/${r.id}/review`, "POST", { pass: true }, 400);
await call(vendor, `/requests/${r.id}`, "PUT", {
  ...form,
  title: form.title + "改",
});
await call(vendor, `/requests/${r.id}/submit`, "POST");
await call(admin, `/requests/${r.id}/review`, "POST", { pass: true });
await call(vendor, `/requests/${r.id}/withdraw`, "POST", undefined, 400);
expect(
  (await call(vendor, `/requests/${r.id}/history`)).some((h) =>
    h.action.includes("撤回"),
  ),
).toBe(true);
const created = await call(admin, `/requests/${r.id}/create`, "POST", {
  ...form,
  status: "OPEN",
  liveUrl: "",
  replayUrl: "",
});
expect((await call(admin, `/meetings/${created.eventId}`)).files.length).toBe(
  1,
);
// 议程采用本地时间序列化，测试窗口覆盖当前时间。
function local(delta) {
  const d = new Date(Date.now() + delta);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 19);
}
await call(admin, "/talks", "POST", {
  eventId: event.id,
  title: `V4直播议程${stamp}`,
  speaker: "讲师",
  room: "V4测试厅" + stamp,
  starts: local(-1800000),
  ends: local(3600000),
  tags: "安全",
});
let talks = (await call(admin, `/meetings/${event.id}`)).talks;
const talk = talks.find((t) => t.title === `V4直播议程${stamp}`);
await call(admin, `/talks/${talk.id}/media`, "PUT", {
  liveUrl:
    "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
  replayUrl: "",
  liveOn: true,
});
expect(
  (await call(user, `/events/${event.id}/media`)).find((t) => t.id === talk.id)
    .live_url,
).toContain("flower.mp4");
await call(
  user,
  `/talks/${talk.id}/media`,
  "PUT",
  { liveUrl: "", replayUrl: "", liveOn: false },
  403,
);
await call(
  admin,
  `/talks/${talk.id}/media`,
  "PUT",
  { liveUrl: "", replayUrl: "", liveOn: true },
  400,
);
for (const old of await call(user, "/my/plans"))
  await call(user, `/talks/${old.id}/plan`, "DELETE");
await call(user, `/talks/${talk.id}/plan`, "POST");
expect((await call(user, "/my/plans")).some((t) => t.id === talk.id)).toBe(
  true,
);
await call(user, `/talks/${talk.id}/plan`, "POST", undefined, 400);
await call(admin, "/attendance", "POST", {
  talkId: talk.id,
  starts: local(-3600000),
  ends: local(3500000),
  enabled: true,
});
let attendance = (await call(user, `/attendance?eventId=${event.id}`)).find(
  (a) => a.talk_id === talk.id,
);
const ticket = await call(user, `/events/${event.id}/ticket`);
await call(
  admin,
  "/attendance/scan",
  "POST",
  { sessionId: attendance.id, token: ticket.token },
  403,
);
await call(
  user,
  "/attendance/scan",
  "POST",
  { sessionId: attendance.id, token: ticket.token },
  403,
);
await call(
  staff,
  "/attendance",
  "POST",
  { talkId: talk.id, starts: local(-1000), ends: local(300000) },
  403,
);
const [one, two] = await Promise.all([
  call(staff, "/attendance/scan", "POST", {
    sessionId: attendance.id,
    token: ticket.token,
  }),
  call(staff, "/attendance/scan", "POST", {
    sessionId: attendance.id,
    token: ticket.token,
  }),
]);
expect([one.duplicate, two.duplicate].sort()).toEqual([false, true]);
expect(
  (await call(admin, `/attendance/${attendance.id}/results`)).rows.length,
).toBe(1);
expect(
  (await call(user, `/attendance?eventId=${event.id}`)).find(
    (a) => a.id === attendance.id,
  ).checked,
).toBeTruthy();
await call(user, `/events/${event.id}/join`, "DELETE", undefined, 400);
await call(user, `/attendance/${attendance.id}/results`, "GET", undefined, 403);
await call(admin, `/admin/stats?eventId=${event.id}`);
const browser = await chromium.launch({ channel: "msedge", headless: true });
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  permissions: ["clipboard-read", "clipboard-write"],
});
const page = await ctx.newPage(),
  errors = [];
page.on("pageerror", (e) => errors.push(e.message));
const out = new URL("../../test-results/", import.meta.url);
await mkdir(out, { recursive: true });
async function login(name) {
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
  for (const [name, role] of [
    ["demo_admin", "管理员"],
    ["demo_staff", "工作人员"],
    ["demo_exhibit", "展商"],
    ["demo_user", "参会者"],
  ]) {
    await login(name);
    await page.getByRole("button", { name: "个人主页", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: role + "个人主页" }),
    ).toBeVisible();
    await expect(page.getByLabel("真实姓名", { exact: true })).toHaveValue(
      "张三",
    );
    await expect(page.locator(".account")).toContainText("测试网名");
  }
  await page.getByRole("button", { name: "我的订阅", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "我的订阅", exact: true }),
  ).toHaveClass(/active/);
  await page.getByRole("button", { name: "个人日程", exact: true }).click();
  await expect(page.getByRole("heading", { name: talk.title })).toBeVisible();
  await page.getByRole("button", { name: "签到服务", exact: true }).click();
  await expect(page.getByText(/✓ 已签到/).first()).toBeVisible();
  await page.screenshot({
    path: fileURLToPath(new URL("ticket-v4.png", out)),
    fullPage: true,
  });
  await page.getByRole("button", { name: "评论与签名墙", exact: true }).click();
  await expect(page.getByRole("heading", { name: "评论记录" })).toBeVisible();
  await page.getByRole("button", { name: "签名墙", exact: true }).click();
  await expect(page.getByRole("heading", { name: "签名记录" })).toBeVisible();
  await page.getByRole("button", { name: "热点广场", exact: true }).click();
  await expect(page.locator(".blank-feature")).toBeEmpty();
  await page.goto(`${base}/?event=${event.id}`);
  await expect(
    page.getByRole("heading", { name: event.title, exact: true }),
  ).toBeVisible();
  await expect(page.locator("video").first()).toBeVisible();
  await page.getByRole("button", { name: "会议议程", exact: true }).click();
  await page
    .locator("article")
    .filter({
      has: page.getByRole("heading", { name: talk.title, exact: true }),
    })
    .getByRole("button", { name: "分享议程" })
    .click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(
    `talk=${talk.id}`,
  );
  await expect(page.getByRole("button", { name: "邀请同行" })).toHaveCount(0);
  await expect(
    page.locator("header nav").getByRole("button", { name: "地图导航" }),
  ).toHaveCount(0);
  await login("demo_pending");
  await page.goto(`${base}/?event=${event.id}`);
  await expect(page.getByText(/当前仅显示会议基本信息/)).toBeVisible();
  await expect(page.locator("video,iframe")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "会议议程", exact: true }),
  ).toHaveCount(0);
  await login("demo_admin");
  await page.getByRole("button", { name: "签到发布与结果" }).click();
  await expect(
    page.getByRole("heading", { name: "发布议程签到" }),
  ).toBeVisible();
  await page
    .locator("main section select")
    .first()
    .selectOption(String(event.id));
  await expect(
    page.getByAltText("管理员发布的签到入口二维码").first(),
  ).toBeVisible();
  await page.screenshot({
    path: fileURLToPath(new URL("admin-attendance-v4.png", out)),
    fullPage: true,
  });
  await page.getByRole("button", { name: "活动与内容", exact: true }).click();
  await expect(page.locator(".function-shell")).toHaveCount(1);
  await page.screenshot({
    path: fileURLToPath(new URL("admin-events-v4.png", out)),
    fullPage: true,
  });
  await login("demo_exhibit");
  await page.getByRole("button", { name: "新建办会申请" }).click();
  await expect(
    page.getByLabel("同时添加会议资料（每份20MB以内）"),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "我的办会申请", exact: true }),
  ).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "个人主页", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "展商个人主页" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
  await page.screenshot({
    path: fileURLToPath(new URL("profile-mobile-v4.png", out)),
    fullPage: true,
  });
  expect(errors).toEqual([]);
  console.log(
    `PASS V4: ${count} HTTP assertions; four-role browser flows; no page errors.`,
  );
  await writeFile(
    new URL("v4-result.json", out),
    JSON.stringify(
      {
        http: count,
        errors,
        eventId: event.id,
        talkId: talk.id,
        attendanceId: attendance.id,
        date: new Date().toISOString(),
      },
      null,
      2,
    ),
  );
} finally {
  await browser.close();
  for (const c of clients) await c.dispose();
}

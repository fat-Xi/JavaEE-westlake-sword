import { chromium, request, expect } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
const base = process.env.TEST_URL || "http://127.0.0.1:5174";
const out = "../test-results/v5";
await mkdir(out, { recursive: true });
let count = 0;
const clients = [];
async function call(c, path, method = "GET", data, code = 200) {
  const r = await c.fetch("/api" + path, { method, data });
  const text = await r.text();
  expect(r.status(), method + " " + path + ": " + text.slice(0, 500)).toBe(
    code,
  );
  count++;
  return text ? JSON.parse(text) : {};
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
const admin = await login("demo_admin"),
  user = await login("demo_user"),
  staff = await login("demo_staff"),
  vendor = await login("demo_exhibit"),
  pending = await login("demo_pending"),
  checked = await login("demo_checked");
const events = await call(admin, "/admin/events");
const event = events.find((e) => e.title === "【测试】全年签到体验会议");
expect(event).toBeTruthy();
expect(new Date(event.ends) - new Date(event.starts)).toBeGreaterThanOrEqual(
  365 * 86400000,
);
const attendance = (await call(admin, "/attendance?eventId=" + event.id))[0];
expect(attendance.starts).toBe(event.starts);
expect(attendance.ends).toBe(event.ends);
for (const c of [admin, user, staff, vendor]) {
  const p = await call(c, "/profile");
  for (const key of [
    "avatar",
    "nick",
    "realName",
    "gender",
    "email",
    "phone",
    "company",
  ])
    expect(p[key], key).toBeTruthy();
  const changed = {
    ...p,
    nick: p.nick + "测",
    tags: "数据安全",
    avatar: p.avatar,
  };
  await call(c, "/profile", "PUT", changed);
  expect((await call(c, "/me")).nick).toBe(changed.nick);
  const p2 = await call(c, "/profile");
  expect(p2.avatar).toBe(p.avatar);
  if (p.role !== "USER") expect(p2.tags).toBe("");
  await call(c, "/profile", "PUT", p);
}
await call(pending, "/events/" + event.id + "/polls", "GET", undefined, 403);
await call(pending, "/attendance?eventId=" + event.id, "GET", undefined, 403);
expect(
  (await call(checked, "/attendance?eventId=" + event.id))[0].checked,
).toBeTruthy();
const talk = (await call(admin, "/meetings/" + event.id)).talks[0];
await call(admin, "/attendance", "POST", {
  talkId: talk.id,
  starts: talk.starts,
  ends: talk.ends,
  enabled: true,
});
await call(
  admin,
  "/attendance",
  "POST",
  {
    talkId: talk.id,
    starts: "2020-01-01T00:00:00",
    ends: talk.ends,
    enabled: true,
  },
  400,
);
const stamp = Date.now(),
  end = event.ends;
const poll = {
  eventId: event.id,
  title: "回归投票 " + stamp,
  options: ["选项一", "选项二"],
  starts: event.starts,
  ends: end,
};
await call(vendor, "/polls", "POST", poll, 403);
await call(staff, "/polls", "POST", poll, 403);
await call(user, "/polls", "POST", poll, 403);
await call(
  admin,
  "/polls",
  "POST",
  { ...poll, options: ["同名", "同名"] },
  400,
);
await call(admin, "/polls", "POST", poll);
let row = (await call(user, "/events/" + event.id + "/polls")).find(
  (p) => p.title === poll.title,
);
const votePath = "/polls/" + row.id + "/vote";
const results = await Promise.all([
  user.post("/api" + votePath, { data: { optionId: row.options[0].id } }),
  user.post("/api" + votePath, { data: { optionId: row.options[0].id } }),
]);
expect(results.map((r) => r.status()).sort()).toEqual([200, 400]);
count += 2;
row = (await call(admin, "/events/" + event.id + "/polls")).find(
  (p) => p.id === row.id,
);
expect(row.options[0].total).toBe(1);
await call(admin, "/polls/" + row.id, "PUT", { enabled: false });
await call(checked, votePath, "POST", { optionId: row.options[1].id }, 400);
await call(admin, "/questions/1/vote", "POST", {}, 400);
const profile = await call(vendor, "/profile");
const post = {
  eventId: event.id,
  kind: "NEWS",
  title: "展商资讯 " + stamp,
  body: "本地图片和投稿权限回归",
  url: "",
  image: profile.avatar,
  product: "",
};
await call(user, "/posts", "POST", post, 400);
await call(admin, "/posts", "POST", post, 400);
await call(
  vendor,
  "/posts",
  "POST",
  { ...post, image: "https://example.com/x.png" },
  400,
);
await call(
  vendor,
  "/posts",
  "POST",
  { ...post, image: "data:image/png;base64,YWJj" },
  400,
);
for (const kind of ["NEWS", "RESULT", "EXHIBIT"]) {
  await call(vendor, "/posts", "POST", { ...post, kind, title: kind + stamp });
  const p = (await call(vendor, "/posts/mine")).find(
    (p) => p.title === kind + stamp,
  );
  expect(p.image).toBe(profile.avatar);
  expect(p.status).toBe("PENDING");
  await call(admin, "/review/posts/" + p.id, "POST", {
    pass: true,
    reason: "",
  });
  expect(
    (await call(user, "/events/" + event.id + "/posts")).some(
      (x) => x.id === p.id,
    ),
  ).toBe(true);
}
const future = new Date(Date.now() + 86400000 * 3),
  later = new Date(future.getTime() + 86400000);
function local(d) {
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 19);
}
const form = {
  title: "流程回归 " + stamp,
  place: "测试会场",
  starts: local(future),
  ends: local(later),
  capacity: 100,
  contact: "演示 / vendor@example.com",
  content: "会议介绍",
  news: "资讯内容",
  result: "成果内容",
  exhibit: "展商内容",
  image: profile.avatar,
};
const req = await call(vendor, "/requests", "POST", form);
await call(vendor, "/requests/" + req.id + "/submit", "POST");
await call(vendor, "/requests/" + req.id, "PUT", form, 400);
await call(vendor, "/requests/" + req.id + "/withdraw", "POST");
await call(vendor, "/requests/" + req.id, "PUT", {
  ...form,
  news: "修改后的资讯",
});
await call(vendor, "/requests/" + req.id + "/submit", "POST");
expect(
  (await call(vendor, "/requests/" + req.id + "/history")).length,
).toBeGreaterThanOrEqual(5);
await call(admin, "/requests/" + req.id + "/review", "POST", {
  pass: true,
  reason: "",
});
const created = await call(admin, "/requests/" + req.id + "/create", "POST", {
  ...form,
  status: "OPEN",
  liveUrl: "",
  replayUrl: "",
});
await call(
  admin,
  "/requests/" + req.id + "/create",
  "POST",
  { ...form, status: "OPEN", liveUrl: "", replayUrl: "" },
  400,
);
const materials = (await call(admin, "/review/posts")).filter(
  (p) => p.event_id === created.eventId,
);
expect(materials).toHaveLength(3);
expect(
  materials.every(
    (p) =>
      p.user_id === profile.user_id &&
      p.status === "APPROVED" &&
      p.image === profile.avatar,
  ),
).toBe(true);
expect(materials.find((p) => p.kind === "NEWS").body).toBe("修改后的资讯");
await call(admin, "/admin/stats");
const browser = await chromium.launch({ channel: "msedge", headless: true });
const errors = [];
for (const [name, c] of [
  ["admin", admin],
  ["user", user],
  ["vendor", vendor],
  ["staff", staff],
  ["pending", pending],
]) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    storageState: await c.storageState(),
  });
  const page = await context.newPage();
  page.on("pageerror", (e) => errors.push(name + ":" + e.message));
  await page.goto(base);
  await page.waitForTimeout(1000);
  await page.screenshot({ path: out + "/" + name + ".png", fullPage: true });
  if (name === "admin") {
    await page
      .getByRole("button", { name: "签到发布与结果", exact: true })
      .click();
    await page.waitForTimeout(500);
    await page.locator("select").first().selectOption(String(event.id));
    await expect(page.getByText("议程时间：")).toBeVisible();
    await page.screenshot({ path: out + "/attendance.png", fullPage: true });
    await page.getByRole("button", { name: "会议投票", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "投票发布与结果" }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "办会申请审核", exact: true })
      .click();
    const sample = page
      .locator(".row")
      .filter({ hasText: "【待审核演示】数据安全协作交流会" });
    await sample.getByRole("button", { name: "查看申请" }).click();
    const approve = page.getByRole("button", { name: "通过并进入会议发布" });
    if (await approve.count()) await approve.click();
    else
      await page
        .getByRole("button", { name: "进入会议发布（自动填入申请资料）" })
        .click();
    await expect(
      page.getByRole("heading", { name: "发布会议 · 资料已自动填入" }),
    ).toBeVisible();
    await expect(page.getByLabel("正式标题")).toHaveValue(
      "【待审核演示】数据安全协作交流会",
    );
  }
  if (["user", "pending"].includes(name)) {
    expect(await page.locator("header nav").innerText()).not.toContain(
      "签到服务",
    );
    await page
      .locator(".event-card")
      .filter({ hasText: event.title })
      .getByRole("button", { name: "查看活动详情" })
      .click();
    if (name === "pending") {
      await expect(
        page.getByText("当前仅显示会议基本信息。", { exact: false }),
      ).toBeVisible();
      expect(
        await page
          .getByRole("button", { name: "签到服务", exact: true })
          .count(),
      ).toBe(0);
    } else {
      for (const text of ["议程投票", "会议评论", "签名墙", "签到服务"]) {
        await page.getByRole("button", { name: text, exact: true }).click();
        await page.waitForTimeout(250);
      }
      await page.screenshot({ path: out + "/meeting.png", fullPage: true });
      await page.getByRole("button", { name: "发现活动", exact: true }).click();
      await page.setViewportSize({ width: 390, height: 844 });
      await page.screenshot({ path: out + "/mobile.png", fullPage: true });
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
    }
  }
  await context.close();
}
expect(errors).toEqual([]);
// 参考公开会务页面的信息层级，仅截取观察，不复制图片或品牌资产。
try {
  const page = await browser.newPage();
  await page.goto("https://www.huodongxing.com/", {
    timeout: 15000,
    waitUntil: "domcontentloaded",
  });
  await writeFile(
    out + "/reference.txt",
    (await page.title()) +
      "\n" +
      (await page.locator("body").innerText()).slice(0, 5000),
  );
} catch (e) {
  await writeFile(out + "/reference.txt", "公开参考页无法访问：" + e.message);
}
await browser.close();
for (const c of clients) await c.dispose();
await writeFile(
  out + "/result.json",
  JSON.stringify(
    {
      httpChecks: count,
      browserRoles: 5,
      errors,
      finished: new Date().toISOString(),
    },
    null,
    2,
  ),
);
console.log(
  "PASS",
  count,
  "HTTP checks, 5 roles and responsive browser checks",
);

// Run against the dedicated test database and test frontend, not the development site.
import { chromium, request, expect } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";

const base = process.env.TEST_URL || "http://127.0.0.1:5174";
if (["5173", "8080"].includes(new URL(base).port)) {
  throw new Error("Use the isolated test environment (5174/8081).");
}
const out = "../test-results/recommendations";
await mkdir(out, { recursive: true });
const clients = [];
const stamp = Date.now();
const origin = Math.floor(Date.now() / 60000) * 60000;
const local = (hours) => {
  const d = new Date(origin + hours * 3600000);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 19);
};
let count = 0;
let browser;

async function client() {
  const c = await request.newContext({
    baseURL: base,
    extraHTTPHeaders: { "X-App-Request": "1" },
  });
  clients.push(c);
  return c;
}
async function call(c, path, method = "GET", data, status = 200) {
  const response = await c.fetch("/api" + path, { method, data });
  const text = await response.text();
  expect(
    response.status(),
    method + " " + path + ": " + text.slice(0, 300),
  ).toBe(status);
  count++;
  return text ? JSON.parse(text) : {};
}

try {
  const admin = await client();
  await call(admin, "/login", "POST", {
    name: "demo_admin",
    password: "Demo12345!",
  });
  const anonymous = await client();

  async function participant(suffix, tags = "") {
    const name = `rec_${stamp}_${suffix}`;
    await call(anonymous, "/register", "POST", {
      name,
      password: "Recommend123!",
      role: "USER",
      nick: name,
      realName: "推荐功能测试",
      gender: "未设置",
      email: name + "@example.com",
      phone: "00000000000",
      company: "独立测试库",
      tags,
      avatar: "",
    });
    const users = await call(admin, "/admin/users");
    const user = users.find((u) => u.name === name);
    await call(admin, `/admin/users/${user.id}/review`, "POST", { pass: true });
    const c = await client();
    await call(c, "/login", "POST", { name, password: "Recommend123!" });
    return { c, id: user.id };
  }

  async function event(suffix) {
    const form = {
      title: `推荐验收-${stamp}-${suffix}`,
      place: "测试会场",
      starts: local(-2),
      ends: local(48),
      capacity: 50,
      content: "推荐功能独立验收数据",
      status: "OPEN",
      liveUrl: "",
      replayUrl: "",
    };
    await call(admin, "/admin/events", "POST", form);
    const rows = await call(admin, "/admin/events");
    return { ...form, id: rows.find((e) => e.title === form.title).id };
  }

  async function enroll(user, meeting, approved = true) {
    await call(user.c, `/events/${meeting.id}/join`, "POST", {
      channel: "direct",
    });
    if (approved) {
      const joins = await call(admin, "/admin/joins");
      const join = joins.find(
        (j) => j.user_id === user.id && j.event_id === meeting.id,
      );
      await call(admin, `/admin/joins/${join.id}`, "POST", { pass: true });
    }
  }

  async function talk(meeting, title, starts, ends, tags) {
    await call(admin, "/talks", "POST", {
      eventId: meeting.id,
      title,
      speaker: "测试嘉宾",
      room: title + "会场",
      starts: local(starts),
      ends: local(ends),
      tags,
    });
    const rows = await call(target.c, `/events/${meeting.id}/talks`);
    return rows.find((t) => t.title === title);
  }

  const target = await participant("user", "数据安全，ＡＩ; ai、Java　JAVA");
  const first = await participant("pop1");
  const second = await participant("pop2");
  const pending = await participant("wait");
  const meeting = await event("主会议");
  const other = await event("跨会议日程");
  const empty = await event("无推荐议程");
  for (const user of [target, first, second]) await enroll(user, meeting);
  await enroll(target, other);
  await enroll(target, empty);
  await enroll(pending, meeting, false);

  const owned = await talk(meeting, "已加入的议程", 4, 5, "数据安全");
  const ended = await talk(meeting, "已结束的议程", -1, -0.5, "数据安全");
  const matched = await talk(meeting, "双标签议程", 6, 7, "数据安全、AI");
  const java = await talk(meeting, "Java专场", 8, 9, "java");
  const javascript = await talk(
    meeting,
    "JavaScript专场",
    10,
    11,
    "JavaScript",
  );
  const popular = await talk(meeting, "热门议程", 12, 13, "云安全");
  const overlap = await talk(meeting, "跨会议冲突议程", 2, 3, "数据安全 AI");
  const adjacent = await talk(meeting, "相邻时段议程", 3, 4, "数据安全");
  const otherPlan = await talk(other, "其他会议中的个人日程", 2, 3, "");
  const emptyOwned = await talk(empty, "已全部加入日程", 20, 21, "");
  await talk(empty, "仅剩已结束议程", -1, -0.5, "");
  for (const row of [owned, otherPlan, emptyOwned]) {
    await call(target.c, `/talks/${row.id}/plan`, "POST");
  }
  for (const user of [first, second]) {
    await call(user.c, `/talks/${popular.id}/plan`, "POST");
  }

  const endpoint = `/events/${meeting.id}/recommend`;
  await call(anonymous, endpoint, "GET", undefined, 401);
  await call(pending.c, endpoint, "GET", undefined, 403);
  await call(admin, endpoint, "GET", undefined, 403);
  const staff = await client();
  await call(staff, "/login", "POST", {
    name: "demo_staff",
    password: "Demo12345!",
  });
  await call(staff, endpoint, "GET", undefined, 403);

  let rows = await call(target.c, endpoint);
  expect(rows.map((r) => r.id)).toEqual([
    matched.id,
    adjacent.id,
    java.id,
    popular.id,
    javascript.id,
    overlap.id,
  ]);
  expect(
    rows.some((r) => [owned.id, ended.id, otherPlan.id].includes(r.id)),
  ).toBe(false);
  expect(rows[0].matched_tags).toEqual(["数据安全", "AI"]);
  expect(rows[0].score).toBe(2);
  expect(rows[0].reason).toContain("匹配你的兴趣：数据安全、AI");
  expect(rows.find((r) => r.id === java.id).score).toBe(1);
  expect(rows.find((r) => r.id === javascript.id).score).toBe(0);
  expect(rows.find((r) => r.id === adjacent.id).conflicts).toEqual([]);
  expect(rows.at(-1).conflicts.map((r) => r.id)).toEqual([otherPlan.id]);
  expect(rows.at(-1).conflicts[0].event_title).toBe(other.title);
  expect(rows.find((r) => r.id === popular.id).popularity).toBe(2);
  for (const row of rows) {
    expect(row).not.toHaveProperty("live_url");
    expect(row).not.toHaveProperty("replay_url");
  }
  await call(target.c, `/talks/${overlap.id}/plan`, "POST", undefined, 400);
  expect(await call(target.c, `/events/${empty.id}/recommend`)).toEqual([]);

  const profile = await call(target.c, "/profile");
  await call(target.c, "/profile", "PUT", { ...profile, tags: "" });
  rows = await call(target.c, endpoint);
  expect(rows[0].id).toBe(popular.id);
  expect(rows[0].reason).toContain("2 人加入日程");
  expect(rows.every((r) => r.score === 0 && r.matched_tags.length === 0)).toBe(
    true,
  );
  await call(target.c, "/profile", "PUT", profile);

  browser = await chromium.launch({ channel: "msedge", headless: true });
  const context = await browser.newContext({
    storageState: await target.c.storageState(),
    viewport: { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(`${base}/?event=${meeting.id}&talk=${matched.id}`);
  const panel = page.getByRole("region", { name: "为你推荐", exact: true });
  await expect(
    panel.getByRole("heading", { name: matched.title }),
  ).toBeVisible();
  const matchedRow = panel.getByRole("article").filter({
    has: page.getByRole("heading", { name: matched.title, exact: true }),
  });
  await expect(
    matchedRow.getByText("匹配你的兴趣：数据安全、AI"),
  ).toBeVisible();
  await expect(panel.getByRole("button", { name: "时间冲突" })).toBeDisabled();
  await expect(panel.getByText(other.title, { exact: false })).toBeVisible();
  await panel.screenshot({ path: out + "/desktop.png" });

  await page.setViewportSize({ width: 390, height: 844 });
  await panel.screenshot({ path: out + "/mobile.png" });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await panel
    .getByRole("article")
    .filter({
      has: page.getByRole("heading", { name: matched.title, exact: true }),
    })
    .getByRole("button", { name: "加入日程", exact: true })
    .click();
  await expect(panel.getByRole("heading", { name: matched.title })).toHaveCount(
    0,
  );
  expect(
    (await call(target.c, "/my/plans")).some((p) => p.id === matched.id),
  ).toBe(true);
  await page
    .locator("article.section-block")
    .filter({
      has: page.getByRole("heading", { name: java.title, exact: true }),
    })
    .getByRole("button", { name: "加入个人日程", exact: true })
    .click();
  await expect(panel.getByRole("heading", { name: java.title })).toHaveCount(0);

  const routePattern = `**/api/events/${meeting.id}/recommend`;
  await page.route(routePattern, (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ message: "测试服务暂时不可用" }),
    }),
  );
  await panel.getByRole("button", { name: "刷新推荐" }).click();
  await expect(panel.getByRole("alert")).toContainText("测试服务暂时不可用");
  await page.unroute(routePattern);
  await panel.getByRole("button", { name: "刷新推荐" }).click();
  await expect(panel.getByRole("alert")).toHaveCount(0);
  await expect(
    panel.getByRole("heading", { name: popular.title }),
  ).toBeVisible();

  await page.goto(`${base}/?event=${empty.id}&talk=${emptyOwned.id}`);
  await expect(
    panel.getByText("暂无可推荐的议程。", { exact: false }),
  ).toBeVisible();
  expect(errors).toEqual([]);

  // Check the result cap and repeatable ordering when times and popularity tie.
  await call(target.c, "/profile", "PUT", { ...profile, tags: "" });
  const ties = [];
  for (let i = 0; i < 4; i++) {
    ties.push(await talk(meeting, `同分议程${i}`, 14, 15, ""));
  }
  const expected = [
    popular.id,
    adjacent.id,
    javascript.id,
    ...ties.slice(0, 3).map((t) => t.id),
  ];
  expect((await call(target.c, endpoint)).map((r) => r.id)).toEqual(expected);
  expect((await call(target.c, endpoint)).map((r) => r.id)).toEqual(expected);
  await call(admin, `/admin/events/${meeting.id}`, "PUT", {
    ...meeting,
    status: "CLOSED",
  });
  await call(target.c, endpoint, "GET", undefined, 404);
  await writeFile(
    out + "/result.json",
    JSON.stringify(
      {
        httpChecks: count,
        browserErrors: errors,
        finished: new Date().toISOString(),
      },
      null,
      2,
    ),
  );
  console.log(
    `PASS ${count} HTTP checks, recommendation interactions, error recovery and mobile layout`,
  );
} finally {
  if (browser) await browser.close();
  await Promise.all(clients.map((c) => c.dispose()));
}

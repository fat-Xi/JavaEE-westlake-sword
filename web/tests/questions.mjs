// Requires the isolated test database and test frontend (5174/8081).
import { chromium, request, expect } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
const base = process.env.TEST_URL || "http://127.0.0.1:5174";
if (["5173", "8080"].includes(new URL(base).port))
  throw new Error("Use the isolated test environment.");
const out = "../test-results/questions";
await mkdir(out, { recursive: true });
const clients = [],
  stamp = Date.now();
let count = 0,
  browser;
const local = (hours) => {
  const d = new Date(Date.now() + hours * 3600000);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 19);
};
async function client() {
  const c = await request.newContext({
    baseURL: base,
    extraHTTPHeaders: { "X-App-Request": "1" },
  });
  clients.push(c);
  return c;
}
async function call(c, path, method = "GET", data, status = 200) {
  const r = await c.fetch("/api" + path, { method, data });
  const text = await r.text();
  expect(r.status(), method + " " + path + ": " + text.slice(0, 300)).toBe(
    status,
  );
  count++;
  return text ? JSON.parse(text) : {};
}

try {
  const admin = await client(),
    anonymous = await client();
  await call(admin, "/login", "POST", {
    name: "demo_admin",
    password: "Demo12345!",
  });
  async function participant(suffix) {
    const name = `q_${stamp}_${suffix}`;
    await call(anonymous, "/register", "POST", {
      name,
      password: "Question123!",
      role: "USER",
      nick: `提问测试${suffix}`,
      realName: "测试参会者",
      gender: "未设置",
      email: name + "@example.com",
      phone: "00000000000",
      company: "独立测试",
      tags: "数据安全",
      avatar: "",
    });
    const user = (await call(admin, "/admin/users")).find(
      (u) => u.name === name,
    );
    await call(admin, `/admin/users/${user.id}/review`, "POST", { pass: true });
    const c = await client();
    await call(c, "/login", "POST", { name, password: "Question123!" });
    return { c, id: user.id };
  }
  async function event(suffix) {
    const form = {
      title: `提问验收-${stamp}-${suffix}`,
      place: "测试会场",
      starts: local(-2),
      ends: local(48),
      capacity: 50,
      content: "提问箱验收会议",
      status: "OPEN",
      liveUrl: "",
      replayUrl: "",
    };
    await call(admin, "/admin/events", "POST", form);
    return {
      ...form,
      id: (await call(admin, "/admin/events")).find(
        (e) => e.title === form.title,
      ).id,
    };
  }
  async function enroll(user, meeting, approved = true) {
    await call(user.c, `/events/${meeting.id}/join`, "POST", {
      channel: "direct",
    });
    if (approved) {
      const join = (await call(admin, "/admin/joins")).find(
        (j) => j.user_id === user.id && j.event_id === meeting.id,
      );
      await call(admin, `/admin/joins/${join.id}`, "POST", { pass: true });
    }
  }
  async function talk(meeting, title) {
    await call(admin, "/talks", "POST", {
      eventId: meeting.id,
      title,
      speaker: "测试嘉宾",
      room: title + "会场",
      starts: local(2),
      ends: local(3),
      tags: "数据安全",
    });
    return (await call(user.c, `/events/${meeting.id}/talks`)).find(
      (t) => t.title === title,
    );
  }
  async function submit(c, body, talkId = null, meetingId = meeting.id) {
    return (
      await call(c, "/questions", "POST", { eventId: meetingId, talkId, body })
    ).id;
  }
  async function approve(id) {
    await call(admin, `/review/questions/${id}`, "POST", { pass: true });
  }
  async function question(c, id) {
    return (await call(c, `/events/${meeting.id}/questions`)).find(
      (q) => q.id === id,
    );
  }
  const user = await participant("user"),
    peer = await participant("peer"),
    pending = await participant("wait"),
    outsider = await participant("out");
  const meeting = await event("主会议"),
    other = await event("其他会议"),
    ended = await event("结束会议");
  await enroll(user, meeting);
  await enroll(peer, meeting);
  await enroll(pending, meeting, false);
  await enroll(user, ended);
  const firstTalk = await talk(meeting, "数据安全议程"),
    secondTalk = await talk(meeting, "云安全议程"),
    otherTalk = await talk(other, "其他会议议程");
  const endpoint = `/events/${meeting.id}/questions`;

  await call(anonymous, endpoint, "GET", undefined, 401);
  await call(pending.c, endpoint, "GET", undefined, 403);
  await call(outsider.c, endpoint, "GET", undefined, 403);
  await call(
    pending.c,
    "/questions",
    "POST",
    { eventId: meeting.id, body: "未审核报名不能提问" },
    403,
  );
  await call(user.c, "/review/questions", "GET", undefined, 403);
  for (const name of ["demo_staff", "demo_exhibit"]) {
    const c = await client();
    await call(c, "/login", "POST", { name, password: "Demo12345!" });
    await call(c, endpoint, "GET", undefined, 403);
    await call(
      c,
      "/questions",
      "POST",
      { eventId: meeting.id, body: "角色越权" },
      403,
    );
  }
  for (const body of ["   ", "问".repeat(501)])
    await call(
      user.c,
      "/questions",
      "POST",
      { eventId: meeting.id, body },
      400,
    );
  await call(
    user.c,
    "/questions",
    "POST",
    { eventId: meeting.id, talkId: otherTalk.id, body: "错误会议议程" },
    404,
  );
  await call(
    user.c,
    "/questions",
    "POST",
    { eventId: meeting.id, talkId: 0, body: "错误议程" },
    400,
  );

  const global = (
    await call(user.c, "/questions", "POST", {
      eventId: meeting.id,
      body: "  全会问题  ",
    })
  ).id;
  const first = await submit(user.c, "如何开展数据安全治理？", firstTalk.id);
  const second = await submit(user.c, "云安全议程的问题", secondTalk.id);
  const secret = await submit(peer.c, "仅另一作者可见的待审问题", firstTalk.id);
  expect((await question(user.c, global)).talk_id).toBeNull();
  expect((await question(user.c, global)).body).toBe("全会问题");
  expect((await question(user.c, first)).status).toBe("PENDING");
  expect(await question(peer.c, first)).toBeUndefined();
  expect(await question(user.c, secret)).toBeUndefined();
  await call(user.c, `/questions/${first}/support`, "POST", undefined, 400);
  await call(
    admin,
    `/review/questions/${first}/state`,
    "PUT",
    { featured: true },
    400,
  );
  await call(
    admin,
    `/review/questions/${first}/answer`,
    "PUT",
    { answer: "未审核不能回答" },
    400,
  );
  await call(
    admin,
    `/review/questions/${global}`,
    "POST",
    { pass: false, reason: " " },
    400,
  );
  await call(admin, `/review/questions/${global}`, "POST", {
    pass: false,
    reason: "请补充背景",
  });
  expect((await question(user.c, global)).reason).toBe("请补充背景");
  expect(await question(peer.c, global)).toBeUndefined();
  await approve(first);
  await approve(second);
  expect((await question(peer.c, first)).talk_title).toBe(firstTalk.title);
  expect((await question(peer.c, first)).name).toBe("提问测试user");
  await call(
    peer.c,
    `/review/questions/${first}`,
    "POST",
    { pass: false, reason: "越权" },
    403,
  );
  await call(
    peer.c,
    `/review/questions/${first}/answer`,
    "PUT",
    { answer: "越权" },
    403,
  );
  await call(
    peer.c,
    `/review/questions/${first}/state`,
    "PUT",
    { closed: true },
    403,
  );
  await call(pending.c, `/questions/${first}/support`, "POST", undefined, 403);
  await call(outsider.c, `/questions/${first}/support`, "POST", undefined, 403);
  await call(admin, `/questions/${first}/support`, "POST", undefined, 403);
  await call(admin, `/questions/${first}/vote`, "POST", {}, 400);
  await call(admin, `/review/questions/${first}/state`, "PUT", {}, 400);
  await call(
    admin,
    `/review/questions/${first}/answer`,
    "PUT",
    { answer: " " },
    400,
  );

  const beforeVotes = (await call(admin, `/admin/stats?eventId=${meeting.id}`))
    .votes;
  const concurrent = await Promise.all(
    Array.from({ length: 6 }, () =>
      user.c.post(`/api/questions/${first}/support`),
    ),
  );
  expect(concurrent.map((r) => r.status())).toEqual(Array(6).fill(200));
  count += 6;
  expect((await question(user.c, first)).votes).toBe(1);
  expect((await question(user.c, first)).supported).toBe(true);
  await call(peer.c, `/questions/${first}/support`, "POST");
  expect((await question(user.c, first)).votes).toBe(2);
  expect(await call(user.c, "/votes")).toContain(first);
  await call(user.c, `/questions/${first}/support`, "DELETE");
  await call(user.c, `/questions/${first}/support`, "DELETE");
  expect((await question(peer.c, first)).votes).toBe(1);
  expect((await question(peer.c, first)).supported).toBe(true);
  expect((await call(admin, `/admin/stats?eventId=${meeting.id}`)).votes).toBe(
    beforeVotes,
  );
  expect(
    (await call(user.c, endpoint)).filter((q) => q.status === "APPROVED")[0].id,
  ).toBe(first);
  await call(admin, `/review/questions/${second}/state`, "PUT", {
    featured: true,
  });
  expect((await call(user.c, endpoint))[0].id).toBe(second);
  await call(admin, `/review/questions/${second}`, "POST", {
    pass: false,
    reason: "精选问题下架",
  });
  const rejected = (await call(admin, "/review/questions")).find(
    (q) => q.id === second,
  );
  expect(rejected.featured).toBe(false);
  expect(await question(peer.c, second)).toBeUndefined();
  const stateUpdates = await Promise.all([
    admin.put(`/api/review/questions/${first}/state`, {
      data: { featured: true },
    }),
    admin.put(`/api/review/questions/${first}/state`, {
      data: { closed: true },
    }),
  ]);
  expect(stateUpdates.map((r) => r.status())).toEqual([200, 200]);
  count += 2;
  let row = await question(user.c, first);
  expect(row.featured).toBe(true);
  expect(row.closed).toBe(true);
  await call(user.c, `/questions/${first}/support`, "POST", undefined, 400);
  await call(peer.c, `/questions/${first}/support`, "DELETE");
  expect((await question(user.c, first)).votes).toBe(0);
  await call(admin, `/review/questions/${first}/answer`, "PUT", {
    answer: "  请先完成资产梳理和风险评估。  ",
  });
  expect((await question(user.c, first)).answer).toBe(
    "请先完成资产梳理和风险评估。",
  );
  expect(
    (await call(user.c, "/notes")).some((n) =>
      n.body.includes("收到主办方答复"),
    ),
  ).toBe(true);
  await call(admin, `/review/questions/${first}/state`, "PUT", {
    closed: false,
  });
  const race = await Promise.all([
    admin.put(`/api/review/questions/${first}/state`, {
      data: { closed: true },
    }),
    user.c.post(`/api/questions/${first}/support`),
  ]);
  expect(race[0].status()).toBe(200);
  expect([200, 400]).toContain(race[1].status());
  count += 2;
  row = await question(user.c, first);
  expect(row.closed).toBe(true);
  expect([0, 1]).toContain(row.votes);
  await call(user.c, `/questions/${first}/support`, "POST", undefined, 400);
  await call(user.c, `/questions/${first}/support`, "DELETE");
  await call(admin, `/talks/${firstTalk.id}`, "DELETE", undefined, 400);

  const oldQuestion = await submit(user.c, "会后保留的问题", null, ended.id);
  await approve(oldQuestion);
  await call(admin, `/admin/events/${ended.id}`, "PUT", {
    ...ended,
    starts: local(-3),
    ends: local(-1),
  });
  expect(
    (await call(user.c, `/events/${ended.id}/questions`)).some(
      (q) => q.id === oldQuestion,
    ),
  ).toBe(true);
  await call(
    user.c,
    "/questions",
    "POST",
    { eventId: ended.id, body: "会后新增问题" },
    400,
  );
  await call(
    user.c,
    `/questions/${oldQuestion}/support`,
    "POST",
    undefined,
    400,
  );

  browser = await chromium.launch({ channel: "msedge", headless: true });
  const userContext = await browser.newContext({
    storageState: await user.c.storageState(),
    viewport: { width: 1440, height: 1000 },
  });
  const adminContext = await browser.newContext({
    storageState: await admin.storageState(),
    viewport: { width: 1440, height: 1000 },
  });
  const page = await userContext.newPage(),
    adminPage = await adminContext.newPage(),
    errors = [];
  for (const p of [page, adminPage])
    p.on("pageerror", (e) => errors.push(e.message));
  await page.goto(`${base}/?event=${meeting.id}`);
  await page.getByRole("button", { name: "会议提问", exact: true }).click();
  const box = page.getByRole("region", { name: "会议提问箱", exact: true });
  const browserBody = `浏览器提交的问题-${stamp}`;
  await box
    .getByRole("combobox", { name: "关联议程", exact: true })
    .selectOption(String(firstTalk.id));
  await box.getByLabel("问题", { exact: true }).fill(browserBody);
  await box.getByRole("button", { name: "提交问题审核" }).click();
  await expect(box.getByLabel("问题", { exact: true })).toHaveValue("");
  await expect(
    box.getByRole("region", { name: "我的未公开问题" }).getByText(browserBody),
  ).toBeVisible();
  expect(
    (await call(peer.c, endpoint)).some((q) => q.body === browserBody),
  ).toBe(false);

  const submitted = (await call(user.c, endpoint)).find(
    (q) => q.body === browserBody,
  );
  const beijing = new Date(new Date(submitted.created).getTime() + 8 * 3600000)
    .toISOString()
    .slice(0, 16)
    .replace("T", " ")
    .replaceAll("-", "/");
  await expect(
    box
      .getByRole("region", { name: "我的未公开问题" })
      .getByRole("article")
      .filter({
        has: page.getByRole("heading", { name: browserBody, exact: true }),
      })
      .locator("time"),
  ).toHaveText(beijing);

  await adminPage.goto(base);
  await adminPage
    .getByRole("button", { name: "内容审核", exact: true })
    .click();
  const manager = adminPage.getByRole("region", {
    name: "提问箱管理",
    exact: true,
  });
  await manager
    .getByRole("combobox", { name: "提问所属会议", exact: true })
    .selectOption(String(meeting.id));
  const adminRow = manager.getByRole("article").filter({
    has: adminPage.getByRole("heading", { name: browserBody, exact: true }),
  });
  await adminRow.getByRole("button", { name: "通过提问", exact: true }).click();
  await expect(adminRow).toHaveCount(0);
  await adminPage
    .getByRole("combobox", { name: "审核状态", exact: true })
    .selectOption("APPROVED");
  await adminRow.getByRole("button", { name: "设为精选", exact: true }).click();
  await expect(
    adminRow.getByRole("button", { name: "取消精选", exact: true }),
  ).toBeVisible();
  await adminRow
    .getByLabel("主办方人工答复", { exact: true })
    .fill("这是通过管理页面发布的答复。");
  await adminRow.getByRole("button", { name: "发布答复", exact: true }).click();
  await expect(
    adminRow.getByRole("button", { name: "更新答复", exact: true }),
  ).toBeVisible();
  await adminRow.getByRole("button", { name: "关闭问题", exact: true }).click();
  await expect(
    adminRow.getByRole("button", { name: "重新开放问题", exact: true }),
  ).toBeVisible();
  await adminPage.locator(".notice button").click();
  await manager.screenshot({ path: out + "/admin.png" });
  await box.getByRole("button", { name: "刷新问题", exact: true }).click();
  const publicRow = box
    .getByRole("region", { name: "公开问题", exact: true })
    .getByRole("article")
    .filter({
      has: page.getByRole("heading", { name: browserBody, exact: true }),
    });
  await expect(
    publicRow.getByText("这是通过管理页面发布的答复。"),
  ).toBeVisible();
  await expect(
    publicRow.getByRole("button", { name: "赞同 · 0", exact: true }),
  ).toBeDisabled();
  await adminRow
    .getByRole("button", { name: "重新开放问题", exact: true })
    .click();
  await box.getByRole("button", { name: "刷新问题", exact: true }).click();
  await publicRow
    .getByRole("button", { name: "赞同 · 0", exact: true })
    .click();
  await expect(
    publicRow.getByRole("button", { name: "撤销赞同 · 1", exact: true }),
  ).toBeVisible();
  await publicRow
    .getByRole("button", { name: "撤销赞同 · 1", exact: true })
    .click();
  await expect(
    publicRow.getByRole("button", { name: "赞同 · 0", exact: true }),
  ).toBeVisible();
  await box
    .getByRole("combobox", { name: "筛选议程", exact: true })
    .selectOption("MEETING");
  await expect(publicRow).toHaveCount(0);
  await box
    .getByRole("combobox", { name: "筛选议程", exact: true })
    .selectOption(String(firstTalk.id));
  await expect(publicRow).toBeVisible();
  await box
    .getByRole("combobox", { name: "问题状态", exact: true })
    .selectOption("ANSWERED");
  await box
    .getByRole("combobox", { name: "排序方式", exact: true })
    .selectOption("new");
  await page.locator(".notice button").click();
  await box.screenshot({ path: out + "/desktop.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await box.screenshot({ path: out + "/mobile.png" });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  const route = `**/api/events/${meeting.id}/questions`;
  await page.route(route, (r) =>
    r.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ message: "测试问题加载失败" }),
    }),
  );
  await box.getByRole("button", { name: "刷新问题", exact: true }).click();
  await expect(box.getByRole("alert")).toContainText("测试问题加载失败");
  await page.unroute(route);
  await box.getByRole("button", { name: "刷新问题", exact: true }).click();
  await expect(publicRow).toBeVisible();
  await page.goto(`${base}/?event=${ended.id}`);
  await page.getByRole("button", { name: "会议提问", exact: true }).click();
  await expect(
    box.getByText("会议已结束，停止征集新问题", { exact: false }),
  ).toBeVisible();
  await expect(box.getByRole("button", { name: "提交问题审核" })).toHaveCount(
    0,
  );
  expect(errors).toEqual([]);
  await call(admin, `/admin/events/${meeting.id}`, "PUT", {
    ...meeting,
    status: "CLOSED",
  });
  await call(user.c, endpoint, "GET", undefined, 404);
  await call(user.c, `/questions/${first}/support`, "POST", undefined, 404);
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
    `PASS ${count} HTTP checks, question moderation/support workflow and mobile layout`,
  );
} finally {
  if (browser) await browser.close();
  await Promise.all(clients.map((c) => c.dispose()));
}

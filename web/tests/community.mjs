import { chromium, request, expect } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
const base = "http://127.0.0.1:5174",
  out = "../test-results/community";
await mkdir(out, { recursive: true });
let count = 0;
const clients = [];
async function call(c, p, method = "GET", data, status = 200) {
  const r = await c.fetch("/api" + p, { method, data });
  const t = await r.text();
  expect(r.status(), p + t.slice(0, 150)).toBe(status);
  count++;
  return t ? JSON.parse(t) : {};
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
  u = await login("demo_user"),
  o = await login("demo_checked"),
  p = await login("demo_pending");
const event = (await call(a, "/admin/events")).find(
  (e) => e.title === "【测试】全年签到体验会议",
);
const stamp = Date.now(),
  rows = [];
for (const [i, c] of [u, o].entries()) {
  for (const kind of ["COMMENT", "WALL"]) {
    const title = kind + "-" + i + "-" + stamp;
    await call(c, "/posts", "POST", {
      eventId: event.id,
      kind,
      title,
      body: "多人公开记录验证",
      url: "",
      image: "",
      product: "",
    });
    const row = (await call(c, "/posts/mine")).find((r) => r.title === title);
    expect(row.nick).toBeTruthy();
    expect(row.avatar).toMatch(/^data:image/);
    expect(row.event_title).toBe(event.title);
    expect(row.created).toBeTruthy();
    expect(
      (await call(i ? u : o, "/events/" + event.id + "/posts")).some(
        (r) => r.id === row.id,
      ),
    ).toBe(false);
    await call(a, "/review/posts/" + row.id, "POST", { pass: true });
    rows.push({ ...row, owner: i });
  }
}
for (const c of [u, o]) {
  const list = await call(c, "/events/" + event.id + "/posts");
  for (const row of rows) {
    const x = list.find((r) => r.id === row.id);
    expect(x.nick).toBe(row.nick);
    expect(x.avatar).toBe(row.avatar);
    expect(x.created).toBeTruthy();
  }
}
await call(p, "/events/" + event.id + "/posts", "GET", undefined, 403);
const privateIds = [];
for (const kind of ["COMMENT", "WALL"]) {
  for (const status of ["PENDING", "REJECTED"]) {
    const title = kind + status + stamp;
    await call(u, "/posts", "POST", {
      eventId: event.id,
      kind,
      title,
      body: "仅本人可见的审核记录",
      url: "",
      image: "",
      product: "",
    });
    const row = (await call(u, "/posts/mine")).find((r) => r.title === title);
    privateIds.push(row.id);
    if (status === "REJECTED")
      await call(a, "/review/posts/" + row.id, "POST", {
        pass: false,
        reason: "测试驳回原因",
      });
  }
}
expect(
  (await call(o, "/posts/mine")).some((r) => privateIds.includes(r.id)),
).toBe(false);
expect(
  (await call(o, "/events/" + event.id + "/posts")).some((r) =>
    privateIds.includes(r.id),
  ),
).toBe(false);
const browser = await chromium.launch({ channel: "msedge", headless: true });
const ctx = await browser.newContext({
  storageState: await u.storageState(),
  viewport: { width: 1366, height: 1000 },
});
const page = await ctx.newPage(),
  errors = [];
page.on("pageerror", (e) => errors.push(e.message));
await page.goto(base);
await page
  .locator(".event-card")
  .filter({ hasText: event.title })
  .getByRole("button", { name: "查看活动详情" })
  .click();
for (const [kind, label] of [
  ["COMMENT", "会议评论"],
  ["WALL", "签名墙"],
]) {
  await page.getByRole("button", { name: label, exact: true }).click();
  for (const row of rows.filter((r) => r.kind === kind)) {
    const card = page.locator(
      "[data-post-id=" + JSON.stringify(String(row.id)) + "]",
    );
    await expect(card.getByRole("img")).toBeVisible();
    await expect(card.locator("strong")).toHaveText(row.nick);
    await expect(card.locator("time")).not.toBeEmpty();
  }
  await page.screenshot({ path: out + "/" + kind + ".png", fullPage: true });
}
await page.getByRole("button", { name: "会议评论", exact: true }).click();
const title = "界面发表" + stamp;
await page.getByLabel("标题", { exact: true }).fill(title);
await page
  .getByLabel("正文", { exact: true })
  .fill("浏览器提交并在个人主页查询");
await page.getByRole("button", { name: "提交审核", exact: true }).click();
await expect(page.getByLabel("标题", { exact: true })).toHaveValue("");
await expect(
  page.getByRole("heading", { name: title, exact: true }),
).toBeVisible();
await page.getByRole("button", { name: "个人主页", exact: true }).click();
for (const [kind, label] of [
  ["COMMENT", "我的评论"],
  ["WALL", "我的签名"],
]) {
  await page.getByRole("button", { name: label, exact: true }).click();
  for (const row of rows.filter((r) => r.kind === kind && r.owner === 0))
    await expect(
      page.locator("[data-post-id=" + JSON.stringify(String(row.id)) + "]"),
    ).toContainText(event.title);
  for (const row of rows.filter((r) => r.owner === 1))
    await expect(
      page.locator("[data-post-id=" + JSON.stringify(String(row.id)) + "]"),
    ).toHaveCount(0);
  await expect(
    page.getByText("测试驳回原因", { exact: false }).last(),
  ).toBeVisible();
  if (kind === "COMMENT")
    await expect(
      page.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({ path: out + "/my-" + kind + ".png", fullPage: true });
}
expect(errors).toEqual([]);
await browser.close();
for (const c of clients) await c.dispose();
await writeFile(
  out + "/result.json",
  JSON.stringify(
    { count, errors, multipleAuthors: true, privateRecords: true },
    null,
    2,
  ),
);
console.log(
  "PASS community",
  count,
  "HTTP checks; author display, moderation, personal records, submit reset and mobile layout.",
);

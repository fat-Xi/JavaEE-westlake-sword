import { chromium, request, expect } from "@playwright/test";
import { writeFile, readFile } from "node:fs/promises";
const base = process.env.TEST_URL || "http://127.0.0.1:5174";
let count = 0;
const clients = [];
async function login(name) {
  const c = await request.newContext({
    baseURL: base,
    extraHTTPHeaders: { "X-App-Request": "1" },
  });
  clients.push(c);
  await c.post("/api/login", { data: { name, password: "Demo12345!" } });
  return c;
}
async function call(c, path, method = "GET", data, code = 200) {
  const r = await c.fetch("/api" + path, { method, data });
  expect(r.status(), path + (await r.text())).toBe(code);
  count++;
  return (await r.text()) ? await r.json() : {};
}
const a = await login("demo_admin"),
  e = await login("demo_exhibit"),
  u = await login("demo_user"),
  p = await login("demo_pending");
const event = (await call(a, "/admin/events")).find(
  (e) => e.title === "【测试】全年签到体验会议",
);
const rows = (await call(a, "/meetings/" + event.id)).talks;
const talk = rows[0];
const browser = await chromium.launch({ channel: "msedge", headless: true });
const page = await browser.newPage();
await page.goto(base);
// 固定短视频由 ffmpeg 的纯色源生成，无外部素材。
const bytes = await readFile(
  new URL("./fixtures/replay.webm", import.meta.url),
);
let r = await a.post("/api/talks/" + talk.id + "/replay", {
  multipart: {
    file: { name: "test.webm", mimeType: "video/webm", buffer: bytes },
  },
});
expect(r.status(), await r.text()).toBe(200);
count++;
expect((await u.get("/api/talks/" + talk.id + "/replay")).status()).toBe(400);
count++;
expect((await p.get("/api/talks/" + talk.id + "/replay")).status()).toBe(403);
count++;
r = await a.get("/api/talks/" + talk.id + "/replay", {
  headers: { Range: "bytes=0-99" },
});
expect(r.status()).toBe(206);
expect((await r.body()).length).toBe(100);
count++;
const context = await browser.newContext({
  storageState: await a.storageState(),
});
const adminPage = await context.newPage();
await adminPage.goto(base);
const playback = await adminPage.evaluate(async (id) => {
  const video = document.createElement("video");
  video.src = "/api/talks/" + id + "/replay";
  document.body.append(video);
  return await new Promise((resolve) => {
    video.onloadedmetadata = () =>
      resolve({ width: video.videoWidth, height: video.videoHeight });
    video.onerror = () => resolve({ error: video.error?.message });
    setTimeout(() => resolve({ timeout: true }), 10000);
  });
}, talk.id);
expect(playback.width).toBe(320);
expect(playback.height).toBe(180);
const info = (await call(a, "/meetings/" + event.id)).talks.find(
  (t) => t.id === talk.id,
);
await call(a, "/talks/" + talk.id + "/media", "PUT", {
  liveUrl: "",
  replayUrl: info.replay_url,
  liveOn: false,
});
r = await a.post("/api/talks/" + talk.id + "/replay", {
  multipart: {
    file: {
      name: "invalid.mp4",
      mimeType: "video/webm",
      buffer: Buffer.from("not a video data at all"),
    },
  },
});
expect(r.status()).toBe(400);
count++;
r = await e.post("/api/talks/" + talk.id + "/replay", {
  multipart: {
    file: { name: "test.webm", mimeType: "video/webm", buffer: bytes },
  },
});
expect(r.status()).toBe(403);
count++;
const form = {
  title: "附件数据库回归 " + Date.now(),
  place: "测试厅",
  starts: "2027-01-01T10:00:00",
  ends: "2027-01-01T12:00:00",
  capacity: 20,
  contact: "test@example.com",
  content: "文件数据库保存测试",
};
const req = await call(e, "/requests", "POST", form);
const content = Buffer.from("资料保存数据库验证");
r = await e.post("/api/requests/" + req.id + "/files", {
  multipart: {
    file: { name: "测试资料.txt", mimeType: "text/plain", buffer: content },
  },
});
expect(r.status()).toBe(200);
count++;
const file = (await call(e, "/requests/" + req.id + "/files"))[0];
r = await e.get("/api/requests/" + req.id + "/files/" + file.id + "/download");
expect(await r.body()).toEqual(content);
count++;
await call(e, "/requests/" + req.id + "/submit", "POST");
await call(a, "/requests/" + req.id + "/review", "POST", { pass: true });
const created = await call(a, "/requests/" + req.id + "/create", "POST", {
  ...form,
  status: "OPEN",
  liveUrl: "",
  replayUrl: "",
});
const attached = (await call(a, "/events/" + created.eventId + "/files"))[0];
r = await a.get("/api/files/" + attached.id + "/download");
expect(await r.body()).toEqual(content);
count++;
await call(p, "/files/" + attached.id + "/download", "GET", undefined, 403);
// 实际文件选择、图片预览和保存，确认由浏览器生成位图再写入数据库。
const vendorContext = await browser.newContext({
  storageState: await e.storageState(),
});
const vendorPage = await vendorContext.newPage();
await vendorPage.goto(base);
await vendorPage
  .getByRole("button", { name: "会议资讯、成果与展商资料", exact: true })
  .click();
await vendorPage
  .getByRole("button", { name: "提交新资料", exact: true })
  .click();
await vendorPage.getByLabel("内容类型").selectOption("NEWS");
await vendorPage.getByLabel("标题", { exact: true }).fill("浏览器本地图片测试");
await vendorPage
  .getByLabel("正文", { exact: true })
  .fill("上传本地图片后保存。");
const avatar = (await call(e, "/profile")).avatar;
await vendorPage.locator("input[type=file]").setInputFiles({
  name: "picture.png",
  mimeType: "image/png",
  buffer: Buffer.from(avatar.split(",")[1], "base64"),
});
await expect(vendorPage.getByAltText("所选图片预览")).toBeVisible();
await vendorPage.getByRole("button", { name: "提交审核", exact: true }).click();
await expect(
  vendorPage.getByRole("heading", { name: "我的投稿资料", exact: true }),
).toBeVisible();
expect((await call(e, "/posts/mine"))[0].image).toMatch(
  /^data:image\/png;base64,/,
);
await browser.close();
for (const c of clients) await c.dispose();
await writeFile(
  "../test-results/v5/files-result.json",
  JSON.stringify({ checks: count, playback }, null, 2),
);
console.log(
  "PASS",
  count,
  "file/replay checks; real WebM decoded; local image form saved.",
);

import { chromium, request, expect } from "@playwright/test";
import QRCode from "qrcode";
import { mkdir, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import path from "node:path";
const base = process.env.TEST_URL || "http://127.0.0.1:5174";
const out = path.resolve("../test-results/v6");
await mkdir(out, { recursive: true });
let count = 0;
const errors = [];
const clients = [];
async function call(c, p, method = "GET", data, status = 200) {
  const r = await c.fetch("/api" + p, { method, data });
  expect(r.status(), p + " " + (await r.text())).toBe(status);
  count++;
  return (await r.text()) ? await r.json() : {};
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
  e = await login("demo_exhibit"),
  u = await login("demo_user"),
  s = await login("demo_staff"),
  wait = await login("demo_wait");
const event = (await call(a, "/admin/events")).find(
  (x) => x.title === "【测试】全年签到体验会议",
);
const session = (await call(s, "/attendance?eventId=" + event.id))[0];
expect(session.talk_starts).toBeTruthy();
expect(session.room).toBeTruthy();
await call(wait, "/events", "GET", undefined, 403);
expect((await call(wait, "/me")).status).toBe("PENDING");
const ticket = await call(u, "/events/" + event.id + "/ticket");
await call(
  s,
  "/attendance/scan",
  "POST",
  { sessionId: session.id, token: "https://example.com/?attendance=1" },
  400,
);
await call(
  s,
  "/attendance/scan",
  "POST",
  { sessionId: session.id, token: "00000000-0000-0000-0000-000000000000" },
  400,
);
const req = (await call(e, "/requests")).find(
  (x) => x.image && x.title.includes("数据安全协作交流会"),
);
expect(req).toBeTruthy();
const files = await call(e, "/requests/" + req.id + "/files");
expect(files.length).toBeGreaterThan(0);
const download = await e.get(
  "/api/requests/" + req.id + "/files/" + files[0].id + "/download",
);
expect(download.status()).toBe(200);
expect((await download.body()).length).toBeGreaterThan(100);
count++;
const posts = await call(u, "/events/" + event.id + "/posts");
expect(posts.some((p) => p.kind === "COMMENT")).toBe(true);
expect(posts.some((p) => p.kind === "WALL")).toBe(true);
await QRCode.toFile(path.join(out, "ticket.png"), ticket.token, {
  width: 220,
  margin: 4,
});
await QRCode.toFile(
  path.join(out, "entry.png"),
  base + "/?event=" + event.id + "&attendance=" + session.id,
  { width: 280, margin: 4 },
);
const ffmpeg =
  "C:/Users/14631/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/Lib/site-packages/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe";
execFileSync(ffmpeg, [
  "-y",
  "-loop",
  "1",
  "-i",
  path.join(out, "ticket.png"),
  "-vf",
  "pad=640:480:(ow-iw)/2:(oh-ih)/2:white,format=yuv420p",
  "-t",
  "2",
  "-r",
  "10",
  path.join(out, "camera.y4m"),
  "-loglevel",
  "error",
]);
const browser = await chromium.launch({
  channel: "msedge",
  headless: true,
  args: [
    "--use-fake-ui-for-media-stream",
    "--use-fake-device-for-media-stream",
    "--use-file-for-fake-video-capture=" + path.join(out, "camera.y4m"),
  ],
});
async function pageFor(c) {
  const ctx = await browser.newContext({
    viewport: { width: 1366, height: 1000 },
    storageState: await c.storageState(),
    permissions: ["camera"],
  });
  const p = await ctx.newPage();
  p.on("pageerror", (x) => errors.push(x.message));
  await p.goto(base);
  return p;
}
const staff = await pageFor(s);
await staff.getByRole("button", { name: "扫码签到", exact: true }).click();
await expect(staff.getByRole("combobox").first()).toHaveValue(String(event.id));
await expect(
  staff.getByRole("button", { name: "开启摄像头扫码" }),
).toBeEnabled();
await staff.getByRole("button", { name: "开启摄像头扫码" }).click();
await expect(staff.locator(".status-ok")).toContainText(/签到成功|已签到/, {
  timeout: 20000,
});
expect(
  (await call(u, "/attendance?eventId=" + event.id))[0].checked,
).toBeTruthy();
await staff
  .getByLabel("识别个人签到码图片")
  .setInputFiles(path.join(out, "ticket.png"));
await expect(staff.locator(".status-ok")).toContainText("已签到，请勿重复核销");
await staff
  .getByLabel("识别个人签到码图片")
  .setInputFiles(path.join(out, "entry.png"));
await expect(staff.getByRole("alert")).toContainText("会议入口二维码");
await staff.screenshot({ path: path.join(out, "scan.png"), fullPage: true });
const vendor = await pageFor(e);
await vendor
  .getByRole("button", { name: "会议资讯、成果与展商资料", exact: true })
  .click();
const mine = await call(e, "/posts/mine");
for (const [kind, label] of [
  ["NEWS", "资讯"],
  ["RESULT", "成果展示"],
  ["EXHIBIT", "展商展览"],
]) {
  const post = mine.find((p) => p.kind === kind);
  expect(post).toBeTruthy();
  await vendor
    .locator(".row")
    .filter({ hasText: post.title })
    .getByRole("button", { name: "修改并重新送审" })
    .click();
  await expect(vendor.locator(".content-type")).toContainText(label);
  await expect(vendor.getByLabel("标题", { exact: true })).toHaveValue(
    post.title,
  );
  await expect(
    vendor.getByRole("button", { name: "修改并重新送审" }),
  ).toHaveCount(0);
  await expect(vendor.locator("form.editor")).toHaveCount(1);
  await vendor.getByRole("button", { name: "提交审核", exact: true }).click();
  await expect(
    vendor.getByRole("heading", { name: "我的投稿资料", exact: true }),
  ).toBeVisible();
  expect(
    (await call(e, "/posts/mine")).find((p) => p.id === post.id).kind,
  ).toBe(kind);
}
await vendor
  .getByRole("button", { name: "办会申请与附件", exact: true })
  .click();
await vendor
  .locator(".row")
  .filter({ hasText: req.title })
  .getByRole("button", { name: "查看申请" })
  .click();
await expect(vendor.getByRole("button", { name: "查看申请" })).toHaveCount(0);
await expect(vendor.getByAltText("申请配图")).toBeVisible();
await expect(
  vendor.getByRole("link", { name: /会议筹备与资料清单/ }),
).toBeVisible();
const admin = await pageFor(a);
await admin.getByRole("button", { name: "查看完整信息" }).first().click();
await expect(admin.getByRole("button", { name: "编辑 / 上下架" })).toHaveCount(
  0,
);
await admin.getByRole("button", { name: "收起会议详情" }).click();
await admin.getByRole("button", { name: "编辑 / 上下架" }).first().click();
await expect(admin.getByRole("button", { name: "查看完整信息" })).toHaveCount(
  0,
);
await expect(
  admin.getByRole("heading", { name: "议程设置", exact: true }),
).toHaveCount(0);
await admin.getByRole("button", { name: "取消", exact: true }).click();
await admin.getByRole("button", { name: "内容审核", exact: true }).click();
await admin.getByRole("button", { name: "修订资料并重新送审" }).first().click();
await expect(admin.locator(".review-row")).toHaveCount(0);
await expect(admin.locator("form.editor")).toHaveCount(1);
const pending = await browser.newPage();
await pending.goto(base);
await pending.getByLabel("账号", { exact: true }).fill("demo_wait");
await pending.getByLabel("密码", { exact: true }).fill("Demo12345!");
await pending.getByRole("button", { name: "登录", exact: true }).click();
await expect(pending.getByRole("status")).toContainText(
  "身份资料正在等待管理员审核",
);
await expect(pending.locator("header nav button")).toHaveText(["个人主页"]);
await pending.reload();
await expect(pending.getByRole("status")).toContainText(
  "身份资料正在等待管理员审核",
);
await pending.screenshot({
  path: path.join(out, "pending.png"),
  fullPage: true,
});
// 从注册界面创建的新用户也必须出现持久审核提示。
const fresh = await browser.newPage();
await fresh.goto(base);
await fresh.getByRole("button", { name: "还没有账号？去注册" }).click();
await fresh.getByLabel("网名", { exact: true }).fill("新注册测试");
await fresh.getByLabel("真实姓名", { exact: true }).fill("演示注册用户");
await fresh.getByLabel("邮箱", { exact: true }).fill("new@example.com");
await fresh.getByLabel("电话", { exact: true }).fill("00000000000");
const account = "v6_" + Date.now();
await fresh.getByLabel("账号", { exact: true }).fill(account);
await fresh.getByLabel("密码", { exact: true }).fill("Demo12345!");
await fresh.getByRole("button", { name: "注册", exact: true }).click();
await expect(fresh.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
await fresh.getByLabel("密码", { exact: true }).fill("Demo12345!");
await fresh.getByRole("button", { name: "登录", exact: true }).click();
await expect(fresh.getByRole("status")).toContainText(
  "身份资料正在等待管理员审核",
);
await fresh.getByRole("button", { name: "保存个人信息", exact: true }).click();
await expect(
  fresh.getByText(
    "账号已注册，身份资料正在等待管理员审核。审核通过前仅能查看和完善个人资料，暂不能报名或进入会议服务。",
    { exact: true },
  ),
).toBeVisible();
// 模拟浏览器拒绝相机权限，确认不会只留下空白扫描区。
const denied = await pageFor(s);
await denied.evaluate(() => {
  navigator.mediaDevices.getUserMedia = async () => {
    throw new DOMException("Permission denied", "NotAllowedError");
  };
});
await denied.getByRole("button", { name: "扫码签到", exact: true }).click();
await denied.getByRole("button", { name: "开启摄像头扫码" }).click();
await expect(denied.getByRole("alert")).toContainText("摄像头权限被拒绝");
await denied
  .getByLabel("识别个人签到码图片")
  .setInputFiles(path.join(out, "ticket.png"));
await expect(denied.locator(".status-ok")).toContainText("已签到");
expect(errors).toEqual([]);
await browser.close();
for (const c of clients) await c.dispose();
await writeFile(
  path.join(out, "result.json"),
  JSON.stringify(
    {
      httpChecks: count,
      errors,
      camera: "actual QR decoded from fake camera video",
      image: "actual QR image decoded",
      editKinds: 3,
    },
    null,
    2,
  ),
);
console.log(
  "PASS V6",
  count,
  "HTTP checks; camera and image decode, edit isolation, types, pending login and samples.",
);

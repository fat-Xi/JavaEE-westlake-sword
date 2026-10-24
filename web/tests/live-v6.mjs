// 本机演示环境只读验收，不核销票据、不改变审核状态。
import { chromium, request, expect } from "@playwright/test";
import { writeFile } from "node:fs/promises";
const base = "http://127.0.0.1:5173";
const out = "../test-results/v6";
const browser = await chromium.launch({ channel: "msedge", headless: true });
const errors = [];
const result = {};
for (const role of ["exhibit", "user", "staff", "wait"]) {
  const api = await request.newContext({
    baseURL: base,
    extraHTTPHeaders: { "X-App-Request": "1" },
  });
  expect(
    (
      await api.post("/api/login", {
        data: { name: "demo_" + role, password: "Demo12345!" },
      })
    ).status(),
  ).toBe(200);
  const ctx = await browser.newContext({
    storageState: await api.storageState(),
    viewport: { width: 1366, height: 960 },
  });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(base);
  if (role === "exhibit") {
    const rows = await (await api.get("/api/requests")).json();
    const row = rows.find((r) => r.status === "PENDING" && r.image);
    expect(row).toBeTruthy();
    const files = await (
      await api.get("/api/requests/" + row.id + "/files")
    ).json();
    expect(files.length).toBeGreaterThan(0);
    expect(
      (
        await api.get(
          "/api/requests/" + row.id + "/files/" + files[0].id + "/download",
        )
      ).status(),
    ).toBe(200);
    await page
      .locator(".row")
      .filter({ hasText: row.title })
      .getByRole("button", { name: "查看申请" })
      .click();
    await expect(page.getByAltText("申请配图")).toBeVisible();
    await expect(page.getByRole("button", { name: "查看申请" })).toHaveCount(0);
    result.request = {
      id: row.id,
      title: row.title,
      files: files.map((f) => f.name),
    };
  }
  if (role === "user") {
    await page
      .locator(".event-card")
      .filter({ hasText: "【测试】全年签到体验会议" })
      .getByRole("button", { name: "查看活动详情" })
      .click();
    await page.getByRole("button", { name: "会议评论", exact: true }).click();
    await expect(
      page.getByText("【演示评论】期待安全实践交流", { exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "签名墙", exact: true }).click();
    await expect(
      page.getByText("【演示签名】在会享留下足迹", { exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "签到服务", exact: true }).click();
    await page
      .getByRole("button", { name: "出示个人签到码", exact: true })
      .click();
    await expect(
      page.getByRole("link", { name: "下载个人签到码图片" }),
    ).toBeVisible();
    result.commentsAndWall = true;
  }
  if (role === "staff") {
    await page.getByRole("button", { name: "扫码签到", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "开启摄像头扫码" }),
    ).toBeEnabled();
    await expect(page.getByText("议程：", { exact: false })).toBeVisible();
    result.scanReady = true;
  }
  if (role === "wait") {
    await expect(page.getByRole("status")).toContainText(
      "身份资料正在等待管理员审核",
    );
    await expect(page.locator("header nav button")).toHaveText(["个人主页"]);
    result.waitStatus = (await (await api.get("/api/me")).json()).status;
  }
  await page.screenshot({
    path: out + "/live-" + role + ".png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: out + "/mobile-" + role + ".png",
    fullPage: true,
  });
  await ctx.close();
  await api.dispose();
}
expect(errors).toEqual([]);
await browser.close();
await writeFile(
  out + "/live-result.json",
  JSON.stringify({ ...result, errors }, null, 2),
);
console.log(
  "Live V6 verified: samples, pending account, ticket download, scanner ready and mobile layouts.",
);

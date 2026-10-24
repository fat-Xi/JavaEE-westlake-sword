import { chromium, request, expect } from "@playwright/test";
import { writeFile } from "node:fs/promises";
const base = "http://127.0.0.1:5173";
const browser = await chromium.launch({ channel: "msedge", headless: true });
const errors = [];
const out = "../test-results/v5";
let sample;
for (const role of ["admin", "user", "exhibit", "staff"]) {
  const api = await request.newContext({
    baseURL: base,
    extraHTTPHeaders: { "X-App-Request": "1" },
  });
  const login = await api.post("/api/login", {
    data: { name: "demo_" + role, password: "Demo12345!" },
  });
  expect(login.status()).toBe(200);
  const profile = await (await api.get("/api/profile")).json();
  expect(profile.avatar).toMatch(/^data:image/);
  expect(profile.realName).toBeTruthy();
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    storageState: await api.storageState(),
  });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(base);
  await page.waitForTimeout(500);
  if (role === "admin") {
    const rows = await (await api.get("/api/admin/events")).json();
    sample = rows.find((e) => e.title === "【测试】全年签到体验会议");
    expect(sample).toBeTruthy();
    const reqs = await (await api.get("/api/requests")).json();
    expect(
      reqs.some(
        (r) =>
          r.title === "【待审核演示】数据安全协作交流会" &&
          r.status === "PENDING",
      ),
    ).toBe(true);
    await page
      .getByRole("button", { name: "签到发布与结果", exact: true })
      .click();
    await page.locator("select").first().selectOption(String(sample.id));
    await expect(page.getByText("议程时间：")).toBeVisible();
    await page.screenshot({ path: out + "/live-admin.png", fullPage: true });
  }
  if (role === "user") {
    await page.screenshot({ path: out + "/live-home.png", fullPage: true });
    await page
      .locator(".event-card")
      .filter({ hasText: sample.title })
      .getByRole("button", { name: "查看活动详情" })
      .click();
    await page.getByRole("button", { name: "议程投票", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "下次更想参与哪个安全主题？" }),
    ).toBeVisible();
    await page.screenshot({ path: out + "/live-meeting.png", fullPage: true });
  }
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  await ctx.close();
  await api.dispose();
}
expect(errors).toEqual([]);
await writeFile(
  out + "/live-result.json",
  JSON.stringify(
    {
      sample: { id: sample.id, starts: sample.starts, ends: sample.ends },
      roles: 4,
      errors,
    },
    null,
    2,
  ),
);
await browser.close();
console.log(
  "Live 5173: four roles OK; seeds, admin schedule, meeting polls and logout OK",
  sample.starts,
  sample.ends,
);

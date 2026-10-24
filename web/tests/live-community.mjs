import { chromium, expect } from "@playwright/test";
const browser = await chromium.launch({ channel: "msedge", headless: true });
const page = await browser.newPage({ viewport: { width: 1366, height: 1000 } });
await page.goto("http://127.0.0.1:5173");
await page.getByLabel("账号", { exact: true }).fill("demo_user");
await page.getByLabel("密码", { exact: true }).fill("Demo12345!");
await page.getByRole("button", { name: "登录", exact: true }).click();
await page
  .locator(".event-card")
  .filter({ hasText: "【测试】全年签到体验会议" })
  .getByRole("button", { name: "查看活动详情" })
  .click();
await page.getByRole("button", { name: "会议评论", exact: true }).click();
const card = page.locator(".community-post").first();
await expect(card.locator("strong")).not.toBeEmpty();
await expect(card.locator("time")).not.toBeEmpty();
await expect(card.getByRole("img")).toBeVisible();
await page.screenshot({
  path: "../test-results/community/live-comments.png",
  fullPage: true,
});
await page.getByRole("button", { name: "个人主页", exact: true }).click();
await page.getByRole("button", { name: "我的评论", exact: true }).click();
await expect(page.locator(".community-post").first()).toContainText("所属会议");
await page.getByRole("button", { name: "我的签名", exact: true }).click();
await expect(page.locator(".community-post").first()).toContainText("所属会议");
await browser.close();
console.log(
  "Live 5173: public authors, avatars, dates and both personal record tabs verified.",
);

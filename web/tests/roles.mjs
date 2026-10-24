// 浏览器验证四角色独立页面以及展商申请 -> 管理员建会流程。
import { chromium, expect } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const out = fileURLToPath(new URL("../../test-results/", import.meta.url));
await mkdir(out, { recursive: true });
const mysql =
  process.env.MYSQL_BIN || "D:/mysql/mysql-9.7.0-winx64/bin/mysql.exe";
const sql = (query) =>
  execFileSync(
    mysql,
    [
      "-u",
      "root",
      "--default-character-set=utf8mb4",
      "westlake_sword",
      "-N",
      "-e",
      query,
    ],
    { encoding: "utf8" },
  ).trim();
const browser = await chromium.launch({ channel: "msedge", headless: true });
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
});
const page = await context.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
let requestId, eventId;
const title = "UI办会验证_" + Date.now();
const ready = async () => expect(page.locator(".account button")).toBeEnabled();
async function login(name) {
  await page.goto("http://127.0.0.1:5173");
  await expect(page.getByText("正在检查登录状态…")).toHaveCount(0);
  if (
    await page.getByRole("button", { name: "退出", exact: true }).isVisible()
  ) {
    await ready();
    await page.getByRole("button", { name: "退出", exact: true }).click();
  }
  await expect(
    page.getByRole("heading", { name: "欢迎回来", exact: true }),
  ).toBeVisible();
  await page.getByLabel("账号", { exact: true }).fill(name);
  await page.getByLabel("密码", { exact: true }).fill("Demo12345!");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.locator(".account")).toContainText(name);
  await ready();
}
async function nav(labels) {
  await expect(page.locator("header nav button")).toHaveText(labels);
}
async function chooseRequest() {
  await page
    .locator(".row")
    .filter({ hasText: title })
    .getByRole("button", { name: "查看申请", exact: true })
    .click();
  await ready();
}
try {
  const shareId = sql(
    "SELECT id FROM events WHERE title='【演示】数字安全创新交流日' LIMIT 1",
  );
  await page.goto(`http://127.0.0.1:5173/?event=${shareId}&channel=invite`);
  await expect(
    page.getByRole("heading", { name: "欢迎回来", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".event-card")).toHaveCount(0);
  await expect(page.locator("header nav")).toHaveCount(0);
  expect(
    await page
      .locator(".brand-logo")
      .evaluate((el) => el.complete && el.naturalWidth > 0),
  ).toBe(true);
  await page.screenshot({ path: out + "login-v3.png", fullPage: true });
  await page.getByLabel("账号", { exact: true }).fill("demo_user");
  await page.getByLabel("密码", { exact: true }).fill("Demo12345!");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(
    page.getByRole("heading", {
      name: "【演示】数字安全创新交流日",
      exact: true,
    }),
  ).toBeVisible();
  await expect(page).toHaveURL(/channel=invite/);
  await ready();

  await login("demo_exhibit");
  await nav(["展商工作台"]);
  await expect(
    page.getByRole("heading", { name: "展商协作中心" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "新建办会申请", exact: true }).click();
  await page.getByLabel("会议名称", { exact: true }).fill(title);
  await page.getByLabel("拟定地点", { exact: true }).fill("杭州测试会场");
  const date = new Date(Date.now() + 10 * 86400000).toISOString().slice(0, 10);
  await page.getByLabel("拟定开始时间", { exact: true }).fill(date + "T09:00");
  await page.getByLabel("拟定结束时间", { exact: true }).fill(date + "T17:00");
  await page.getByLabel("预计人数", { exact: true }).fill("60");
  await page
    .getByLabel("联系人及联系方式", { exact: true })
    .fill("测试展商 13800000000");
  await page
    .getByLabel("会议内容、设备及资料需求", { exact: true })
    .fill("交流数据安全实践，需投影设备。附件为演示议程。");
  const saved = page.waitForResponse(
    (r) => r.url().endsWith("/api/requests") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  requestId = (await (await saved).json()).id;
  await ready();
  await page.locator("input[type=file]").setInputFiles({
    name: "议程测试.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("演示议程资料，供管理员审核"),
  });
  await page.getByRole("button", { name: "上传附件", exact: true }).click();
  await expect(page.getByRole("link", { name: /议程测试.txt/ })).toBeVisible();
  await ready();
  await page
    .getByRole("button", { name: "提交管理员审核", exact: true })
    .click();
  await expect(page.locator(".tag", { hasText: "待审核" })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "修改信息", exact: true }),
  ).toHaveCount(0);
  await page.screenshot({ path: out + "exhibitor-v3.png", fullPage: true });

  await login("demo_admin");
  await nav(["管理中心"]);
  await expect(
    page.getByRole("heading", { name: "管理员控制中心" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "办会申请审核", exact: true }).click();
  await ready();
  await chooseRequest();
  await expect(
    page.getByText("测试展商 13800000000", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: /议程测试.txt/ })).toBeVisible();
  await page.getByLabel("审核意见（驳回必填）").fill("请补充会议设备需求");
  await page.getByRole("button", { name: "驳回补充资料", exact: true }).click();
  await ready();
  await expect(page.locator(".tag", { hasText: "已驳回" })).toBeVisible();

  await login("demo_exhibit");
  await chooseRequest();
  await expect(page.getByText("审核意见：请补充会议设备需求")).toBeVisible();
  await page.getByRole("button", { name: "修改信息", exact: true }).click();
  await page
    .getByLabel("会议内容、设备及资料需求", { exact: true })
    .fill("已补充：需要投影、麦克风和 60 个座位。");
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await ready();
  await page
    .getByRole("button", { name: "提交管理员审核", exact: true })
    .click();
  await ready();

  await login("demo_admin");
  await page.getByRole("button", { name: "办会申请审核", exact: true }).click();
  await ready();
  await chooseRequest();
  await page.getByRole("button", { name: "资料审核通过", exact: true }).click();
  await ready();
  await expect(
    page.getByRole("button", { name: "确认信息并创建活动", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "确认信息并创建活动", exact: true })
    .click();
  await expect(
    page.getByRole("combobox", { name: "发布状态", exact: true }),
  ).toHaveValue("CLOSED");
  const created = page.waitForResponse(
    (r) =>
      r.url().endsWith(`/requests/${requestId}/create`) &&
      r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "创建正式活动", exact: true }).click();
  eventId = (await (await created).json()).eventId;
  await ready();
  await expect(page.locator(".tag", { hasText: "已创建活动" })).toBeVisible();
  await page.getByRole("button", { name: "活动与内容", exact: true }).click();
  await ready();
  const eventRow = page.locator(".row").filter({ hasText: title });
  await eventRow
    .getByRole("button", { name: "查看完整信息", exact: true })
    .click();
  await ready();
  await expect(
    page.getByRole("heading", { name: "会议完整信息" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "议程测试.txt", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("已补充：需要投影、麦克风和 60 个座位。", { exact: true }),
  ).toBeVisible();
  await page.screenshot({ path: out + "admin-meeting-v3.png", fullPage: true });
  await page.getByRole("button", { name: "收起会议详情", exact: true }).click();
  await eventRow
    .getByRole("button", { name: "编辑 / 上下架", exact: true })
    .click();
  await page.getByLabel("地点", { exact: true }).fill("杭州更新会场");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await ready();
  await page.getByRole("button", { name: "运营看板", exact: true }).click();
  await expect(page.locator("canvas")).toHaveCount(2);
  await ready();
  await page.getByRole("button", { name: "内容审核", exact: true }).click();
  await ready();
  await page
    .getByRole("combobox", { name: "审核状态", exact: true })
    .selectOption("ALL");
  await page
    .getByRole("button", { name: "修订资料并重新送审", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("heading", { name: "修订展示资料", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "取消修订", exact: true }).click();
  await page.getByRole("button", { name: "用户与权限", exact: true }).click();
  await ready();
  const options = await page
    .locator("select")
    .first()
    .locator("option")
    .allTextContents();
  expect(options.map((t) => t.trim())).toEqual([
    "管理员",
    "工作人员",
    "展商",
    "参会者",
  ]);

  await login("demo_staff");
  await nav(["会议执行信息", "扫码签到"]);
  await page
    .getByRole("combobox", { name: "选择会议（含已关闭）", exact: true })
    .selectOption(String(eventId));
  await ready();
  await expect(page.getByText("杭州更新会场", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "编辑 / 上下架", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: "会议资料", exact: true }),
  ).toHaveCount(0);
  await page.screenshot({ path: out + "staff-v3.png", fullPage: true });
  await page.getByRole("button", { name: "扫码签到", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "二维码核销", exact: true }),
  ).toBeVisible();
  await ready();

  await login("demo_user");
  await nav(["发现活动", "我的空间", "热点广场", "地图导航", "AI 问答"]);
  await expect(
    page.locator(".event-card").filter({ hasText: title }),
  ).toHaveCount(0);
  for (const [label, heading, action] of [
    ["热点广场", "热点话题广场", "发布话题（待开发）"],
    ["地图导航", "会场地图导航", "规划路线（待开发）"],
    ["AI 问答", "AI 会议助手", "发送问题（待开发）"],
  ]) {
    await page.getByRole("button", { name: label, exact: true }).click();
    await ready();
    await expect(
      page.getByRole("heading", { name: heading, exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: action, exact: true }),
    ).toBeDisabled();
  }
  await page.screenshot({ path: out + "ai-page-v3.png", fullPage: true });
  await page.getByRole("button", { name: "发现活动", exact: true }).click();
  await ready();
  await page
    .locator(".event-card")
    .filter({ hasText: "数字安全创新交流日" })
    .getByRole("button", { name: "查看活动详情" })
    .click();
  await ready();
  await page.getByRole("button", { name: "参会服务", exact: true }).click();
  await page.getByRole("button", { name: "查看签到码", exact: true }).click();
  await expect(page.getByAltText("签到二维码")).toHaveAttribute(
    "src",
    /^data:image\/png/,
  );
  await ready();
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "欢迎回来", exact: true }),
  ).toBeVisible();
  await expect(page.locator("header nav")).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth + 1,
    ),
  ).toBe(false);
  await page.screenshot({ path: out + "login-mobile-v3.png", fullPage: true });
  await login("demo_pending");
  await page.getByRole("button", { name: "AI 问答", exact: true }).click();
  await ready();
  await expect(
    page.getByText("尚无审核通过的会议，请先报名并等待管理员审核。"),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "发送问题（待开发）", exact: true }),
  ).toHaveCount(0);
  // 模拟会话在服务器失效，下一次业务请求必须退出旧页面。
  await context.request.post("http://127.0.0.1:5173/api/logout", {
    headers: { "X-App-Request": "1" },
  });
  await page.getByRole("button", { name: "我的空间", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "欢迎回来", exact: true }),
  ).toBeVisible();
  expect(errors).toEqual([]);
  console.log(
    "PASS: login/logout, logo, four exclusive page trees, applicant draft/files/rejection/resubmission, admin approval/creation/edit/closed details, staff read-only, attendee features, three reserved pages, charts, four role options, mobile login.",
  );
} finally {
  if (errors.length) console.error(errors);
  await browser.close();
  if (requestId) {
    sql(
      `DELETE FROM request_files WHERE request_id=${Number(requestId)}; DELETE FROM requests WHERE id=${Number(requestId)};`,
    );
  }
  if (eventId) {
    sql(
      `DELETE FROM files WHERE event_id=${Number(eventId)}; DELETE FROM event_info WHERE event_id=${Number(eventId)}; DELETE FROM events WHERE id=${Number(eventId)};`,
    );
  }
}

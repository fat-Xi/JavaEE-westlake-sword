// 管理界面布局源自确认的原型；真实业务逻辑在 live.js 中。
const $ = (s) => document.querySelector(s),
  esc = (v) =>
    String(v ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
const KEY = "huixiang-admin-demo-v1";
const modules = [
  ["accounts", "账户管理"],
  ["joins", "报名审批"],
  ["meetings", "会议审批"],
  ["qa", "问答审批"],
  ["polls", "投票审批"],
  ["comments", "评论审批"],
  ["wall", "签名墙审批"],
  ["check", "发布签到"],
  ["live", "启动直播"],
  ["logs", "日志管理"],
  ["stats", "运营看板"],
];
function date(offset) {
  const d = new Date(Date.now() + offset * 3600000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}T${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}
let db = {
  users: [],
  meetings: [],
  items: [],
  talks: [],
  sessions: [],
  logs: [],
  traffic: [],
  siteViews: 0,
};
let page = "accounts",
  tab = "待审核",
  meetingId = "",
  roleFilter = "全部",
  query = "",
  detailId = null,
  detailTab = 0,
  posterIndex = 0;
function persist() {
  // 由 live.js 接入真实业务。
}
function log(action, target, result = "成功") {
  // 由 live.js 接入真实业务。
}
function toast(text) {
  $("#toast").textContent = text;
  $("#toast").classList.add("show");
  clearTimeout(window.toastTimer);
  window.toastTimer = setTimeout(
    () => $("#toast").classList.remove("show"),
    2600,
  );
}
function badge(s) {
  return `<span class="badge ${s === "待审核" ? "pending" : s === "已通过" || s === "进行中" ? "ok" : s === "已驳回" ? "no" : ""}">${esc(s)}</span>`;
}
function stage(m) {
  return new Date(m.ends) < new Date()
    ? "已结束"
    : new Date(m.starts) > new Date()
      ? "未开始"
      : "进行中";
}
function fmt(s) {
  if (!s) return "—";
  const d = new Date(s);
  return Number.isNaN(d.getTime())
    ? esc(s)
    : d.toLocaleString("zh-CN", { hour12: false });
}
function pending(key) {
  return key === "accounts"
    ? db.users.filter((u) => u.status === "待审核").length
    : key === "meetings"
      ? db.meetings.filter((m) => m.status === "待审核").length
      : db.items.filter((i) => i.kind === key && i.status === "待审核").length;
}
function go(key) {
  page = key;
  tab = "待审核";
  meetingId = "";
  query = "";
  roleFilter = "全部";
  detailId = null;
  render();
  window.scrollTo({ top: 0, behavior: "smooth" });
}
function title(t, sub, extra = "") {
  return `<div class="heading"><div><span class="eyebrow">HUIXIANG · MANAGEMENT</span><h1>${t}</h1><p class="muted">${sub}</p></div>${extra}</div>`;
}
function states() {
  return `<div class="tabs">${["待审核", "已通过", "已驳回", "全部"].map((s) => `<button class="${tab === s ? "active" : ""}" onclick="tab='${s}';render()">${s}</button>`).join("")}</div>`;
}
function empty(text) {
  return `<div class="empty"><div class="symbol">◇</div><h3>${text}</h3><p>新的申请将在对应会议下展示。</p></div>`;
}
function render() {
  document.title = "会享 · " + modules.find((m) => m[0] === page)[1];
  $("#nav").innerHTML = modules
    .map(
      ([k, n]) =>
        `<button class="${page === k ? "active" : ""}" onclick="go('${k}')">${n}${pending(k) ? `<span class="count" aria-label="${pending(k)}条待审核">${pending(k) > 99 ? "99+" : pending(k)}</span>` : ""}</button>`,
    )
    .join("");
  $("#app").innerHTML =
    page === "accounts"
      ? accounts()
      : page === "joins"
        ? registrations()
        : page === "meetings"
          ? meetings()
          : ["qa", "polls", "comments", "wall"].includes(page)
            ? reviews()
            : page === "check"
              ? checkin()
              : page === "live"
                ? '<section class="live-blank" aria-label="启动直播预留区域"></section>'
                : page === "logs"
                  ? logs()
                  : stats();
}
function accounts() {
  const rows = db.users.filter(
    (u) =>
      (tab === "全部" || u.status === tab) &&
      (roleFilter === "全部" || u.role === roleFilter) &&
      `${esc(u.name)}${u.nick}`.includes(query),
  );
  return (
    title(
      "让每个身份，都清晰可信。",
      "审核注册资料，查看账户类型，管理平台访问。",
      `<button onclick="addDemo()">＋ 模拟新申请</button>`,
    ) +
    `<div class="metrics">${[
      ["平台账户", db.users.length],
      ["待审核", pending("accounts")],
      ["展商", db.users.filter((u) => u.role === "展商").length],
      ["参会者", db.users.filter((u) => u.role === "参会者").length],
    ]
      .map(([n, v]) => metric(n, v))
      .join(
        "",
      )}</div><section class="panel"><div class="toolbar">${states()}<div class="actions"><select aria-label="账户类型" onchange="roleFilter=this.value;render()">${["全部", "管理员", "展商", "参会者", "工作人员"].map((r) => `<option ${roleFilter === r ? "selected" : ""}>${r}</option>`).join("")}</select><input placeholder="搜索账号或网名" aria-label="搜索账号" value="${esc(query)}" onchange="query=this.value;render()"></div></div><div class="table-wrap"><table><thead><tr><th>账号 / 网名</th><th>账户类型</th><th>审核状态</th><th>操作</th></tr></thead><tbody>${rows.map((u) => `<tr><td><strong>${esc(u.nick)}</strong><br><small>${esc(u.name)}</small></td><td>${u.role}</td><td>${badge(u.status)}</td><td><div class="actions"><button onclick="userDetail(${u.id})">查看资料</button>${u.status === "待审核" ? `<button class="accept" onclick="review('accounts',${u.id},true)">通过</button><button class="reject" onclick="reject('accounts',${u.id})">驳回</button>` : ""}<button class="link" ${u.id === 1 ? 'disabled title="不能删除当前管理员"' : ""} onclick="removeUser(${u.id})">删除账户</button></div></td></tr>`).join("")}</tbody></table>${!rows.length ? empty("没有匹配的账户") : ""}</div></section>`
  );
}
function metric(name, value, note = "") {
  return `<div class="metric"><small>${name}</small><strong>${value}</strong>${note ? `<small>${note}</small>` : ""}</div>`;
}
function modal(html) {
  $("#dialog").innerHTML =
    `<button class="close" aria-label="关闭弹窗" onclick="closeModal()">✕</button>${html}`;
  $("#modal").showModal();
}
function closeModal() {
  $("#modal").close();
}
function userDetail(id) {
  const u = db.users.find((u) => u.id === id);
  modal(
    `<h2>注册资料</h2>${[
      ["网名", u.nick],
      ["账号", u.name],
      ["真实姓名", u.real],
      ["类型", u.role],
      ["邮箱", u.email],
      ["电话", u.phone],
      ["审核状态", u.status],
      ["审核备注", u.reason || "无"],
    ]
      .map(
        ([k, v]) =>
          `<div class="info"><span>${k}</span><strong>${esc(v)}</strong></div>`,
      )
      .join("")}`,
  );
}
function removeUser(id) {
  // 由 live.js 接入真实业务。
}
function confirmDelete(id, form) {
  const u = db.users.find((u) => u.id === id);
  if (form.elements.namedItem("confirm").value !== u.name)
    return toast("输入的账号不一致");
  db.users = db.users.filter((u) => u.id !== id);
  log("删除账户", u.name);
  closeModal();
  render();
  toast("已删除账户，业务记录保留");
}
function meetings() {
  if (detailId) return meetingDetail();
  const rows = db.meetings.filter((m) => tab === "全部" || m.status === tab);
  return (
    title(
      "从完整资料，到安心发布。",
      "查看展商提交的全部会议内容，通过后同步公开。",
    ) +
    `<div class="toolbar">${states()}</div><div class="grid enter">${rows.map((m) => `<article class="card"><div class="cover"><small>${esc(m.type)} / ${new Date(m.starts).getFullYear()}</small><h2>${esc(m.coverTitle)}</h2></div><div class="card-body">${badge(m.status)}<h3>${esc(m.title)}</h3><p>${esc(m.owner)} · ${esc(m.place)}</p><p>${fmt(m.starts)} — ${fmt(m.ends)}</p><div class="row"><small>${m.capacity} 人 · ${db.talks.filter((t) => t.event === m.id).length} 个议程</small><button onclick="detailId=${m.id};detailTab=0;render()">查看全部资料 ↗</button></div></div></article>`).join("")}</div>${!rows.length ? empty("暂无此状态的会议") : ""}`
  );
}
function meetingDetail() {
  // 由 live.js 接入真实业务。
}
function posters(m) {
  return `<div class="poster"><small>${esc(m.type)} / ${new Date(m.starts).getFullYear()}</small><h2>${posterIndex % 2 === 0 ? m.coverTitle : "让每一次交流，都有新的可能。"}</h2><p>${esc(m.title)}</p></div><div class="poster-controls"><button onclick="posterIndex=0;render()">海报 1</button><button onclick="posterIndex=1;render()">海报 2</button></div>`;
}
// 每个审批模块仅显示该模块存在待审核记录的会议。
function reviewMeetings() {
  const ids = new Set(
    db.items
      .filter((i) => i.kind === page && i.status === "待审核")
      .map((i) => i.event),
  );
  return db.meetings.filter((m) => ids.has(m.id));
}
function meetingSelect() {
  return `<select class="select-meeting" aria-label="选择会议" onchange="meetingId=this.value;tab='待审核';render()"><option value="">请选择会议</option>${reviewMeetings()
    .map(
      (m) =>
        `<option value="${m.id}" ${String(m.id) === meetingId ? "selected" : ""}>${esc(m.title)} · ${db.items.filter((i) => i.event === m.id && i.kind === page && i.status === "待审核").length} 条待审核</option>`,
    )
    .join("")}</select>`;
}
function reviews() {
  const labels = {
    qa: [
      "让问题与解答，有序连接。",
      "参会者提交问题，展商提交回答；分别审核，通过后公开。",
    ],
    polls: [
      "倾听观点，让选择更透明。",
      "审核展商提交的投票题目、选项与有效时间。",
    ],
    comments: [
      "让讨论，保有温度。",
      "审核参会者提交的会议评论，通过后进入公开评论区。",
    ],
    wall: [
      "让相遇，留下真诚回响。",
      "审核参会者提交的签名，通过后进入公开签名墙。",
    ],
  };
  const choices = reviewMeetings();
  if (!choices.length)
    return (
      title(...labels[page]) +
      `<section class="panel">${empty("暂无需要审批的会议")}</section>`
    );
  if (!choices.some((m) => String(m.id) === meetingId)) meetingId = "";
  const rows = db.items.filter(
    (i) =>
      i.kind === page &&
      String(i.event) === meetingId &&
      (tab === "全部" || i.status === tab) &&
      (page !== "qa" || roleFilter === "全部" || i.role === roleFilter),
  );
  return (
    title(...labels[page]) +
    `<section class="panel"><div class="toolbar">${meetingSelect()}${meetingId ? states() : ""}</div>${!meetingId ? empty("请先选择需要审核的会议") : `${page === "qa" ? `<div class="tabs">${["全部", "参会者", "展商"].map((r) => `<button class="${roleFilter === r ? "active" : ""}" onclick="roleFilter='${r}';render()">${r === "参会者" ? "参会者提问" : r === "展商" ? "展商回答" : "全部申请"}</button>`).join("")}</div>` : ""}<div class="enter">${rows.map((i) => `<article class="item"><div class="row"><div><span class="avatar">${esc(i.author.slice(0, 1))}</span> <strong>${esc(i.author)}</strong> <span class="badge">${i.role}${page === "qa" ? (i.role === "展商" ? "回答" : "提问") : ""}</span></div>${badge(i.status)}</div>${i.talk ? `<p class="muted">议程：${esc(db.talks.find((t) => t.id === i.talk)?.title)}</p>` : ""}${i.question ? `<p class="note">${page === "qa" ? "原问题" : "投票题目"}：${esc(i.question)}</p>` : ""}<h3>${esc(i.body)}</h3>${i.options ? `<div class="grid">${i.options.map((o, n) => `<div class="feature">${String.fromCharCode(65 + n)} · ${esc(o)}</div>`).join("")}</div><p class="muted">${fmt(i.starts)} — ${fmt(i.ends)}</p>` : ""}${i.reason ? `<p class="notice">驳回原因：${esc(i.reason)}</p>` : ""}<div class="form-actions">${i.status === "待审核" ? `<button class="reject" onclick="reject('${page}',${i.id})">驳回</button><button class="accept" onclick="review('${page}',${i.id},true)">通过并公开</button>` : ""}</div></article>`).join("") || empty("当前没有匹配的申请")}</div>`}</section>`
  );
}
function find(kind, id) {
  return (
    kind === "accounts"
      ? db.users
      : kind === "meetings"
        ? db.meetings
        : db.items
  ).find((r) => r.id === id);
}
function review(kind, id, pass, reason = "") {
  // 由 live.js 接入真实业务。
}
function reject(kind, id) {
  // 由 live.js 接入真实业务。
}
function checkin() {
  const talks = db.talks.filter(
    (t) => db.meetings.find((m) => m.id === t.event)?.status === "已通过",
  );
  return (
    title(
      "签到有序，现场从容。",
      "仅能为当前正在进行的议程发布签到；管理员发布，工作人员核销。",
    ) +
    `<section class="panel"><h2>会议议程</h2><div class="table-wrap"><table><thead><tr><th>会议 / 议程</th><th>时间 / 会场</th><th>进行情况</th><th>签到服务</th></tr></thead><tbody>${talks
      .map((t) => {
        const status = stage(t),
          session = db.sessions.find((s) => s.talk === t.id);
        return `<tr><td><small>${esc(db.meetings.find((m) => m.id === t.event).title)}</small><br><strong>${esc(t.title)}</strong></td><td>${fmt(t.starts)} — ${fmt(t.ends)}<br><small>${esc(t.room)}</small></td><td>${badge(status)}</td><td>${session ? `<button onclick="checkResult(${session.id})">查看签到结果</button>` : `<button class="${status === "进行中" ? "primary" : ""}" ${status !== "进行中" ? "disabled" : ""} onclick="publishCheck(${t.id})">${status === "进行中" ? "发布签到" : status === "未开始" ? "议程尚未开始" : "议程已结束"}</button>`}</td></tr>`;
      })
      .join("")}</tbody></table></div></section>`
  );
}
function publishCheck(id) {
  const t = db.talks.find((t) => t.id === id);
  if (stage(t) !== "进行中") return toast("仅可为进行中的议程发布签到");
  modal(
    `<h2>发布议程签到</h2><h3>${esc(t.title)}</h3><p class="muted">${esc(t.room)} · ${fmt(t.starts)} — ${fmt(t.ends)}</p><form onsubmit="event.preventDefault();saveCheck(${id},this)"><div class="form-grid"><label>开始时间<input type="datetime-local" name="starts" required value="${date(0)}"></label><label>结束时间<input type="datetime-local" name="ends" required value="${t.ends}"></label></div><p class="notice">签到时间须在议程时间范围内，不能重复发布同一议程。</p><div class="form-actions"><button type="button" onclick="closeModal()">取消</button><button class="accept">确认发布</button></div></form>`,
  );
}
function saveCheck(id, form) {
  // 由 live.js 接入真实业务。
}
function checkResult(id) {
  // 由 live.js 接入真实业务。
}

function logs() {
  const rows = db.logs.filter((r) =>
    `${esc(r.actor)}${esc(r.action)}${r.target}${esc(r.result)}`.includes(
      query,
    ),
  );
  return (
    title(
      "每一次操作，都有迹可循。",
      "包括审核、账户处理、签到发布与工作人员核销。",
      `<button onclick="download('操作日志.csv','时间,操作人,操作,对象,结果\\n'+db.logs.map(r=>[r.time,r.actor,r.action,r.target,r.result].map(v=>JSON.stringify(v)).join(',')).join('\\n'))">导出日志 ↓</button>`,
    ) +
    `<section class="panel"><div class="toolbar"><strong>全部日志 · ${rows.length} 条</strong><input aria-label="搜索日志" placeholder="搜索操作人、动作或对象" value="${esc(query)}" onchange="query=this.value;render()"></div><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>结果</th></tr></thead><tbody>${rows.map((r) => `<tr><td>${fmt(r.time)}</td><td>${esc(r.actor)}</td><td>${esc(r.action)}</td><td>${esc(r.target)}</td><td>${badge(r.result)}</td></tr>`).join("")}</tbody></table>${rows.length ? "" : empty("没有匹配的日志")}</div></section>`
  );
}
function stats() {
  const approved = db.meetings.filter((m) => m.status === "已通过"),
    pendingAll = modules.reduce((s, [k]) => s + pending(k), 0),
    signs = db.sessions.reduce((s, c) => s + c.count, 0);
  return (
    title(
      "用数据，看见每一次连接。",
      "演示统计 · 审核、账户与签到操作会更新相关数据。",
    ) +
    `<div class="metrics">${metric("公开会议", approved.length, `共 ${db.meetings.length} 场会议记录`)}${metric("平台账户", db.users.length, "当前未删除账户")}${metric("全站累计浏览", db.siteViews.toLocaleString(), "演示累计 PV，含其他页面")}${metric("待处理审批", pendingAll, "六类审批合计")}</div><div class="grid"><section class="panel"><div class="row"><h2>近7天网站浏览趋势</h2><span class="badge">PV</span></div>${lineChart()}<small>访问次数，非去重人数；不等于下方会议浏览量之和。</small></section><section class="panel"><h2>各会议累计浏览量</h2>${approved.map((m) => `<div class="bar-row"><span>${esc(m.title.split(" · ")[0])}</span><div class="track"><div class="fill" style="width:${(m.views / Math.max(...approved.map((m) => m.views), 1)) * 100}%"></div></div><strong>${m.views}</strong></div>`).join("")}<small>审批通过后会议从0开始累计浏览，原型不伪造新增访问。</small></section><section class="panel"><h2>账户类型分布</h2>${[
      "参会者",
      "展商",
      "工作人员",
      "管理员",
    ]
      .map((r, i) => {
        const n = db.users.filter((u) => u.role === r).length;
        return `<div class="bar-row"><span>${r}</span><div class="track"><div class="fill" style="width:${(n / Math.max(db.users.length, 1)) * 100}%"></div></div><strong>${n} 人</strong></div>`;
      })
      .join(
        "",
      )}</section><section class="panel"><h2>现场与内容概览</h2><div class="metrics" style="grid-template-columns:1fr 1fr;margin:15px 0">${metric("签到场次", db.sessions.length)}${metric("已核销人次", signs)}${metric("公开评论", db.items.filter((i) => i.kind === "comments" && i.status === "已通过").length)}${metric("公开签名", db.items.filter((i) => i.kind === "wall" && i.status === "已通过").length)}</div><small>核销按场次累计，跨议程不作去重人数。</small></section></div>`
  );
}
function lineChart() {
  // 由 live.js 接入真实业务。
}

function download(name, text) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(
    new Blob(["\ufeff" + text.replaceAll("\\n", "\n")], {
      type: "text/plain;charset=utf-8",
    }),
  );
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

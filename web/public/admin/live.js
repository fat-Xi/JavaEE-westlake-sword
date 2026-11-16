// 页面沿用确认过的原型结构；所有操作由服务端鉴权及持久化。
let busy = false,
  raw = {},
  currentUser = null;
const state = (s) =>
  ({
    PENDING: "待审核",
    APPROVED: "已通过",
    REJECTED: "已驳回",
    WITHDRAWN: "已撤销",
    REMOVED: "已删除",
    CLOSED: "已关闭",
  })[s] || s;
const roles = {
  ADMIN: "管理员",
  USER: "参会者",
  EXHIBITOR: "展商",
  STAFF: "工作人员",
};
async function api(path, method = "GET", data) {
  const r = await fetch("/api" + path, {
    method,
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-App-Request": "1" },
    body: data === undefined ? undefined : JSON.stringify(data),
  });
  const t = await r.text();
  let out = {};
  try {
    out = t ? JSON.parse(t) : {};
  } catch {}
  if (r.status === 401)
    parent.postMessage("admin-session-ended", location.origin);
  if (!r.ok) throw Error(out.message || "操作失败，请刷新重试");
  return out;
}
// 请求进行中暂时锁定操作按钮，避免连续点击使审核被静默跳过。
function lockActions() {
  document.querySelectorAll("#app button, dialog button").forEach((button) => {
    if (busy && !button.disabled) {
      button.disabled = true;
      button.dataset.busyLock = "1";
    } else if (!busy && button.dataset.busyLock) {
      button.disabled = false;
      delete button.dataset.busyLock;
    }
  });
}
async function run(work) {
  if (busy) return toast("正在处理，请稍候");
  busy = true;
  lockActions();
  try {
    await work();
  } catch (e) {
    toast(e.message);
  } finally {
    busy = false;
    lockActions();
  }
}
async function load() {
  raw = await api("/admin/desk");
  db.commentReports = raw.commentReports ?? [];
  db.users = raw.users.map((u) => ({
    ...u,
    nick: u.nick || u.name,
    real: u.real_name,
    role: roles[u.role],
    status: state(u.status),
  }));
  db.meetings = raw.meetings.map((m) => {
    const p = JSON.parse(m.payload),
      e = raw.events.find((e) => e.id === m.event_id);
    return {
      ...p,
      id: m.event_id || -m.id,
      requestId: m.id,
      owner: m.owner,
      status:
        m.status === "APPROVED" && e?.status === "CLOSED"
          ? "已关闭"
          : state(m.status),
      reason: m.reason,
      type: p.meetingType || "会议",
      coverTitle: p.coverTitle || p.title,
      body: p.content,
      views: e?.views || 0,
    };
  });
  for (const e of raw.events)
    if (!db.meetings.some((m) => m.id === e.id))
      db.meetings.push({
        ...e,
        body: e.content,
        type: e.meeting_type || "会议",
        coverTitle: e.cover_title || e.title,
        owner: "管理员",
        status: e.status === "OPEN" ? "已通过" : "已关闭",
        talks: [],
        features: [],
        materials: [],
      });
  db.talks = raw.talks.map((t) => ({ ...t, event: t.event_id }));
  db.sessions = raw.sessions.map((s) => ({ ...s, talk: s.talk_id }));
  db.items = [
    ...raw.questions
      .filter((r) => r.status !== "WITHDRAWN")
      .map((r) => ({
        ...r,
        id: "q" + r.id,
        source: "question",
        sourceId: r.id,
        event: r.event_id,
        kind: "qa",
        role: "参会者",
        status: state(r.status),
        talk: r.talk_id,
      })),
    ...raw.posts
      .filter((r) => r.status !== "WITHDRAWN")
      .map((r) => ({
        ...r,
        id: "p" + r.id,
        source: "post",
        sourceId: r.id,
        event: r.event_id,
        kind: r.kind === "COMMENT" ? "comments" : "wall",
        role: "参会者",
        status: state(r.status),
      })),
    ...raw.interactions.map((r) => {
      const p = JSON.parse(r.payload);
      return {
        ...p,
        id: "i" + r.id,
        source: "interaction",
        sourceId: r.id,
        event: r.event_id,
        kind:
          r.kind === "ANSWER" ? "qa" : r.kind === "POLL" ? "polls" : "check",
        role: "展商",
        author: r.author,
        status: state(r.status),
        reason: r.reason,
        body: p.answer || p.title || p.talkTitle,
        question: p.questionText || p.question,
        talk: p.talkId,
      };
    }),
  ];
  db.logs = raw.logs.map((l) => ({
    time: l.created,
    actor: l.actor || "历史账户",
    action: l.action,
    target: l.detail,
    result: "已记录",
  }));
  db.siteViews = raw.siteViews;
  db.traffic = Array.from({ length: 7 }, (_, i) => {
    const d = new Date();
    d.setDate(d.getDate() - 6 + i);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    return raw.traffic.find((r) => r.day === key)?.views || 0;
  });
}
const basePending = pending;
pending = function (key) {
  return (
    basePending(key) +
    (key === "joins"
      ? (raw.joins || []).filter((j) => j.status === "PENDING").length
      : 0)
  );
};
persist = () => {};
log = () => {};
const originalRender = render;
render = function () {
  if (detailId && !db.meetings.some((m) => m.id === detailId)) detailId = null;
  originalRender();
  document.querySelectorAll("button").forEach((b) => {
    if (b.textContent.includes("模拟新申请")) b.remove();
  });
  document.querySelectorAll('button[onclick^="removeUser"]').forEach((b) => {
    const id = Number(b.getAttribute("onclick").match(/\d+/)?.[0]);
    b.disabled =
      id === currentUser?.id ||
      db.users.find((u) => u.id === id)?.role === "管理员";
  });
  lockActions();
};
const originalGo = go;
go = function (k) {
  originalGo(k);
  api("/visits", "POST", { page: "admin/" + k }).catch(() => {});
  run(async () => {
    await load();
    render();
  });
};
review = function (kind, id, pass, reason = "") {
  run(async () => {
    let path;
    if (kind === "accounts") path = `/admin/users/${id}/review`;
    else if (kind === "meetings") {
      const m = db.meetings.find((m) => m.id === id);
      path = `/exhibit-review/meetings/${m.requestId}`;
    } else {
      const r = db.items.find((r) => r.id === id);
      if (!r) throw Error("申请已改变，请刷新");
      path =
        r.source === "interaction"
          ? `/exhibit-review/interactions/${r.sourceId}`
          : r.source === "question"
            ? `/review/questions/${r.sourceId}`
            : `/review/posts/${r.sourceId}`;
    }
    await api(path, "POST", { pass, reason });
    if (kind === "meetings") detailId = null;
    await load();
    render();
    toast(pass ? "审核通过，已发送通知" : "已驳回并通知申请人");
  });
};
// 原型把ID当作数字，真实记录来自不同表，使用带前缀的字符串避免碰撞。
const originalReviews = reviews;
function removePublishedComment(id, reportId) {
  modal(
    `<h2>删除评论</h2><p class="muted">这条评论及其所有回复将一并移除，作者会收到删除理由。</p>
    <form id="remove-comment-form">
    <label>删除理由<textarea name="reason" required maxlength="200"></textarea></label>
    <div class="form-actions">
    <button type="button" onclick="closeModal()">取消</button>
    <button class="reject">确认删除</button></div></form>`,
  );
  $("#remove-comment-form").onsubmit = (event) => {
    event.preventDefault();
    const reason = new FormData(event.target).get("reason").trim();
    if (!reason) return toast("请填写删除理由");
    run(async () => {
      if (reportId) {
        await api(`/review/comment-reports/${reportId}`, "PUT", {
          remove: true,
          reason,
        });
      } else {
        await api(`/review/posts/${id}`, "DELETE", { reason });
      }
      closeModal();
      await load();
      render();
      toast("评论及其回复已删除");
    });
  };
}
function keepReportedComment(id) {
  run(async () => {
    await api(`/review/comment-reports/${id}`, "PUT", { remove: false });
    await load();
    render();
    toast("举报已处理，评论已保留");
  });
}
function manageQuestion(id, action) {
  const question = db.items.find(
    (item) => item.id === id && item.source === "question",
  );
  if (!question) return;
  run(async () => {
    await api(`/review/questions/${question.sourceId}/state`, "PUT", {
      [action]: !question[action],
    });
    await load();
    render();
    toast("提问状态已更新");
  });
}
reviews = function () {
  return originalReviews().replace(
    /(review|reject)\('([^']+)',([qpi]\d+)/g,
    "$1('$2','$3'",
  );
};
reject = function (kind, id) {
  modal(
    `<h2>驳回申请</h2><p class="muted">原因将发送给申请人。</p><form id="reject-form"><label>驳回原因<textarea name="reason" required maxlength="200"></textarea></label><div class="form-actions"><button type="button" onclick="closeModal()">取消</button><button class="reject">确认驳回</button></div></form>`,
  );
  $("#reject-form").onsubmit = (e) => {
    e.preventDefault();
    const reason = new FormData(e.target).get("reason").trim();
    if (!reason) return toast("请填写驳回原因");
    closeModal();
    review(kind, id, false, reason);
  };
};
confirmDelete = function (id, form) {
  const name = form.elements.namedItem("confirm").value;
  run(async () => {
    await api(`/admin/users/${id}/delete`, "POST", { name });
    closeModal();
    await load();
    render();
    toast("账户已删除，历史业务记录保留");
  });
};
removeUser = function (id) {
  const u = db.users.find((u) => u.id === id);
  if (id === currentUser.id || u.role === "管理员") return;
  modal(
    `<h2>删除账户</h2><p>撤销 ${esc(u.nick)} 的访问资格，保留历史业务数据。</p><form onsubmit="event.preventDefault();confirmDelete(${id},this)"><label>输入账号 ${esc(u.name)} 确认<input name="confirm" required></label><div class="form-actions"><button type="button" onclick="closeModal()">取消</button><button class="reject">确认删除</button></div></form>`,
  );
};
meetingDetail = function () {
  const m = db.meetings.find((m) => m.id === detailId),
    names = ["基础信息", "详细信息", "会议议程", "展商资料"];
  let content = "";
  if (detailTab > 3) detailTab = 0;
  if (detailTab === 0) {
    const images = m.posters?.length ? m.posters : m.poster ? [m.poster] : [];
    content = `${m.cover ? `<img src="${esc(m.cover)}" alt="会议封面" style="width:100%;max-height:240px;object-fit:cover;border-radius:18px">` : ""}${[
      ["会议标题", m.title],
      ["展商", m.owner],
      ["会议类型", m.meetingType || "自动识别"],
      ["封面标题", m.coverTitle],
      ["地点", m.place],
      ["开始时间", fmt(m.starts)],
      ["结束时间", fmt(m.ends)],
      ["预计人数", m.capacity + " 人"],
    ]
      .map(
        ([k, v]) =>
          `<div class="info"><span>${k}</span><strong>${esc(v)}</strong></div>`,
      )
      .join(
        "",
      )}<h3>轮播海报 · ${images.length} 张</h3><div class="grid">${images.map((v, i) => `<img src="${esc(v)}" alt="海报${i + 1}" style="width:100%;border-radius:16px">`).join("")}</div>`;
  }
  if (detailTab === 1)
    content = `<h2>会议介绍</h2><p class="pre">${esc(m.body)}</p><div class="grid">${(m.features || []).map((f) => `<article class="feature"><h3>${esc(f.title)}</h3><p>${esc(f.body)}</p></article>`).join("")}</div>`;
  if (detailTab === 2)
    content =
      (m.talks || [])
        .map(
          (t) =>
            `<div class="agenda"><b>${esc(t.starts.slice(11, 16))}<br><small>— ${esc(t.ends.slice(11, 16))}</small></b><div><h3>${esc(t.title)}</h3><p>${esc(t.room)} · ${esc(t.speaker)} · ${esc(t.tags)}</p><small>${fmt(t.starts)} — ${fmt(t.ends)}</small></div></div>`,
        )
        .join("") || empty("尚未填写议程");
  if (detailTab === 3)
    content =
      (m.materials || [])
        .map(
          (r) =>
            `<article class="item"><span class="badge">${esc(r.type)}</span><h3>${esc(r.title)}</h3><p>${esc(r.product)}</p><p class="pre">${esc(r.body)}</p>${r.image ? `<img src="${esc(r.image)}" style="max-width:100%;max-height:240px" alt="资料配图">` : ""}${(r.files || []).map((f) => `<p><a href="${esc(f.data)}" download="${esc(f.name)}">↓ ${esc(f.name)}</a></p>`).join("")}</article>`,
        )
        .join("") || empty("暂无展商资料");
  return (
    `<button class="link" onclick="detailId=null;render()">← 返回会议审批</button>` +
    title(
      esc(m.title),
      `${esc(m.owner)} 提交 · 全部资料随会议一起审核`,
      badge(m.status),
    ) +
    `<section class="panel"><div class="tabs" style="margin-bottom:25px">${names.map((n, i) => `<button class="${detailTab === i ? "active" : ""}" onclick="detailTab=${i};render()">${n}</button>`).join("")}</div>${content}</section><section class="panel row"><div><strong>审核结论</strong><p>${esc(m.reason || "通过后同步发布会议、议程和资料。")}</p></div>${m.status === "待审核" ? `<div class="actions"><button class="reject" onclick="reject('meetings',${m.id})">驳回并说明原因</button><button class="accept" onclick="review('meetings',${m.id},true)">审核通过并发布</button></div>` : badge(m.status)}</section>`
  );
};
saveCheck = function (id, form) {
  const starts = form.starts.value,
    ends = form.ends.value;
  run(async () => {
    await api("/attendance", "POST", {
      talkId: id,
      starts,
      ends,
      enabled: true,
    });
    closeModal();
    await load();
    render();
    toast("签到场次已发布");
  });
};
checkResult = function (id) {
  run(async () => {
    const r = await api(`/attendance/${id}/results`),
      s = db.sessions.find((s) => s.id === id),
      t = db.talks.find((t) => t.id === s.talk);
    modal(
      `<h2>签到结果 · ${esc(t.title)}</h2><p>${fmt(s.starts)} — ${fmt(s.ends)}</p><div class="metrics" style="grid-template-columns:1fr 1fr">${metric("实际核销", r.rows.length)}${metric("已通过报名", r.eligible)}</div><div class="table-wrap"><table><tr><th>参会者</th><th>核销时间</th><th>工作人员</th></tr>${r.rows.map((x) => `<tr><td>${esc(x.nick)}</td><td>${fmt(x.checked)}</td><td>${esc(x.staff)}</td></tr>`).join("")}</table></div>`,
    );
  });
};
const baseCheck = checkin;
checkin = function () {
  return (
    baseCheck() +
    `<section class="panel"><h2>展商签到申请</h2>${
      db.items
        .filter((r) => r.kind === "check" && r.status === "待审核")
        .map(
          (r) =>
            `<div class="item"><h3>${esc(r.body)}</h3><p>${fmt(r.starts)} — ${fmt(r.ends)} · ${esc(r.author)}</p><button class="reject" onclick="reject('check','${r.id}')">驳回</button> <button class="accept" onclick="review('check','${r.id}',true)">审核通过并发布</button></div>`,
        )
        .join("") || empty("暂无待审核签到申请")
    }</section>`
  );
};
const baseStats = stats;
stats = function () {
  let html = baseStats()
    .replace(
      "演示统计 · 审核、账户与签到操作会更新相关数据。",
      "实时业务统计 · 来源于会议、账户、内容、签到和浏览记录。",
    )
    .replace(
      "演示累计 PV，含其他页面",
      `从 ${raw.trackingSince || "本次启用"} 开始记录`,
    )
    .replace(
      "审批通过后会议从0开始累计浏览，原型不伪造新增访问。",
      "会议浏览量来自会议详情访问记录。",
    );
  return html.replace("六类审批合计", "全部待处理审批合计");
};
lineChart = function () {
  const a = db.traffic,
    max = Math.max(...a, 1),
    pts = a.map((v, i) => `${40 + i * 68},${170 - (v / max) * 130}`).join(" ");
  return `<svg class="chart" viewBox="0 0 500 220" role="img" aria-label="近7天网站浏览量"><polyline points="${pts}" fill="none" stroke="#8a73e3" stroke-width="3"/>${a.map((v, i) => `<circle cx="${40 + i * 68}" cy="${170 - (v / max) * 130}" r="4" fill="#8a73e3"/><text x="${40 + i * 68}" y="${155 - (v / max) * 130}" text-anchor="middle">${v}</text><text x="${40 + i * 68}" y="200" text-anchor="middle">${i === 6 ? "今天" : 6 - i + "天前"}</text>`).join("")}</svg>`;
};
async function signOut() {
  await api("/logout", "POST");
  parent.postMessage("admin-session-ended", location.origin);
}
// 报名独立于会议内容审核，统计及操作仍使用原报名接口。
function registrations() {
  const rows = (raw.joins || []).filter(
    (j) => tab === "全部" || state(j.status) === tab,
  );
  return (
    title(
      "参会报名，有序处理。",
      "审核用户的参会申请，通过或驳回后会发送消息通知。",
    ) +
    `<section class="panel"><div class="toolbar">${states()}</div>${rows.map((j) => `<article class="item row"><div><strong>${esc(j.name)}</strong> ${badge(state(j.status))}<p>${esc(raw.events.find((e) => e.id === j.event_id)?.title || "会议已移除")}</p><small>申请时间：${fmt(j.created)}</small>${j.reason ? `<p class="notice">${esc(j.reason)}</p>` : ""}</div>${j.status === "PENDING" ? `<div class="actions"><button class="reject" onclick="joinReview(${j.id},false)">驳回报名</button><button class="accept" onclick="joinReview(${j.id},true)">通过报名</button></div>` : ""}</article>`).join("") || empty("暂无符合条件的报名申请")}</section>`
  );
}
function joinReview(id, pass) {
  const reason = pass ? "" : prompt("请输入驳回原因");
  if (!pass && !reason?.trim()) return;
  run(async () => {
    await api(`/admin/joins/${id}`, "POST", { pass, reason });
    await load();
    render();
    toast("报名审核完成");
  });
}
async function start() {
  try {
    currentUser = await api("/me");
    if (currentUser.role !== "ADMIN") throw Error("仅管理员可访问");
    await api("/visits", "POST", { page: "admin/accounts" });
    await load();
    render();
  } catch (e) {
    $("#app").textContent = e.message;
  }
}
setInterval(async () => {
  if (busy || document.hidden) return;
  try {
    await load();

    const n = $("#nav");
    n.innerHTML = modules
      .map(
        ([k, label]) =>
          `<button class="${page === k ? "active" : ""}" onclick="go('${k}')">${label}${pending(k) ? `<span class="count">${pending(k)}</span>` : ""}</button>`,
      )
      .join("");
    if (
      ["stats", "joins", "qa", "polls", "comments", "reports", "wall"].includes(
        page,
      ) &&
      !$("#modal").open
    )
      render();
  } catch {}
}, 15000);
start();

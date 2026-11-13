/* 原型交互与真实接口的连接层：不使用演示数据或本地审批。 */
let sending = false;
async function request(path, method = "GET", body) {
  const response = await fetch("/api" + path, {
    method,
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-App-Request": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch {}
  if (response.status === 401) {
    parent.postMessage("exhibitor-session-ended", location.origin);
    throw Error("登录已过期");
  }
  if (!response.ok) throw Error(data.message || "操作失败，请刷新后重试");
  return data;
}
async function action(work) {
  if (sending) return;
  sending = true;
  document
    .querySelectorAll("button[type=submit],button.primary")
    .forEach((b) => (b.disabled = true));
  try {
    await work();
  } catch (e) {
    toast(e.message);
  } finally {
    sending = false;
    document.querySelectorAll("button").forEach((b) => (b.disabled = false));
  }
}
async function reload() {
  meetings = await request("/exhibitor/meetings");
}
async function start() {
  try {
    const me = await request("/me");
    if (me.role !== "EXHIBITOR" || me.status !== "APPROVED")
      throw Error("此页面仅供已审核展商使用");
    profile = await request("/profile");
    profile.account = me.name;
    await reload();
    render();
  } catch (e) {
    $("#app").textContent = e.message;
  }
}
persist = function () {};
newMeeting = function () {
  editor = blank();
  step = 0;
  go("editor");
};
const liveOldRender = render;
render = function () {
  liveOldRender();
  document.querySelectorAll("button").forEach((b) => {
    if (/^(演示新增提问|模拟审核|保存草稿)$/.test(b.textContent.trim()))
      b.remove();
  });
  document.querySelectorAll(".profile-grid input[readonly]").forEach((e, i) => {
    if (i === 0) e.value = profile.account || "";
  });
};
const originalInvalid = invalid;
invalid = function () {
  const issue = originalInvalid();
  if (issue) return issue;
  if (new Date(editor.starts) <= new Date())
    return [0, "starts", "会议开始时间须晚于当前时间"];
  return null;
};
publish = function () {
  if (!validate()) return;
  action(async () => {
    const r = await request("/exhibitor/meetings", "POST", editor);
    await reload();
    newId = r.id;
    filter = "全部";
    query = "";
    go("home");
    toast("会议已提交，等待管理员审核");
    setTimeout(() => (newId = null), 1200);
  });
};
withdrawMeeting = function () {
  if (!confirm("撤销审核并进入修改？未重新提交前不会公开展示。")) return;
  const m = clone(active());
  action(async () => {
    await request(`/exhibitor/meetings/${m.id}/withdraw`, "POST");
    await reload();
    editor = m;
    step = 0;
    go("editor");
    toast("已撤销审核，可修改后重新提交");
  });
};
// 未通过的会议保留驳回原因，重新提交仍需完整校验。
const previousActions = meetingActions;
meetingActions = function (m) {
  return m.status === "已驳回"
    ? '<button class="primary" onclick="editMeeting()">修改会议</button>'
    : previousActions(m);
};
submitCheck = function (e) {
  e.preventDefault();
  const f = Object.fromEntries(new FormData(e.target));
  const m = active();
  f.talk = Number(f.talk);
  f.talkId = m.talks[f.talk].id;
  action(async () => {
    await request(`/exhibitor/meetings/${m.id}/interactions/CHECK`, "POST", f);
    await reload();
    render();
    toast("签到已提交审核");
  });
};
submitPoll = function (e) {
  e.preventDefault();
  const f = Object.fromEntries(new FormData(e.target));
  f.options = f.options
    .split("\n")
    .map((x) => x.trim())
    .filter(Boolean);
  action(async () => {
    await request(
      `/exhibitor/meetings/${selected}/interactions/POLL`,
      "POST",
      f,
    );
    await reload();
    render();
    toast("投票已提交审核");
  });
};
submitAnswer = function (e, id) {
  e.preventDefault();
  const answer = new FormData(e.target).get("answer").trim();
  action(async () => {
    await request(
      `/exhibitor/meetings/${selected}/interactions/ANSWER`,
      "POST",
      { questionId: id, answer },
    );
    await reload();
    render();
    toast("回答已提交审核，通过前不会公开");
  });
};
withdrawInteraction = function (kind, id) {
  if (!confirm("撤销这条内容的审核？")) return;
  const m = active();
  const row = m[kind].find((x) => x.id === id);
  const reviewId = kind === "questions" ? row.reviewId : id;
  action(async () => {
    await request(`/exhibitor/interactions/${reviewId}/withdraw`, "POST");
    await reload();
    render();
    toast("已撤销审核");
  });
};
const prototypeAnswerPage = answerPage;
const originalCheckPage = checkPage;
checkPage = function (m) {
  if (!m.talks.length)
    return '<section class="panel empty"><h2>暂未安排会议议程</h2><p>需要先由管理员补充议程，才能发布对应的签到场次。</p></section>';
  return originalCheckPage(m);
};
answerPage = function (m) {
  return prototypeAnswerPage(m).replace(
    '<button onclick="simulateQuestion()">演示新增提问</button>',
    "",
  );
};
// 评论和签名始终来自公开内容，匿名签名不泄露作者身份。
const earlierContent = detailContent;
detailContent = function (m, previewing) {
  if (detailTab === 6 || detailTab === 7) {
    const wall = detailTab === 7,
      rows = m[wall ? "signatures" : "comments"] || [];
    return `<div class="interaction-feed"><h2>${wall ? "让相遇，留下回响。" : "在交流中，让观点碰撞。"}</h2>${
      wall
        ? `<div class="wall-board">${
            rows
              .slice(0, 8)
              .map((r) => `<p>${esc(r.body)}</p>`)
              .join("") || "<p>期待第一份祝福</p>"
          }</div>`
        : ""
    }${rows.map((r) => `<article class="comment-card"><img class="avatar" src="${esc(r.avatar || "/avatar.svg")}" alt="头像"><div><strong>${esc(r.nick)}</strong><small>　${time(r.created)}</small><p>${esc(r.body)}</p>${r.product ? `<span class="badge">${esc(r.product)}</span>` : ""}</div></article>`).join("") || '<div class="empty">暂无公开记录</div>'}</div>`;
  }
  if (detailTab === 1) {
    const now = new Date();
    return `<h2>${m.status === "未开始" ? "提前了解议程，安排参会计划。" : "把感兴趣的议程，加入日程。"}</h2>${m.talks.map((t, i) => `<article class="prototype-timeline"><div class="agenda-time"><b>${esc(t.starts.slice(11, 16))}</b><br><small>— ${esc(t.ends.slice(11, 16))}</small></div><div>${badge(new Date(t.ends) <= now ? "已结束" : new Date(t.starts) > now ? "未开始" : "正在进行")}<h3>${esc(t.title)}</h3><p>${esc(t.room)} · 嘉宾：${esc(t.speaker)} · ${esc(t.tags)}</p><div class="actions"><button onclick="toggleAgenda(this,${i})">${t.saved ? "✓ 已加入日程" : "＋ 加入日程"}</button><button onclick="toggleMap(this)">地图导航 ↗</button></div><div class="agenda-map" hidden></div></div></article>`).join("")}`;
  }
  if (detailTab === 2)
    return `<span class="eyebrow">LIVE & REPLAY</span><h2>不错过每一个精彩瞬间。</h2>${m.talks
      .map((t) => {
        const ended = new Date(t.ends) <= new Date();
        const live = new Date(t.starts) <= new Date() && !ended;
        let url = ended ? t.replay_url : live && t.live_on ? t.live_url : "";
        if (url && /^\/api\/talks\/\d+\/replay$/.test(url))
          url = `/api/exhibitor/meetings/${m.id}/replays/${t.id}`;
        return `<details class="review-item"><summary>${esc(t.title)} · ${esc(t.starts.slice(11, 16))}</summary>${url && /^(https?:\/\/|\/api\/)/.test(url) ? (/\.(mp4|webm)(\?.*)?$/i.test(url) || url.startsWith("/api/") ? `<video controls preload="none" style="width:100%;max-height:500px" src="${esc(url)}"></video>` : `<iframe src="${esc(url)}" title="${esc(t.title)}" style="width:100%;height:460px;border:0" sandbox="allow-scripts allow-same-origin allow-presentation" allowfullscreen referrerpolicy="no-referrer"></iframe>`) : `<div class="empty">${ended ? (t.had_live ? "回放待管理员发布" : "会议未开放直播，暂无回放") : live ? "直播未开启" : "议程未开始，直播未开启"}</div>`}</details>`;
      })
      .join("")}`;
  return earlierContent(m, previewing);
};
const baseCarousel = carousel;
carousel = function (m) {
  let html = baseCarousel(m);
  const wall = m.signatures || [];
  html = html.replace(
    "<p>相聚西湖，连接每一种可能。</p><p>向更可信的未来，一起出发。</p>",
    wall
      .slice(0, 8)
      .map((r) => `<p>${esc(r.body)}</p>`)
      .join("") || "<p>期待第一份祝福</p>",
  );
  return html;
};
toggleAgenda = function (button, index) {
  if (page === "preview") return toast("提交并审核通过后可添加日程");
  action(async () => {
    const t = active().talks[index];
    await request(
      `/exhibitor/meetings/${selected}/plans/${t.id}`,
      t.saved ? "DELETE" : "POST",
    );
    t.saved = !t.saved;
    button.textContent = t.saved ? "✓ 已加入日程" : "＋ 加入日程";
  });
};
showCheck = function (id) {
  const m = active(),
    c = m.checks.find((x) => x.id === id);
  modal(
    `<div class="heading"><h2>签到入口与结果</h2><button onclick="closeModal()">✕</button></div><h3>${esc(m.talks[c.talk]?.title)}</h3><p>${time(c.starts)} — ${time(c.ends)}</p><div class="row"><div><canvas id="attendance-qr"></canvas><p><a id="qr-download" download="签到入口.png">下载签到入口码</a></p></div><div><h1>${c.count || 0}<small> / ${m.capacity} 人</small></h1><p>各议程签到人数由工作人员核销后统计</p></div></div><p class="hint">展商发布签到，参会者出示个人码，由工作人员核销。</p>`,
  );
  const url = `${location.origin}/?event=${m.eventId}&attendance=${c.id}&code=${c.code}`;
  window.ExhibitQr.toCanvas($("#attendance-qr"), url, { width: 220 }).then(
    () => ($("#qr-download").href = $("#attendance-qr").toDataURL()),
  );
};
saveProfile = function (e) {
  e.preventDefault();
  const f = Object.fromEntries(new FormData(e.target));
  action(async () => {
    const next = { ...profile, ...f, tags: "" };
    await request("/profile", "PUT", next);
    profile = { ...next };
    parent.postMessage("exhibitor-profile-updated", location.origin);
    render();
    toast("个人资料已保存");
  });
};
profileImage = async function (input) {
  try {
    const file = input.files[0];
    if (!file || !file.type.startsWith("image/") || file.size > 5 * 1024 * 1024)
      throw Error("请选择5MB以内的头像图片");
    const bitmap = await createImageBitmap(file);
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 160;
    const size = Math.min(bitmap.width, bitmap.height);
    canvas
      .getContext("2d")
      .drawImage(
        bitmap,
        (bitmap.width - size) / 2,
        (bitmap.height - size) / 2,
        size,
        size,
        0,
        0,
        160,
        160,
      );
    bitmap.close();
    profile.avatar = canvas.toDataURL("image/jpeg", 0.85);
    document.querySelector(".profile-grid .avatar").innerHTML =
      `<img src="${profile.avatar}" alt="头像">`;
    toast("头像已选择，请保存个人资料");
  } catch (e) {
    toast(e.message);
  }
};
passwordDialog = function () {
  modal(
    '<div class="heading"><h2>修改密码</h2><button onclick="closeModal()">✕</button></div><form onsubmit="changePassword(event)"><label>原密码<input name="old" type="password" required></label><label>新密码<input name="new" type="password" minlength="8" required></label><label>再次输入<input name="repeat" type="password" required></label><button class="primary" style="margin-top:20px">确认修改</button></form>',
  );
};
changePassword = function (e) {
  e.preventDefault();
  const f = Object.fromEntries(new FormData(e.target));
  if (f.new !== f.repeat) return toast("两次新密码不一致");
  action(async () => {
    await request("/password", "PUT", { oldPassword: f.old, password: f.new });
    parent.postMessage("exhibitor-session-ended", location.origin);
  });
};
async function signOut() {
  await request("/logout", "POST");
  parent.postMessage("exhibitor-session-ended", location.origin);
}
const oldAiPage = aiPage;
aiPage = function () {
  return oldAiPage().replaceAll("展商助手 · 本地演示", "展商助手");
};
askExhibitor = async function (e) {
  e.preventDefault();
  if (aiBusy) return;
  const q = $("#ai-question").value.trim();
  if (!q) return;
  aiBusy = true;
  $("#ai-question").disabled = true;
  $(".compact-composer button").disabled = true;
  $(".compact-composer button").textContent = "回答中…";
  const feed = $(".chat-feed");
  feed.insertAdjacentHTML(
    "beforeend",
    `<p class="chat-bubble mine">${esc(q)}</p><article class="chat-bubble"><small>展商助手</small><p class="ai-answer">正在查阅会议资料…</p></article>`,
  );
  feed.scrollTop = feed.scrollHeight;
  try {
    const result = await request("/exhibitor/ai", "POST", { question: q });
    if (page !== "ai" || !feed.isConnected) return;
    const r = { q, shown: "" };
    aiHistory.push(r);
    const node = [...document.querySelectorAll(".ai-answer")].at(-1);
    let i = 0;
    const letters = Array.from(result.answer);
    function tick() {
      if (page !== "ai" || !feed.isConnected) return;
      r.shown += letters[i++] || "";
      node.textContent = r.shown;
      feed.scrollTop = feed.scrollHeight;
      if (i < letters.length) aiTimer = setTimeout(tick, 12);
      else done();
    }
    tick();
  } catch (err) {
    toast(err.message);
    done();
  }
  function done() {
    if (!feed.isConnected) return;
    aiBusy = false;
    if (page === "ai") {
      $("#ai-question").disabled = false;
      $("#ai-question").value = "";
      $(".compact-composer button").disabled = false;
      $(".compact-composer button").textContent = "发送 ↑";
    }
  }
};
// 仅更新提醒数据，不打断编辑或重绘正在填写的表单。
setInterval(async () => {
  if (!["home", "detail", "interaction"].includes(page) || sending) return;
  try {
    const latest = await request("/exhibitor/meetings");
    meetings = latest;
    const count = active() ? unread(active()) : 0;
    document.querySelectorAll(".dot").forEach((b) => {
      if (b.textContent.includes("互动"))
        b.textContent = "互动" + (count ? " · " + count : "");
      if (b.textContent.includes("回答问题"))
        b.textContent = "回答问题" + (count ? " · " + count : "");
      b.classList.toggle("dot", count > 0);
    });
  } catch {}
}, 15000);
start();

// 不在浏览器中提供模拟审核、模拟核销或样例生成入口。
seed = undefined;
demoReview = undefined;
approve = undefined;
resetDemo = undefined;
simulateQuestion = undefined;
simulateCheck = undefined;
participantCheck = undefined;
participantPreview = undefined;

// 列表封面与参会者共用同一字段及缺省规则。
function meetingType(m) {
  return (
    m.meetingType ||
    (m.title.includes("AI")
      ? "人工智能"
      : m.title.includes("云")
        ? "云安全"
        : m.title.includes("签到")
          ? "现场体验"
          : m.title.includes("交流")
            ? "行业交流"
            : "数据安全")
  );
}
function coverFields() {
  return `<label id="field-coverTitle">封面标题（选填）<input maxlength="120" value="${esc(editor.coverTitle || "")}" placeholder="留空时显示：会议类型 · 新视野" onchange="setField('coverTitle',this.value)"></label><label>会议类型<select aria-label="会议类型" onchange="setField('meetingType',this.value)">${["", "数据安全", "人工智能", "云安全", "现场体验", "行业交流", "技术论坛", "成果发布", "其他"].map((t) => `<option value="${t}" ${t === (editor.meetingType || "") ? "selected" : ""}>${t || "自动识别"}</option>`).join("")}</select></label>`;
}
function posterImages(m) {
  return m.posters?.length ? m.posters : m.poster ? [m.poster] : [];
}
function posterUpload() {
  return `<div class="upload" id="field-poster"><strong>会议轮播海报 *</strong><input type="file" multiple accept="image/png,image/jpeg,image/webp" aria-label="海报图片" onchange="readPosters(this)"><small>可分批添加1—8张，每张不超过5MB；按下列顺序展示在会议轮播中。</small><div class="poster-list">${posterImages(
    editor,
  )
    .map(
      (p, i) =>
        `<div><img src="${esc(p)}" alt="海报${i + 1}"><div class="actions"><button type="button" aria-label="海报${i + 1}前移" ${i === 0 ? "disabled" : ""} onclick="movePoster(${i},-1)">←</button><button type="button" aria-label="海报${i + 1}后移" ${i === posterImages(editor).length - 1 ? "disabled" : ""} onclick="movePoster(${i},1)">→</button><button type="button" onclick="removePoster(${i})">移除</button></div></div>`,
    )
    .join("")}</div></div>`;
}
async function readPosters(input) {
  try {
    const files = [...input.files],
      existing = posterImages(editor);
    if (existing.length + files.length > 8)
      throw Error("最多上传8张海报，请先移除不需要的图片");
    const added = await Promise.all(files.map(imageData));
    editor.posters = [...existing, ...added];
    editor.poster = editor.posters[0] || "";
    render();
  } catch (e) {
    toast(e.message);
    input.value = "";
  }
}
function movePoster(i, step) {
  editor.posters = [...posterImages(editor)];
  [editor.posters[i], editor.posters[i + step]] = [
    editor.posters[i + step],
    editor.posters[i],
  ];
  editor.poster = editor.posters[0];
  render();
}
function removePoster(i) {
  editor.posters = posterImages(editor).filter((_, n) => n !== i);
  editor.poster = editor.posters[0] || "";
  render();
}
function liveSlides(m) {
  const images = posterImages(m);
  return [
    ...(images.length ? images : [""]).map((p) => ({
      image: p,
      target: 0,
      title: m.title,
    })),
    { target: 1, title: "会议议程" },
    { target: 7, title: "签名墙 · 让相遇留下回响" },
  ];
}
carousel = function (m) {
  const slides = liveSlides(m);
  if (slideIndex >= slides.length) slideIndex = 0;
  return `<section class="event-carousel" aria-label="会议轮播" onmouseenter="slidePaused=true" onmouseleave="slidePaused=false" onfocusin="slidePaused=true" onfocusout="slidePaused=false">${slides
    .map(
      (s, i) =>
        `<article tabindex="0" role="button" aria-label="${esc(s.title)}" onclick="chooseSlide(${i})" onkeydown="if(event.key==='Enter')chooseSlide(${i})" class="feature-slide ${slidePosition(i, slides.length)} tone-${s.target === 7 ? 2 : s.target}">${
          s.image
            ? `<img class="slide-image" src="${esc(s.image)}" alt="会议海报${i + 1}">`
            : s.target === 7
              ? `<h2>${m.status === "未开始" ? "期待与你相聚" : s.title}</h2><div class="wall-board">${
                  (m.signatures || [])
                    .slice(0, 8)
                    .map((r) => `<p>${esc(r.body)}</p>`)
                    .join("") || "<p>期待第一份祝福</p>"
                }</div>`
              : `<div class="slide-orbit"></div><div class="slide-copy"><span class="eyebrow">WESTLAKE SUMMIT / ${new Date(m.starts).getFullYear()}</span><h1>${esc(s.title)}</h1><p>${esc(s.target === 1 ? "安排你的参会之旅" : m.place)}</p>${s.target === 0 ? badge(m.status) : ""}<p>${s.target === 1 ? "查看议程 ↓" : "查看活动详情 ↓"}</p></div>`
        }</article>`,
    )
    .join(
      "",
    )}<button class="carousel-arrow left" aria-label="上一张" onclick="moveSlide(-1)">‹</button><button class="carousel-arrow right" aria-label="下一张" onclick="moveSlide(1)">›</button></section>`;
};
function slidePosition(i, n) {
  return i === slideIndex
    ? "center"
    : i === (slideIndex + 1) % n
      ? "next"
      : i === (slideIndex + n - 1) % n
        ? "prev"
        : "outside";
}
moveSlide = function (step) {
  const slides = document.querySelectorAll(".feature-slide");
  if (!slides.length) return;
  slideIndex = (slideIndex + step + slides.length) % slides.length;
  slides.forEach((el, i) => {
    el.classList.remove("center", "next", "prev", "outside");
    el.classList.add(slidePosition(i, slides.length));
    el.tabIndex = slidePosition(i, slides.length) === "outside" ? -1 : 0;
  });
};
chooseSlide = function (i) {
  if (i !== slideIndex) return moveSlide(i - slideIndex);
  selectDetail(liveSlides(page === "preview" ? editor : active())[i].target);
};

const visitGo = go;
go = function (p) {
  visitGo(p);
  request("/visits", "POST", { page: "exhibitor/" + p }).catch(() => {});
};
request("/visits", "POST", { page: "exhibitor/home" }).catch(() => {});

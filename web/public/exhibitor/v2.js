// 与参会者保持一致的视觉结构；真实数据由 live.js 接入。
let slideIndex = 0,
  slidePaused = false,
  aiHistory = [],
  aiBusy = false,
  aiTimer;
const oldRender = render,
  oldDetailContent = detailContent,
  oldMaterials = materialsForm;
meetings = meetings.filter((m) => m.status !== "草稿");
draft = null;

render = function () {
  oldRender();
  document.body.dataset.page = page;
  $("#topnav").insertAdjacentHTML(
    "beforeend",
    `<button class="${page === "ai" ? "active" : ""}" onclick="go('ai')">AI 助手</button>`,
  );
  if (page === "ai")
    $("#app").innerHTML = '<div class="assistant-page">' + aiPage() + "</div>";
  document.querySelectorAll("button").forEach((b) => {
    if (b.textContent.includes("撤销审核")) b.classList.add("withdraw-button");
    if (/发布会议并送审|提交修改并送审/.test(b.textContent))
      b.classList.add("publish-button");
  });
  if (page === "editor") {
    const buttons = document.querySelectorAll(".sidebar button");
    buttons[4]?.classList.add("preview-step");
    buttons[5]?.classList.add("publish-step");
  }
  document
    .querySelectorAll(
      page === "profile" || page === "editor"
        ? "[data-no-reveal]"
        : ".heading,.metrics,.card",
    )
    .forEach((el) => {
      el.classList.add("reveal");
      observer.observe(el);
    });
};
const observer = new IntersectionObserver(
  (entries) =>
    entries.forEach((e) => {
      if (e.isIntersecting) {
        e.target.classList.add("visible");
        observer.unobserve(e.target);
      }
    }),
  { threshold: 0.08 },
);
const oldGo = go;
go = function (p) {
  if (p !== "ai") {
    clearTimeout(aiTimer);
    aiBusy = false;
  }
  if (p === "detail" || p === "preview") slideIndex = 0;
  oldGo(p);
};
function meetingActions(m) {
  if (m.status === "已结束") return "";
  if (["未开始", "待审核"].includes(m.status))
    return `<button class="primary edit-meeting" onclick="editMeeting()">修改会议</button>${m.status === "待审核" ? '<button onclick="withdrawMeeting()">撤销审核</button>' : ""}`;
  return `<button class="teal ${unread(m) ? "dot" : ""}" onclick="interactionTab=0;go('interaction')">互动${unread(m) ? " · " + unread(m) : ""}</button>`;
}
function carousel(m) {
  return `<section class="event-carousel" aria-label="会议轮播" onmouseenter="slidePaused=true" onmouseleave="slidePaused=false" onfocusin="slidePaused=true" onfocusout="slidePaused=false">${[0, 1, 2].map((n) => `<article tabindex="0" role="button" aria-label="${["活动海报", "会议议程", "签名墙"][n]}" onclick="chooseSlide(${n})" onkeydown="if(event.key==='Enter')chooseSlide(${n})" class="feature-slide ${n === slideIndex ? "center" : n === (slideIndex + 1) % 3 ? "next" : "prev"} tone-${n}">${n === 0 ? `<img class="slide-image" src="${esc(m.poster)}" alt="会议海报"><div class="slide-copy"><span class="eyebrow">WESTLAKE SUMMIT / 2026</span><h1>${esc(m.title)}</h1><p>${esc(m.place)}</p>${badge(m.status)}<p>查看活动详情 ↓</p></div>` : n === 1 ? '<div class="slide-orbit"></div><div class="slide-copy"><span class="eyebrow">WESTLAKE SUMMIT / 2026</span><h1>会议议程</h1><p>安排你的参会之旅</p><p>查看议程 ↓</p></div>' : `<h2>${m.status === "未开始" ? "期待与你相聚" : "签名墙 · 让相遇留下回响"}</h2><div class="wall-board"><p>相聚西湖，连接每一种可能。</p><p>向更可信的未来，一起出发。</p></div>`}</article>`).join("")}<button class="carousel-arrow left" aria-label="上一张" onclick="moveSlide(-1)">‹</button><button class="carousel-arrow right" aria-label="下一张" onclick="moveSlide(1)">›</button></section>`;
}
function moveSlide(n) {
  slideIndex = (slideIndex + n + 3) % 3;
  document.querySelectorAll(".feature-slide").forEach((el, i) => {
    el.classList.remove("center", "next", "prev");
    el.classList.add(
      i === slideIndex
        ? "center"
        : i === (slideIndex + 1) % 3
          ? "next"
          : "prev",
    );
  });
}
function chooseSlide(n) {
  if (n !== slideIndex) {
    moveSlide(n - slideIndex);
    return;
  }
  selectDetail([0, 1, 7][n]);
}
setInterval(() => {
  if (
    ["detail", "preview"].includes(page) &&
    !slidePaused &&
    !document.hidden &&
    scrollY < 250
  )
    moveSlide(1);
}, 5500);
function selectDetail(n) {
  detailTab = n;
  const m = page === "preview" ? editor : active();
  $(".detail-body").innerHTML = detailContent(m, page === "preview");
  document
    .querySelectorAll(".meeting-nav>.tab-button")
    .forEach((b, i) => b.classList.toggle("active", i === n));
  $(".detail-body").classList.remove("screen-enter");
  void $(".detail-body").offsetWidth;
  $(".detail-body").classList.add("screen-enter");
  $(".detail-body").scrollIntoView({ block: "start", behavior: "smooth" });
}
detail = function (m, previewing = false) {
  return `${previewing ? '<div class="preview-band"><span>效果预览 · 与参会者会议页面同步</span><button onclick="page=\'editor\';step=0;render()">← 返回修改</button></div>' : '<button class="link" onclick="go(\'home\')">← 我发布的会议</button>'}<div class="meeting-stage">${carousel(m)}</div><div class="meeting-nav">${tabs.map((t, i) => `<button class="tab-button ${detailTab === i ? "active" : ""}" onclick="selectDetail(${i})">${t}</button>`).join("")}<div class="actions">${previewing ? "" : meetingActions(m)}</div></div><section class="panel detail-body screen-enter">${detailContent(m, previewing)}</section>`;
};
detailContent = function (m, previewing) {
  if (detailTab === 1)
    return `<h2>${m.status === "未开始" ? "提前了解议程，安排参会计划。" : "把感兴趣的议程，加入日程。"}</h2>${m.talks
      .map((t, i) => {
        const status =
          t.ends <= "2026-11-13T10:35"
            ? "已结束"
            : t.starts > "2026-11-13T10:35"
              ? "未开始"
              : "正在进行";
        return `<article class="prototype-timeline"><div class="agenda-time"><b>${esc(t.starts.slice(11))}</b><br><small>— ${esc(t.ends.slice(11))}</small></div><div><span class="badge">${status}</span><h3>${esc(t.title)}</h3><p>${esc(t.room)} · 嘉宾：${esc(t.speaker)} · ${esc(t.tags)}</p><div class="actions"><button onclick="toggleAgenda(this,${i})">${t.saved ? "✓ 已加入日程" : "＋ 加入日程"}</button><button onclick="toggleMap(this)">地图导航 ↗</button></div><div class="agenda-map" hidden aria-label="地图导航预留区域"></div></div></article>`;
      })
      .join("")}`;

  if (detailTab === 4)
    return `<div class="interaction-feed"><div class="row"><h2>每个问题，都值得认真回答。</h2>${m.status === "进行中" && !previewing ? `<button class="teal ${unread(m) ? "dot" : ""}" onclick="interactionTab=2;go('interaction')">回答问题 · ${unread(m)}</button>` : ""}</div>${m.questions.map((q) => `<article class="comment-card"><span class="avatar">${esc(q.name.slice(0, 1))}</span><div><strong>${esc(q.name)}</strong><small>　${q.time} · ${esc(m.talks[q.talk]?.title)}</small><p>${esc(q.body)}</p>${badge(q.status)}${q.answer ? `<p class="reply">展商回复：${esc(q.answer)}${q.status === "待审核" ? "（审核通过后公开）" : ""}</p>` : ""}</div></article>`).join("") || '<div class="empty">暂无会议提问。</div>'}</div>`;
  if (detailTab === 5) {
    const polls = m.polls.filter((p) => p.status === "已通过");
    return polls.length
      ? polls
          .map((p) => {
            const values = p.options.map((_, i) => p.results?.[i] || 0),
              total = values.reduce((a, b) => a + b, 0);
            return `<h2 class="poll-title">${esc(p.title)}</h2><small>${esc(profile.nick)} · ${time(p.ends)} 截止 · ${total} 人参与</small><h3 style="margin-top:25px">${esc(p.question)}</h3>${p.options.map((o, i) => `<div class="result-option"><div class="row"><span>${esc(o)}</span><strong>${values[i]} 票 · ${total ? Math.round((values[i] / total) * 100) : 0}%</strong></div><progress max="${total || 1}" value="${values[i]}"></progress></div>`).join("")}<p class="muted">展商查看统计结果，不参与投票。</p>`;
          })
          .join("")
      : '<div class="empty"><h3>暂无投票结果</h3><p>投票审核通过后将在这里展示选项与实时统计。</p></div>';
  }
  if (detailTab === 6 || detailTab === 7) {
    const wall = detailTab === 7;
    return `<div class="interaction-feed"><h2>${wall ? "让相遇，留下回响。" : "在交流中，让观点碰撞。"}</h2>${wall ? '<div class="wall-board"><p>相聚西湖，连接每一种可能。</p><p>向更可信的未来，一起出发。</p></div>' : ""}${["林间风", "星野"].map((name, i) => `<article class="comment-card"><span class="avatar">${name[0]}</span><div><strong>${name}</strong><small>　11月13日 10:${20 + i * 5}</small><p>${wall ? ["相聚西湖，连接每一种可能。", "向更可信的未来，一起出发。"][i] : ["主题分享很有启发，期待更多真实案例。", "很高兴与行业伙伴交流，收获满满。"][i]}</p>${wall ? "" : '<span class="badge">很有启发 ✨</span>'}</div></article>`).join("")}</div>`;
  }
  return oldDetailContent(m, previewing);
};
let editCategory = "展商介绍";
materialsForm = function () {
  return `<nav class="tabs material-tabs">${["展商介绍", "会议资讯", "成果展示"].map((t) => `<button class="${editCategory === t ? "active" : ""}" onclick="editCategory='${t}';render()">${t} · ${editor.materials.filter((m) => m.type === t).length}</button>`).join("")}</nav><div class="row"><h3>${editCategory}</h3><button class="primary" onclick="addTypedMaterial()">＋ 添加${editCategory}</button></div><p class="muted">资料按所选分类展示在参会者的“展商内容”栏目，随会议一起审核。</p>${editor.materials.map((m, i) => (m.type !== editCategory ? "" : `<article class="list-row"><div>${badge(m.type)}<h3>${esc(m.title)}</h3><p>${esc(m.product)} · ${m.files.length} 个附件</p></div><div class="actions"><button onclick="materialDialog(${i})">修改</button><button onclick="removeMaterial(${i})">删除</button></div></article>`)).join("") || '<div class="empty">该分类暂无资料，点击右侧按钮添加。</div>'}`;
};
function addTypedMaterial() {
  materialDialog(-1);
  materialDraft.type = editCategory;
  $("#modal select[name=type]").value = editCategory;
}
withdrawMeeting = function () {
  if (!confirm("撤销当前审核并进入修改？重新提交前不会公开展示。")) return;
  const m = active();
  editor = clone(m);
  editor.withdrawn = true;
  meetings = meetings.filter((x) => x.id !== m.id);
  step = 0;
  persist();
  go("editor");
  toast("已撤销审核，修改后可重新提交；不保存草稿。");
};
function aiPage() {
  return `<div class="ai-heading"><span class="eyebrow">HUIXIANG INTELLIGENCE</span><h1>你的办会灵感助手 ✦</h1><p>从筹备会议，到回应现场，让每一次交流更从容。</p></div><section class="assistant-box"><div class="chat-feed" aria-label="问答记录"><p class="chat-bubble">你好，${esc(profile.nick)}。我可以帮助你检查会议资料、查看审核进度、整理互动结果。想先了解什么？</p>${aiHistory.map((r) => `<p class="chat-bubble mine">${esc(r.q)}</p><article class="chat-bubble"><small>展商助手 · 本地演示</small><p class="ai-answer">${esc(r.shown)}</p></article>`).join("")}</div><form class="ai-composer" onsubmit="askExhibitor(event)"><div class="ai-shortcuts">${["我的会议审核进度", "会议发布需要哪些资料", "有哪些问题待回答", "整理我的签到和投票结果"].map((q) => `<button type="button" onclick="document.querySelector('#ai-question').value='${q}'">${q}</button>`).join("")}</div><div class="compact-composer"><textarea id="ai-question" required maxlength="500" placeholder="输入办会相关问题…" aria-label="你的问题" ${aiBusy ? "disabled" : ""}></textarea><button class="primary" ${aiBusy ? "disabled" : ""}>${aiBusy ? "回答中…" : "发送 ↑"}</button></div></form></section>`;
}
// 真实业务处理在 live.js 中实现。
let askExhibitor;
// 真实业务处理在 live.js 中实现。
let toggleAgenda;
function toggleMap(button) {
  const box = button.closest(".actions").nextElementSibling;
  box.hidden = !box.hidden;
  button.textContent = box.hidden ? "地图导航 ↗" : "收起地图 ↑";
}

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
    ),
  clone = (x) => JSON.parse(JSON.stringify(x));
const steps = [
    "会议基础信息",
    "会议详细信息",
    "会议议程",
    "展商资料",
    "效果预览",
    "发布会议",
  ],
  tabs = [
    "活动详情",
    "会议议程",
    "直播和回放",
    "展商内容",
    "会议提问",
    "议程投票",
    "评论区",
    "签名墙",
  ],
  key = "huixiang-exhibitor-prototype-v1";
const art = (title, teal = false) =>
  "data:image/svg+xml;charset=utf-8," +
  encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="500"><defs><linearGradient id="g"><stop stop-color="${teal ? "#306779" : "#242650"}"/><stop offset="1" stop-color="${teal ? "#87b9bc" : "#8870d2"}"/></linearGradient></defs><rect width="1200" height="500" fill="url(#g)"/><g fill="none" stroke="#ffffff33"><ellipse cx="1000" cy="250" rx="200" ry="180"/><ellipse cx="1000" cy="250" rx="125" ry="220"/><ellipse cx="1000" cy="250" rx="235" ry="80"/></g><text x="70" y="125" font-family="sans-serif" font-size="18" letter-spacing="7" fill="#cec2fa">WESTLAKE SUMMIT / 2026</text><text x="70" y="285" font-family="Microsoft YaHei,sans-serif" font-size="52" fill="white">${esc(title)}</text></svg>`,
  );
function blank() {
  return {
    id: null,
    status: "预览",
    title: "",
    place: "",
    starts: "",
    ends: "",
    capacity: "",
    cover: "",
    poster: "",
    posters: [],
    coverTitle: "",
    meetingType: "",
    content: "",
    features: [
      { title: "", body: "" },
      { title: "", body: "" },
      { title: "", body: "" },
    ],
    talks: [],
    materials: [],
    checks: [],
    polls: [],
    questions: [],
    log: [],
  };
}

let meetings = [],
  profile = { nick: "", realName: "", avatar: "" },
  draft = null;
let page = "home",
  step = 0,
  selected = 1,
  detailTab = 0,
  mode = "exhibitor",
  returnTo = "detail",
  filter = "全部",
  query = "",
  editor = null,
  newId = null,
  interactionTab = 0,
  materialFilter = "展商介绍";
function persist() {}
function toast(t) {
  $("#toast").textContent = t;
  $("#toast").classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => $("#toast").classList.remove("show"), 3300);
}
function active() {
  return meetings.find((m) => m.id === selected);
}
function time(v) {
  return v ? esc(v.replace("T", " ")) : "待填写";
}
function badge(s) {
  return `<span class="badge ${{ 进行中: "live", 已通过: "approved", 待审核: "pending", 已结束: "ended", 草稿: "draft", 已驳回: "rejected", 待回答: "pending" }[s] || ""}">${esc(s)}</span>`;
}
function unread(m) {
  return m.questions.filter((q) => q.status === "待回答").length;
}
function log(m, t) {
  m.log.unshift({ time: new Date().toLocaleString("zh-CN"), text: t });
}
function go(p) {
  page = p;
  render();
  window.scrollTo({ top: 0, behavior: "instant" });
}
// 真实业务处理在 live.js 中实现。
let newMeeting;
function openMeeting(id) {
  selected = id;
  detailTab = 0;
  mode = "exhibitor";
  go("detail");
}
function editMeeting() {
  editor = clone(active());
  step = 0;
  go("editor");
}
function stepTo(n) {
  if (n === 4) return preview();
  step = n;
  render();
  window.scrollTo({ top: 0, behavior: "instant" });
}
function setField(path, value) {
  const keys = path.split(".");
  let o = editor;
  keys.slice(0, -1).forEach((k) => (o = o[k]));
  o[keys.at(-1)] = value;
}
function field(l, k, type = "text", wide = false) {
  let v = k.split(".").reduce((o, x) => o?.[x], editor);
  return `<label id="field-${k.replaceAll(".", "-")}" class="${wide ? "wide" : ""}">${l} *<input type="${type}" value="${esc(v)}" ${type === "number" ? 'min="1"' : ""} onchange="setField('${k}',this.value)"></label>`;
}
function area(l, k) {
  return `<label id="field-${k}" class="wide">${l} *<textarea onchange="setField('${k}',this.value)">${esc(editor[k])}</textarea></label>`;
}
function upload(l, k, d) {
  return `<div class="upload" id="field-${k}"><strong>${l} *</strong>${editor[k] ? `<img src="${esc(editor[k])}" alt="${l}预览">` : '<span class="muted">＋ 选择本地图片</span>'}<input type="file" aria-label="${l}" accept="image/png,image/jpeg,image/webp" onchange="readImage(this,'${k}')"><small>${d}</small></div>`;
}
async function imageData(f) {
  if (!f || !f.type.startsWith("image/")) throw Error("请选择图片文件");
  if (f.size > 5 * 1024 * 1024) throw Error("图片请小于5MB");
  const b = await createImageBitmap(f),
    c = document.createElement("canvas"),
    r = Math.min(1, 1400 / Math.max(b.width, b.height));
  c.width = b.width * r;
  c.height = b.height * r;
  c.getContext("2d").drawImage(b, 0, 0, c.width, c.height);
  b.close();
  return c.toDataURL("image/jpeg", 0.8);
}
async function readImage(input, k) {
  try {
    setField(k, await imageData(input.files[0]));
    render();
  } catch (e) {
    toast(e.message);
  }
}
function modal(html) {
  $("#modalbody").innerHTML = html;
  $("#modal").showModal();
}
function closeModal() {
  $("#modal").close();
}
function render() {
  $("#topnav").innerHTML =
    `<button class="${["home", "detail", "interaction"].includes(page) ? "active" : ""}" onclick="go('home')">我发布的会议</button><button class="${["editor", "preview"].includes(page) ? "active" : ""}" onclick="newMeeting()">发布新的会议</button>`;
  $("#nickname").textContent = profile.nick;
  $("#avatar").innerHTML = profile.avatar
    ? `<img src="${esc(profile.avatar)}" alt="头像">`
    : esc(profile.realName.slice(0, 1));
  $("#app").innerHTML =
    page === "home"
      ? home()
      : page === "editor"
        ? editorPage()
        : page === "detail"
          ? detail(active())
          : page === "preview"
            ? detail(editor, true)
            : page === "interaction"
              ? interaction()
              : profilePage();
}
function home() {
  return `<section class="heading"><div><span class="eyebrow">YOUR EVENTS · YOUR IMPACT</span><h1>让每一次相聚，产生价值。</h1><p>你好，${esc(profile.nick)}。在这里管理你的会议与交流。</p></div><button class="primary" onclick="newMeeting()">＋ 发布新的会议</button></section><div class="metrics"><div><strong>${meetings.length}</strong>场会议</div><div><strong>${meetings.filter((m) => m.status === "进行中").length}</strong>场进行中</div><div><strong>${meetings.filter((m) => m.status === "待审核").length}</strong>场待审核</div></div><div class="row"><h2>我发布的会议</h2><input class="search" value="${esc(query)}" placeholder="搜索会议名称或地点" aria-label="搜索会议" oninput="query=this.value;renderCards()"></div><nav class="filters">${["全部", "进行中", "未开始", "待审核", "已结束"].map((s) => `<button class="${filter === s ? "active" : ""}" onclick="filter='${s}';render()">${s}</button>`).join("")}</nav><div id="cards" class="grid">${cards()}</div>`;
}
function cards() {
  const rows = meetings.filter(
    (m) =>
      (filter === "全部" || m.status === filter) &&
      (m.title + m.place).includes(query),
  );
  return rows.length
    ? rows
        .map(
          (m, i) =>
            `<article class="card ${newId === m.id ? "new-card" : ""}" style="animation-delay:${i * 45}ms"><div class="cover t${i % 3}" onclick="openMeeting(${m.id})">${m.cover ? `<img src="${esc(m.cover)}" alt="会议封面">` : ""}${!m.cover || m.coverTitle || m.meetingType ? `<small>${esc(meetingType(m))} / ${new Date(m.starts).getFullYear()}</small><span class="cover-caption">${esc(m.coverTitle || meetingType(m) + " · 新视野")}</span>` : ""}</div><div class="card-body">${badge(m.status)}${unread(m) ? ` <span class="badge pending">${unread(m)} 条待回答</span>` : ""}<h3>${esc(m.title || "未命名会议")}</h3><p>${time(m.starts).slice(0, 16)} · ${esc(m.place)}</p><div class="row"><small>${m.capacity} 人 · ${m.talks.length} 个议程</small><button class="primary" onclick="openMeeting(${m.id})">查看会议 ↗</button></div></div></article>`,
        )
        .join("")
    : '<div class="empty wide"><h3>没有匹配的会议</h3><p>试试其他状态，或发布你的第一场会议。</p></div>';
}
function renderCards() {
  $("#cards").innerHTML = cards();
}
function editorPage() {
  return `<div class="heading"><div><span class="eyebrow">CREATE SOMETHING MEANINGFUL</span><h1>${editor.id ? "修改会议" : "发布新的会议"}</h1><p>从一份想法，到一场值得参与的相聚。</p></div></div><div class="editor"><aside class="sidebar">${steps.map((s, i) => `<button class="${step === i ? "active" : ""}" onclick="stepTo(${i})"><span class="num">0${i + 1}</span>${s}</button>`).join("")}<small>基础与详细信息必填<br>至少安排一个完整议程<br>审核通过后公开展示</small></aside><section class="panel screen-enter"><div class="section-title"><span class="eyebrow">STEP 0${step + 1}</span><h2>${steps[step]}</h2><p>${["封面用于会议列表，海报用于活动详情顶部。", "以下内容将原样呈现在参会者的活动详情中。", "时间、会场、嘉宾与标签同步呈现在会议议程中。", "统一管理展商介绍、会议资讯、成果与附件。", "", "提交后进入待审核，审核通过后公开展示。"][step]}</p></div>${step === 0 ? basic() : step === 1 ? detailsForm() : step === 2 ? talksForm() : step === 3 ? materialsForm() : releaseForm()}${step < 4 ? `<div class="footer-actions"><small>* 为必填项</small><div class="actions">${step > 0 ? `<button onclick="stepTo(${step - 1})">上一步</button>` : ""}<button class="primary" onclick="stepTo(${step + 1})">${step === 3 ? "效果预览 ↗" : "下一步 →"}</button></div></div>` : ""}</section></div>`;
}
function basic() {
  return `<div class="form-grid">${upload("封面图片", "cover", "用于参会者首页卡片，建议横向图片。")}${posterUpload()}${coverFields()}${field("会议标题", "title", "text", true)}${field("会议地点", "place", "text", true)}${field("开始时间", "starts", "datetime-local")}${field("结束时间", "ends", "datetime-local")}${field("预计人数", "capacity", "number")}</div>`;
}
function detailsForm() {
  return `<div class="form-grid">${area("会议介绍", "content")}${editor.features.map((f, i) => `<div class="wide"><h3 style="margin:15px 0">亮点 0${i + 1}</h3><div class="form-grid">${field("亮点标题", `features.${i}.title`)}${field("亮点说明", `features.${i}.body`)}</div></div>`).join("")}</div>`;
}
function talksForm() {
  return `<div class="row"><h3>已添加 ${editor.talks.length} 个议程</h3><button class="primary" onclick="talkDialog(-1)">＋ 添加议程</button></div><div id="field-talks">${editor.talks.length ? editor.talks.map((t, i) => `<article class="list-row"><span class="time">${esc(t.starts.slice(11))}</span><div><h3>${esc(t.title)}</h3><p>${time(t.starts)} — ${time(t.ends)}<br>${esc(t.room)} · 嘉宾：${esc(t.speaker)}<br>${esc(t.tags)}</p></div><div class="actions"><button onclick="talkDialog(${i})">修改</button><button class="danger" onclick="removeTalk(${i})">删除</button></div></article>`).join("") : '<div class="empty"><h3>还没有安排议程</h3><p>至少添加一个完整议程，才能预览和提交会议。</p></div>'}</div>`;
}
function talkDialog(i) {
  const t =
    i < 0
      ? {
          title: "",
          starts: editor.starts,
          ends: editor.ends,
          room: "",
          speaker: "",
          tags: "",
        }
      : editor.talks[i];
  modal(
    `<div class="heading"><h2>${i < 0 ? "添加" : "修改"}议程</h2><button onclick="closeModal()">✕</button></div><form onsubmit="saveTalk(event,${i})"><div class="form-grid">${[
      ["议程标题", "title", "text"],
      ["会场", "room", "text"],
      ["开始时间", "starts", "datetime-local"],
      ["结束时间", "ends", "datetime-local"],
      ["嘉宾", "speaker", "text"],
      ["兴趣标签", "tags", "text"],
    ]
      .map(
        ([l, k, type]) =>
          `<label>${l} *<input name="${k}" type="${type}" value="${esc(t[k])}" required></label>`,
      )
      .join(
        "",
      )}</div><p class="muted">议程时间须在会议时间范围内；标签以顿号或逗号分隔。</p><div class="footer-actions"><button type="button" onclick="closeModal()">取消</button><button class="primary">保存议程</button></div></form>`,
  );
}
function saveTalk(e, i) {
  e.preventDefault();
  const t = Object.fromEntries(new FormData(e.target));
  if (t.ends <= t.starts || t.starts < editor.starts || t.ends > editor.ends)
    return toast("议程开始须早于结束，且位于会议时间内。");
  if (
    editor.talks.some(
      (x, j) =>
        j !== i && x.room === t.room && x.starts < t.ends && x.ends > t.starts,
    )
  )
    return toast("同一会场在该时间已有议程，请调整时间。");
  if (i < 0) editor.talks.push(t);
  else editor.talks[i] = { ...editor.talks[i], ...t };
  closeModal();
  render();
  toast("议程已保存");
}
function removeTalk(i) {
  if (confirm("删除此议程？")) {
    editor.talks.splice(i, 1);
    render();
  }
}
function materialsForm() {
  return `<div class="row"><h3>内容与附件</h3><button class="primary" onclick="materialDialog(-1)">＋ 添加资料</button></div><p class="muted">可选内容；与会议一起提交审核，同步显示在参会者的“展商内容”中。</p>${editor.materials.length ? editor.materials.map((m, i) => `<article class="list-row"><div>${badge(m.type)}<h3>${esc(m.title)}</h3><p>${esc(m.product)} · ${m.files.length} 个附件</p></div><div class="actions"><button onclick="materialDialog(${i})">修改</button><button onclick="removeMaterial(${i})">删除</button></div></article>`).join("") : '<div class="empty"><h3>还没有添加展商资料</h3><p>添加展商介绍、会议资讯或成果展示，支持本地图片与附件。</p></div>'}`;
}
let materialDraft;
function materialDialog(i) {
  materialDraft = clone(
    i < 0
      ? {
          type: "展商介绍",
          title: "",
          product: "",
          body: "",
          image: "",
          files: [],
        }
      : editor.materials[i],
  );
  modal(
    `<div class="heading"><h2>${i < 0 ? "添加" : "修改"}资料</h2><button onclick="closeModal()">✕</button></div><form onsubmit="saveMaterial(event,${i})"><div class="form-grid"><label>内容类型<select name="type">${["展商介绍", "会议资讯", "成果展示"].map((x) => `<option ${x === materialDraft.type ? "selected" : ""}>${x}</option>`).join("")}</select></label><label>标题 *<input name="title" required value="${esc(materialDraft.title)}"></label><label class="wide">简介 / 产品说明<input name="product" value="${esc(materialDraft.product)}"></label><label class="wide">正文 *<textarea name="body" required>${esc(materialDraft.body)}</textarea></label><label>配图<input type="file" accept="image/*" onchange="materialImage(this)"><span id="material-image">${materialDraft.image ? "<small>已选择图片，可重新上传替换</small>" : ""}</span></label><label>附件<input type="file" multiple onchange="addFiles(this)"><small>支持PDF、PPT、PPTX、DOCX、TXT，单个附件不超过2MB。</small></label></div><div id="files-list">${fileList()}</div><div class="footer-actions"><button type="button" onclick="closeModal()">取消</button><button class="primary">保存资料</button></div></form>`,
  );
}
async function materialImage(input) {
  try {
    materialDraft.image = await imageData(input.files[0]);
    $("#material-image").innerHTML =
      `<img class="preview-attachment" src="${materialDraft.image}" alt="资料配图">`;
  } catch (e) {
    toast(e.message);
  }
}
function fileList() {
  return materialDraft.files
    .map(
      (f, i) =>
        `<div class="file-item"><span>▤ ${esc(f.name)} <small>${esc(f.size)}</small></span><button type="button" class="link" onclick="materialDraft.files.splice(${i},1);document.querySelector('#files-list').innerHTML=fileList()">移除</button></div>`,
    )
    .join("");
}
async function addFiles(input) {
  for (const file of input.files) {
    if (file.size > 2 * 1024 * 1024) {
      toast("附件过大，请选择2MB以内文件");
      continue;
    }
    const data = await new Promise((r) => {
      const reader = new FileReader();
      reader.onload = () => r(reader.result);
      reader.readAsDataURL(file);
    });
    materialDraft.files.push({
      name: file.name,
      size: Math.ceil(file.size / 1024) + " KB",
      data,
    });
  }
  $("#files-list").innerHTML = fileList();
}
function saveMaterial(e, i) {
  e.preventDefault();
  Object.assign(materialDraft, Object.fromEntries(new FormData(e.target)));
  if (i < 0) editor.materials.push(materialDraft);
  else editor.materials[i] = materialDraft;
  closeModal();
  render();
  toast("资料与附件已保存");
}
function removeMaterial(i) {
  if (confirm("移除这份资料及附件？")) {
    editor.materials.splice(i, 1);
    render();
  }
}
function invalid() {
  for (const [k, l] of [
    ["cover", "封面图片"],
    ["poster", "海报图片"],
    ["title", "会议标题"],
    ["place", "会议地点"],
    ["starts", "开始时间"],
    ["ends", "结束时间"],
    ["capacity", "预计人数"],
  ])
    if (!String(editor[k]).trim()) return [0, k, `请完善${l}`];
  if (editor.ends <= editor.starts)
    return [0, "ends", "结束时间须晚于开始时间"];
  if (!Number.isInteger(Number(editor.capacity)) || Number(editor.capacity) < 1)
    return [0, "capacity", "预计人数须为正整数"];
  if (!editor.content.trim()) return [1, "content", "请填写会议介绍"];
  for (let i = 0; i < 3; i++)
    for (const k of ["title", "body"])
      if (!editor.features[i][k].trim())
        return [1, `features-${i}-${k}`, `请完善亮点 ${i + 1}`];
  if (!editor.talks.length) return [2, "talks", "请至少添加一个议程"];
  if (
    editor.talks.some(
      (t) =>
        t.starts < editor.starts || t.ends > editor.ends || t.ends <= t.starts,
    )
  )
    return [2, "talks", "会议时间已改变，请调整议程时间范围"];
  return null;
}
function validate() {
  const issue = invalid();
  if (!issue) return true;
  step = issue[0];
  page = "editor";
  render();
  requestAnimationFrame(() => {
    const el = $("#field-" + issue[1]);
    if (el) {
      el.scrollIntoView({ block: "center", behavior: "instant" });
      el.classList.add("flash");
      const note = document.createElement("p");
      note.className = "error-note";
      note.textContent = issue[2];
      el.append(note);
      setTimeout(() => {
        el.classList.remove("flash");
        note.remove();
      }, 2800);
      el.querySelector("input,textarea")?.focus({ preventScroll: true });
    }
    toast(issue[2]);
  });
  return false;
}
function preview() {
  if (!validate()) return;
  detailTab = 0;
  mode = "preview";
  go("preview");
}
function releaseForm() {
  const issue = invalid();
  return `<div class="release"><div class="success-mark">${issue ? "…" : "✓"}</div><h2>${issue ? "还有信息需要完善" : "准备好让这场相聚发生了吗？"}</h2><p class="muted">会议与议程、资料将一并送审。</p><div class="steps">${[
    ["基础信息", !!editor.title && !!editor.cover && !!editor.poster],
    [
      "会议详细信息",
      !!editor.content && editor.features.every((x) => x.title && x.body),
    ],
    ["会议议程", editor.talks.length > 0],
    ["展商资料（选填）", true],
  ]
    .map(
      ([t, ok]) =>
        `<p><span class="check">${ok ? "✓" : "○"}</span>${t}${t === "会议议程" ? ` · ${editor.talks.length} 个` : ""}</p>`,
    )
    .join(
      "",
    )}</div>${editor.id && editor.status === "未开始" ? '<div class="notice">提交修改后会议将重新进入待审核，审核通过前不对参会者展示新版本。</div>' : ""}<div class="actions" style="justify-content:center"><button onclick="preview()">查看效果预览</button><button class="primary" onclick="publish()">${editor.id ? "提交修改并送审" : "发布会议并送审"} →</button></div><p><small>待审核期间可以撤销审核；未提交修改不会保存。</small></p></div>`;
}
function saveDraft() {
  toast("草稿已保存，下次点击“发布新的会议”可以继续。");
}
// 真实业务处理在 live.js 中实现。
let publish;
// 真实业务处理在 live.js 中实现。
let withdrawMeeting;
function detail(m, previewing = false) {
  const participant = mode === "participant";
  const extras = previewing
    ? ""
    : participant
      ? ""
      : m.status === "已结束"
        ? ""
        : ["未开始", "待审核"].includes(m.status)
          ? `<button class="primary" onclick="editMeeting()">修改会议</button>${m.status === "待审核" ? '<button onclick="withdrawMeeting()">撤销审核</button>' : ""}`
          : `<button class="teal ${unread(m) ? "dot" : ""}" onclick="interactionTab=0;go('interaction')">互动${unread(m) ? " · " + unread(m) : ""}</button>`;
  return `${previewing ? '<div class="preview-band"><span>效果预览 · 内容来自当前编辑稿</span><button onclick="page=\'editor\';step=0;render()">← 返回修改</button></div>' : participant ? '<div class="preview-band"><span>参会者视角演示 · 仅展示审核通过的互动内容</span><button onclick="participantCheck()">签到服务演示</button><button onclick="mode=\'exhibitor\';go(returnTo)">返回展商操作</button></div>' : '<button class="link" onclick="go(\'home\')">← 我发布的会议</button>'}<div class="hero" style="margin-top:16px">${m.poster ? `<img src="${esc(m.poster)}" alt="会议海报">` : ""}<div class="hero-copy"><span class="eyebrow">WESTLAKE SUMMIT / 2026</span><h1>${esc(m.title)}</h1><p>${esc(m.place)} · ${time(m.starts)}</p>${badge(previewing ? "预览" : m.status)}</div></div><div class="meeting-nav">${tabs.map((t, i) => `<button class="${detailTab === i ? "active" : ""}" onclick="detailTab=${i};render()">${t}</button>`).join("")}<div class="actions">${extras}</div></div><section class="panel detail-body screen-enter">${detailContent(m, previewing)}</section>${!previewing && !participant ? `<details style="margin-top:20px"><summary class="muted">操作记录 · ${m.log.length} 条</summary>${m.log.map((l) => `<p class="muted">${esc(l.time)}　${esc(l.text)}</p>`).join("")}</details>` : ""}`;
}
function detailContent(m, previewing) {
  if (detailTab === 0)
    return `<span class="eyebrow">ABOUT THE SUMMIT</span><h2>${esc(m.title)}</h2><p class="muted">${time(m.starts)} · ${esc(m.place)} · 预计 ${m.capacity} 人</p><div class="feature-grid">${m.features.map((f) => `<article class="feature"><h3>${esc(f.title)}</h3><p>${esc(f.body)}</p></article>`).join("")}</div><p class="pre">${esc(m.content)}</p>`;
  if (detailTab === 1)
    return `<h2>把精彩，安排进每一个时刻。</h2>${m.talks.map((t) => `<article class="list-row"><span class="time">${esc(t.starts.slice(11))}</span><div><h3>${esc(t.title)}</h3><p>${time(t.starts)} — ${time(t.ends)}<br>${esc(t.room)} · 嘉宾：${esc(t.speaker)} · ${esc(t.tags)}</p></div></article>`).join("")}`;
  if (detailTab === 2)
    return `<span class="eyebrow">LIVE & REPLAY</span><h2>不错过每一个精彩瞬间。</h2>${m.talks.map((t) => `<details class="review-item"><summary>${esc(t.title)} · ${esc(t.starts.slice(11))}</summary><div class="empty"><h3>${m.status === "已结束" ? "暂无回放" : "直播未开启"}</h3><p>由管理员开放直播与回放，展商无需在此发布。</p></div></details>`).join("")}`;
  if (detailTab === 3)
    return `<h2>从展台，到真实应用。</h2><nav class="filters">${["展商介绍", "会议资讯", "成果展示"].map((s) => `<button class="${materialFilter === s ? "active" : ""}" onclick="materialFilter='${s}';render()">${s}</button>`).join("")}</nav>${
      m.materials
        .filter((x) => x.type === materialFilter)
        .map(
          (x) =>
            `<article class="review-item"><h3>${esc(x.title)}</h3><p>${esc(x.product)}</p><details><summary>查看资料 ↗</summary>${x.image ? `<img class="preview-attachment" src="${esc(x.image)}" alt="资料图片">` : ""}<p class="pre">${esc(x.body)}</p>${x.files.map((f) => `<div class="file-item"><span>▤ ${esc(f.name)} · ${esc(f.size)}</span>${f.data ? `<a download="${esc(f.name)}" href="${esc(f.data)}">下载</a>` : '<button class="link" onclick="toast(\'演示附件名称；实际上传的本地文件可下载预览。\')">演示附件</button>'}</div>`).join("")}</details></article>`,
        )
        .join("") || '<div class="empty">暂无这类资料。</div>'
    }`;
  if (detailTab === 4)
    return `<h2>每个问题，都值得认真回答。</h2>${m.questions.length ? m.questions.map((q) => `<article class="review-item"><small>${esc(q.name)} · ${q.time} · ${esc(m.talks[q.talk]?.title)}</small><h3>${esc(q.body)}</h3>${q.status === "已通过" ? `<p class="pre">展商回复：${esc(q.answer)}</p>` : '<p class="muted">等待展商回复</p>'}</article>`).join("") : '<div class="empty">暂无会议提问。</div>'}${mode === "participant" ? '<button style="margin-top:20px" onclick="simulateQuestion()">模拟参会者提出问题</button>' : ""}`;
  if (detailTab === 5) {
    const rows = m.polls.filter((p) => p.status === "已通过");
    return rows.length
      ? rows
          .map(
            (p) =>
              `<h2 class="poll-title">${esc(p.title)}</h2><small>${esc(profile.nick)} · ${time(p.ends)} 截止 · 演示投票</small><h3 style="margin-top:25px">${esc(p.question)}</h3>${p.options.map((o, i) => `<label class="option"><input type="radio" name="poll-${p.id}" value="${i}" ${p.demoChoice !== undefined ? "disabled" : ""} ${p.demoChoice === i ? "checked" : ""}>${esc(o)}</label>`).join("")}<button class="primary" ${p.demoChoice !== undefined ? "disabled" : ""} onclick="demoVote(${p.id})">${p.demoChoice !== undefined ? "✓ 已投票" : "提交答案 →"}</button>`,
          )
          .join("<hr>")
      : '<div class="empty"><h3>暂无投票</h3><p>本场会议尚未发布已通过审核的投票。</p></div>';
  }
  if (detailTab === 6)
    return '<h2>在交流中，让观点碰撞。</h2><article class="list-row"><span class="avatar">林</span><div><strong>林间风</strong><small>　11月13日 10:20 · 演示</small><p>主题分享很有启发，期待更多真实案例。</p></div></article><article class="list-row"><span class="avatar">星</span><div><strong>星野</strong><small>　11月13日 10:25 · 演示</small><p>很高兴与行业伙伴交流，收获满满。</p></div></article>';
  return '<h2>让相遇，留下回响。</h2><p class="muted">参会者签名展示 · 演示</p><div class="wall-board"><p>相聚西湖，连接每一种可能。</p><p>向更可信的未来，一起出发。</p></div>';
}

function interaction() {
  const m = active();
  return `<div class="heading"><div><button class="link" onclick="mode='exhibitor';go('detail')">← ${esc(m.title)}</button><h1>让互动，成为会议的一部分。</h1><p>所有发布内容须经审核，审核通过后才会同步给参会者。</p></div></div><nav class="tabs">${["发布签到", "发布投票", "回答问题"].map((t, i) => `<button class="${interactionTab === i ? "active" : ""} ${i === 2 && unread(m) ? "dot" : ""}" onclick="interactionTab=${i};render()">${t}${i === 2 && unread(m) ? ` · ${unread(m)}` : ""}</button>`).join("")}</nav>${interactionTab === 0 ? checkPage(m) : interactionTab === 1 ? pollPage(m) : answerPage(m)}`;
}
function talkOptions(m) {
  return m.talks
    .map((t, i) => `<option value="${i}">${esc(t.title)}</option>`)
    .join("");
}
function checkPage(m) {
  return `<div class="interactive-layout"><section class="panel"><div class="section-title"><h2>发布议程签到</h2><p>发布签到场次，审核后开放个人签到码，由工作人员扫码核销。</p></div><form onsubmit="submitCheck(event)"><label>对应议程<select name="talk" onchange="checkTimes(this.value)">${talkOptions(m)}</select></label><div class="hint" id="talk-info">${esc(m.talks[0].room)} · ${time(m.talks[0].starts)} — ${time(m.talks[0].ends)}</div><div class="form-grid"><label>签到开始时间<input type="datetime-local" name="starts" value="${m.talks[0].starts}" required></label><label>签到结束时间<input type="datetime-local" name="ends" value="${m.talks[0].ends}" required></label></div><p class="muted">允许在议程开始前2小时内开放签到，结束时间不得晚于议程结束。</p><div class="footer-actions"><small>审批前不会生成可用入口</small><button class="primary">提交签到审核</button></div></form></section><section class="panel"><h2>签到发布记录</h2>${m.checks.length ? m.checks.map((c) => `<article class="review-item"><div class="row"><h3>${esc(m.talks[c.talk]?.title)}</h3>${badge(c.status)}</div><p>${time(c.starts)} — ${time(c.ends)}</p>${c.status === "已通过" ? `<p>已签到 ${c.count || 0} 人 · 预计 ${m.capacity} 人</p><button onclick="showCheck(${c.id})">签到入口与结果</button>` : c.status === "待审核" ? `<button onclick="withdrawInteraction('checks',${c.id})">撤销审核</button>` : "<small>已退出审核队列，可重新发布</small>"}</article>`).join("") : '<div class="empty">暂无发布记录</div>'}</section></div>`;
}
function checkTimes(i) {
  const t = active().talks[i];
  $("#talk-info").textContent =
    `${t.room} · ${t.starts.replace("T", " ")} — ${t.ends.replace("T", " ")}`;
  $("[name=starts]").value = t.starts;
  $("[name=ends]").value = t.ends;
}
// 真实业务处理在 live.js 中实现。
let submitCheck;
// 真实业务处理在 live.js 中实现。
let showCheck;
function pollPage(m) {
  return `<div class="interactive-layout"><section class="panel"><div class="section-title"><h2>发起一场议程投票</h2><p>大标题与题目、选项将同步显示在参会者的“议程投票”中。</p></div><form onsubmit="submitPoll(event)"><label>投票大标题 *<input name="title" placeholder="例如：下一场分享，由你决定。" maxlength="120" required></label><label style="margin-top:18px">题目 *<input name="question" placeholder="你最希望深入了解哪个主题？" required></label><label style="margin-top:18px">单选选项 *<textarea name="options" placeholder="每行一个选项，至少2项" required></textarea></label><div class="form-grid"><label>开始时间<input name="starts" type="datetime-local" value="${m.starts}" required></label><label>截止时间<input name="ends" type="datetime-local" value="${m.ends}" required></label></div><div class="footer-actions"><small>提交后等待审核</small><button class="primary">提交投票审核</button></div></form></section><section class="panel"><h2>投票发布记录</h2>${m.polls.map((p) => `<article class="review-item"><div class="row"><h3>${esc(p.title)}</h3>${badge(p.status)}</div><p>${esc(p.question)}</p><p>${p.options.length} 个选项 · ${time(p.ends)} 截止</p>${p.status === "待审核" ? `<button onclick="withdrawInteraction('polls',${p.id})">撤销审核</button>` : p.status === "已通过" ? '<button onclick="detailTab=5;go(&quot;detail&quot;)">查看投票结果 ↗</button>' : "<small>已撤销，可重新发布</small>"}</article>`).join("") || '<div class="empty">暂无投票发布记录</div>'}</section></div>`;
}
// 真实业务处理在 live.js 中实现。
let submitPoll;
function answerPage(m) {
  return `<section class="panel"><div class="section-title row"><div><h2>让每个疑问，都得到回应。</h2><p>${unread(m)} 条新问题待回答；回复审核通过后公开展示。</p></div></div>${m.questions.map((q) => `<article class="review-item"><div class="row"><div><small>${esc(q.name)} · ${q.time} · ${esc(m.talks[q.talk]?.title)}</small><h3 style="margin-top:8px">${esc(q.body)}</h3></div>${badge(q.status)}</div>${q.status === "待回答" ? `<form onsubmit="submitAnswer(event,${q.id})"><textarea name="answer" required placeholder="给参会者一个清晰、具体的回答…" style="margin-top:18px">${esc(q.answer)}</textarea><div class="actions" style="justify-content:flex-end;margin-top:12px"><button class="primary">提交回答审核</button></div></form>` : `<p class="pre">${esc(q.answer)}</p>${q.status === "待审核" ? `<button onclick="withdrawInteraction('questions',${q.id})">撤销审核</button>` : "<small>该回答已对参会者展示</small>"}`}</article>`).join("")}</section>`;
}
// 真实业务处理在 live.js 中实现。
let submitAnswer;
// 真实业务处理在 live.js 中实现。
let withdrawInteraction;

function profilePage() {
  return `<div class="heading"><div><span class="eyebrow">MY CONFERENCE SPACE</span><h1>你好，${esc(profile.nick)}。</h1><p>完善你的信息，让每一次连接更有温度。</p></div></div><div class="profile-grid"><aside class="panel profile-side"><span class="avatar">${profile.avatar ? `<img src="${esc(profile.avatar)}" alt="头像">` : esc(profile.realName.slice(0, 1))}</span><h3 style="margin:15px 0">${esc(profile.nick)}</h3>${badge("展商")}<button class="active" onclick="go('profile')">个人资料</button><button onclick="go('home')">我的会议</button><button onclick="passwordDialog()">修改密码</button></aside><section class="panel"><div class="section-title"><h2>个人资料</h2><p>账号与角色不可修改，其他个人信息可更新。展商无需填写兴趣标签。</p></div><form onsubmit="saveProfile(event)"><div class="form-grid"><label>账号<input value="demo_exhibit" readonly></label><label>角色<input value="展商" readonly></label><label>网名 *<input name="nick" value="${esc(profile.nick)}" required></label><label>真实姓名 *<input name="realName" value="${esc(profile.realName)}" required></label><label>性别<select name="gender">${["未设置", "男", "女"].map((s) => `<option ${profile.gender === s ? "selected" : ""}>${s}</option>`).join("")}</select></label><label>邮箱 *<input type="email" name="email" value="${esc(profile.email)}" required></label><label>电话 *<input name="phone" value="${esc(profile.phone)}" required></label><label>单位<input name="company" value="${esc(profile.company)}"></label><label class="wide">头像<input type="file" accept="image/*" onchange="profileImage(this)"></label></div><div class="footer-actions"><small>保存后同步到顶部头像和网名</small><button class="primary">保存个人资料</button></div></form></section></div>`;
}
// 真实业务处理在 live.js 中实现。
let profileImage;
// 真实业务处理在 live.js 中实现。
let saveProfile;
// 真实业务处理在 live.js 中实现。
let passwordDialog;
// 真实业务处理在 live.js 中实现。
let changePassword;

window.addEventListener("hashchange", () => {
  if (location.hash === "#home") go("home");
});

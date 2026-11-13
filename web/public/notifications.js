// 同源消息通知，仅读取当前登录者的站内信。
(() => {
  const host = document.createElement("div");
  host.className = "notification-wrap";
  const account = document.querySelector(".account-group");
  if (!account) return;
  account.prepend(host);
  let notes = [],
    opened = false;
  const escape = (v) =>
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
  async function load() {
    try {
      const r = await fetch("/api/notes");
      if (!r.ok) return;
      notes = await r.json();
      draw();
    } catch {}
  }
  function draw() {
    const unread = notes.filter((n) => !n.seen).length;
    host.innerHTML = `<button class="notification-bell" aria-label="消息通知" aria-expanded="${opened}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg>${unread ? `<span class="notification-count">${Math.min(99, unread)}</span>` : ""}</button>${opened ? `<section class="notification-menu"><div class="notification-title"><strong>消息通知</strong><button class="read-notes">全部已读</button><button class="close-notes" aria-label="关闭通知">✕</button></div>${notes.map((n) => `<article class="${n.seen ? "" : "unread"}"><p>${escape(n.body)}</p><small>${escape(new Date(n.created).toLocaleString("zh-CN", { hour12: false }))}</small></article>`).join("") || "<p>暂无消息</p>"}</section>` : ""}`;
    host.querySelector(".notification-bell").onclick = () => {
      opened = !opened;
      draw();
      if (opened) load();
    };
    host.querySelector(".close-notes")?.addEventListener("click", () => {
      opened = false;
      draw();
    });
    host.querySelector(".read-notes")?.addEventListener("click", async () => {
      const r = await fetch("/api/notes/read", {
        method: "POST",
        headers: { "X-App-Request": "1" },
      });
      if (r.ok) load();
    });
  }
  draw();
  load();
  setInterval(load, 15000);
})();

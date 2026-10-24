// 统一请求入口。Session Cookie 由浏览器管理，写请求携带防跨站请求头。
export async function api(path, method = "GET", data) {
  const isFile = data instanceof FormData;
  const headers = { "X-App-Request": "1" };
  if (!isFile) headers["Content-Type"] = "application/json";
  const response = await fetch("/api" + path, {
    method,
    credentials: "same-origin",
    headers,
    body: data === undefined ? undefined : isFile ? data : JSON.stringify(data),
  });
  const text = await response.text();
  let body = {};
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    /* 保留统一错误提示。 */
  }
  if (!response.ok) {
    if (response.status === 401 && path !== "/login") {
      window.dispatchEvent(new Event("session-ended"));
    }
    throw new Error(body.message || "操作失败，请检查登录状态和输入内容");
  }
  return body;
}

export const states = {
  PENDING: "待审核",
  APPROVED: "已通过",
  REJECTED: "已驳回",
  CANCELLED: "已取消",
};
export const roles = {
  ADMIN: "管理员",
  STAFF: "工作人员",
  EXHIBITOR: "展商",
  USER: "参会者",
};
export const kinds = {
  EXHIBIT: "展商展览",
  RESULT: "成果展示",
  NEWS: "资讯",
  COMMENT: "评论",
  WALL: "签名墙",
};
export const time = (value) =>
  value ? String(value).replace("T", " ").slice(0, 16) : "—";

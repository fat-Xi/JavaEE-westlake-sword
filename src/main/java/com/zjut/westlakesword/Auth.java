package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 每次查询当前角色与权限，修改权限后无需重新登录。 */
@Component
public class Auth {

  private final Store store;

  public Auth(Store store) {
    this.store = store;
  }

  public long id(HttpSession session) {
    Object id;
    try {
      id = session.getAttribute("uid");
    } catch (IllegalStateException e) {
      id = null;
    }
    if (id == null) throw new ResponseStatusException(
      HttpStatus.UNAUTHORIZED,
      "请先登录"
    );
    return (Long) id;
  }

  public String role(HttpSession session) {
    return (String) store
      .one("SELECT role FROM users WHERE id=?", id(session))
      .get("role");
  }

  public void admin(HttpSession session) {
    if (!"ADMIN".equals(role(session))) denied("仅管理员可操作");
  }

  public void active(HttpSession session) {
    if (
      !"APPROVED".equals(
        store
          .one("SELECT status FROM users WHERE id=?", id(session))
          .get("status")
      )
    ) denied("身份资料尚未审核通过，请前往个人主页查看");
  }

  public void member(HttpSession session) {
    active(session);
    if (!"USER".equals(role(session))) denied("此功能仅供参会者使用");
  }

  // 角色职责有上限：配置权限不能把工作人员变成运营或审核人员。
  public static Set<String> allowed(String role) {
    return switch (role) {
      case "STAFF" -> Set.of("checkin");
      case "EXHIBITOR" -> Set.of("exhibit", "request");
      default -> Set.of();
    };
  }

  public void need(HttpSession session, String code) {
    active(session);
    String role = role(session);
    if (
      !"ADMIN".equals(role) &&
      (!allowed(role).contains(code) ||
        store.count(
          "SELECT COUNT(*) FROM perms WHERE role=? AND code=?",
          role,
          code
        ) == 0)
    ) {
      denied("缺少权限：" + code);
    }
  }

  public void approved(HttpSession session, long eventId) {
    member(session);
    if (
      store.count(
        "SELECT COUNT(*) FROM joins WHERE user_id=? AND event_id=? AND status='APPROVED'",
        id(session),
        eventId
      ) == 0
    ) denied("报名审核通过后才能使用参会服务");
  }

  public static void denied(String message) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
  }
}

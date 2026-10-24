package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminApi {

  private final Store store;
  private final Auth auth;
  public static final Set<String> ROLES = Set.of(
    "ADMIN",
    "STAFF",
    "EXHIBITOR",
    "USER"
  );
  public static final Set<String> CODES = Set.of(
    "event",
    "review",
    "content",
    "checkin",
    "stats",
    "exhibit",
    "request"
  );

  public AdminApi(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
  }

  record Role(String role) {}

  record Perm(List<String> codes) {}

  @GetMapping("/users")
  public List<Map<String, Object>> users(HttpSession s) {
    auth.admin(s);
    return store.db.queryForList(
      "SELECT u.id,u.name,u.role,u.status,u.reason,u.created,u.logins,p.nick,p.real_name,p.gender,p.email,p.phone FROM users u LEFT JOIN profiles p ON p.user_id=u.id ORDER BY u.id DESC"
    );
  }

  @PutMapping("/users/{id}/role")
  @Transactional
  public void role(@PathVariable long id, @RequestBody Role r, HttpSession s) {
    auth.admin(s);
    Api.check(ROLES.contains(r.role() == null ? "" : r.role()), "角色不存在");
    Api.check(id != auth.id(s), "不能修改自己的管理员角色");
    store.one("SELECT id FROM users WHERE id=? FOR UPDATE", id);
    store.db.update("UPDATE users SET role=? WHERE id=?", r.role(), id);
    store.log(auth.id(s), "修改角色", id + ":" + r.role());
  }

  @PostMapping("/users/{id}/review")
  @Transactional
  public void reviewUser(
    @PathVariable long id,
    @RequestBody Api.Review r,
    HttpSession s
  ) {
    auth.admin(s);
    var u = store.one("SELECT * FROM users WHERE id=? FOR UPDATE", id);
    Api.check("PENDING".equals(u.get("status")), "只处理待审核身份");
    if (!r.pass()) Store.word(r.reason(), 200, "驳回理由");
    store.db.update(
      "UPDATE users SET status=?,reason=? WHERE id=?",
      r.pass() ? "APPROVED" : "REJECTED",
      r.pass() ? "" : r.reason(),
      id
    );
    store.note(id, r.pass() ? "身份审核已通过" : "身份审核驳回：" + r.reason());
    store.log(auth.id(s), "身份审核", id + ":" + r.pass());
  }

  @GetMapping("/perms")
  public List<Map<String, Object>> perms(HttpSession s) {
    auth.admin(s);
    return store.db.queryForList("SELECT * FROM perms ORDER BY role,code");
  }

  @PutMapping("/perms/{role}")
  @Transactional
  public void perms(
    @PathVariable String role,
    @RequestBody Perm p,
    HttpSession s
  ) {
    auth.admin(s);
    Api.check(
      ROLES.contains(role) &&
        !"ADMIN".equals(role) &&
        p.codes() != null &&
        Auth.allowed(role).containsAll(p.codes()),
      "角色或权限错误"
    );
    store.db.update("DELETE FROM perms WHERE role=?", role);
    for (String code : new HashSet<>(p.codes()))
      store.db.update("INSERT INTO perms(role,code) VALUES(?,?)", role, code);
    store.log(auth.id(s), "权限配置", role);
  }

  @GetMapping("/logs")
  public List<Map<String, Object>> logs(HttpSession s) {
    auth.admin(s);
    return store.db.queryForList(
      "SELECT l.*,u.name FROM logs l JOIN users u ON u.id=l.user_id ORDER BY l.id DESC LIMIT 200"
    );
  }

  @GetMapping("/stats")
  public Map<String, Object> stats(
    @RequestParam(required = false) Long eventId,
    HttpSession s
  ) {
    auth.need(s, "stats");
    // 注册和登录是平台指标，其余指标随当前活动过滤。
    String filter = eventId == null ? "" : " AND event_id=?";
    Object[] args =
      eventId == null ? new Object[] {} : new Object[] { eventId };
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("users", store.count("SELECT COUNT(*) FROM users"));
    data.put(
      "logins",
      store.count("SELECT COALESCE(SUM(logins),0) FROM users")
    );
    data.put(
      "joins",
      store.count(
        "SELECT COUNT(*) FROM joins WHERE status<>'CANCELLED'" + filter,
        args
      )
    );
    data.put(
      "approved",
      store.count(
        "SELECT COUNT(*) FROM joins WHERE status='APPROVED'" + filter,
        args
      )
    );
    data.put(
      "pending",
      store.count(
        "SELECT COUNT(*) FROM joins WHERE status='PENDING'" + filter,
        args
      )
    );
    data.put(
      "checkins",
      store.count(
        "SELECT COUNT(*) FROM (SELECT m.user_id,t.event_id FROM marks m JOIN attendance a ON a.id=m.session_id JOIN talks t ON t.id=a.talk_id UNION SELECT j.user_id,j.event_id FROM tickets k JOIN joins j ON j.id=k.join_id WHERE k.checked IS NOT NULL) checked_users WHERE 1=1" +
          filter,
        args
      )
    );
    data.put(
      "votes",
      store.count(
        "SELECT COUNT(*) FROM ballots b JOIN polls p ON b.poll_id=p.id WHERE 1=1" +
          filter,
        args
      )
    );
    data.put(
      "channels",
      store.db.queryForList(
        "SELECT COALESCE(i.channel,'direct') name,COUNT(*) value FROM joins j LEFT JOIN join_info i ON i.join_id=j.id WHERE 1=1" +
          filter +
          " GROUP BY COALESCE(i.channel,'direct')",
        args
      )
    );
    data.put(
      "trend",
      store.db.queryForList(
        "SELECT DATE_FORMAT(created,'%m-%d') name,COUNT(*) value FROM joins WHERE 1=1" +
          filter +
          " GROUP BY DATE(created),DATE_FORMAT(created,'%m-%d') ORDER BY DATE(created) DESC LIMIT 14",
        args
      )
    );
    return data;
  }
}

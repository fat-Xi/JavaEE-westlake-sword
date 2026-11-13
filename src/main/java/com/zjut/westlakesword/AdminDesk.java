package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 真实管理员数据聚合；统计只来自业务表。 */
@RestController
@RequestMapping("/api")
public class AdminDesk {

  private final Store store;
  private final Auth auth;

  public AdminDesk(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
  }

  @GetMapping("/admin/desk")
  public Map<String, Object> desk(HttpSession s) {
    auth.admin(s);
    var d = new HashMap<String, Object>();
    d.put(
      "users",
      store.db.queryForList(
        "SELECT u.id,u.name,u.role,u.status,u.reason,p.nick,p.real_name,p.email,p.phone FROM users u LEFT JOIN profiles p ON p.user_id=u.id WHERE u.status<>'DELETED' ORDER BY u.id DESC"
      )
    );
    d.put(
      "meetings",
      store.db.queryForList(
        "SELECT m.*,u.name owner FROM exhibit_meetings m JOIN users u ON u.id=m.user_id WHERE m.status<>'WITHDRAWN' ORDER BY m.id DESC"
      )
    );
    d.put(
      "events",
      store.db.queryForList(
        "SELECT e.*,COALESCE(i.views,0) views FROM events e LEFT JOIN event_info i ON i.event_id=e.id ORDER BY e.starts DESC"
      )
    );
    d.put(
      "talks",
      store.db.queryForList("SELECT * FROM talks ORDER BY starts")
    );
    d.put(
      "interactions",
      store.db.queryForList(
        "SELECT r.*,m.event_id,u.name author FROM exhibit_reviews r JOIN exhibit_meetings m ON m.id=r.meeting_id JOIN users u ON u.id=m.user_id WHERE r.status<>'WITHDRAWN' ORDER BY r.id DESC"
      )
    );
    d.put(
      "questions",
      store.db.queryForList(
        "SELECT q.*,COALESCE(NULLIF(p.nick,''),u.name) author FROM questions q JOIN users u ON u.id=q.user_id LEFT JOIN profiles p ON p.user_id=u.id ORDER BY q.id DESC"
      )
    );
    d.put(
      "posts",
      store.db.queryForList(
        "SELECT t.*,COALESCE(NULLIF(p.nick,''),u.name) author FROM posts t JOIN users u ON u.id=t.user_id LEFT JOIN profiles p ON p.user_id=u.id WHERE t.kind IN ('COMMENT','WALL') ORDER BY t.id DESC"
      )
    );
    d.put(
      "sessions",
      store.db.queryForList(
        "SELECT a.*,(SELECT COUNT(*) FROM marks m WHERE m.session_id=a.id) count FROM attendance a ORDER BY a.id DESC"
      )
    );
    d.put(
      "logs",
      store.db.queryForList(
        "SELECT l.*,u.name actor FROM logs l LEFT JOIN users u ON u.id=l.user_id ORDER BY l.id DESC"
      )
    );
    d.put(
      "siteViews",
      store.count("SELECT COALESCE(SUM(views),0) FROM site_views")
    );
    d.put(
      "traffic",
      store.db.queryForList(
        "SELECT DATE_FORMAT(day,'%Y-%m-%d') day,views FROM site_views WHERE day>=DATE_SUB(CURDATE(),INTERVAL 6 DAY) ORDER BY day"
      )
    );
    d.put(
      "trackingSince",
      store.db.queryForObject(
        "SELECT DATE_FORMAT(MIN(day),'%Y-%m-%d') FROM site_views",
        String.class
      )
    );
    d.put(
      "joins",
      store.db.queryForList(
        "SELECT j.*,u.name FROM joins j JOIN users u ON u.id=j.user_id ORDER BY j.id DESC"
      )
    );
    return d;
  }

  record Delete(String name) {}

  @PostMapping("/admin/users/{id}/delete")
  @Transactional
  public void delete(
    @PathVariable long id,
    @RequestBody Delete input,
    HttpSession s
  ) {
    auth.admin(s);
    Api.check(id != auth.id(s), "不能删除当前管理员");
    var u = store.one("SELECT * FROM users WHERE id=? FOR UPDATE", id);
    Api.check(!"ADMIN".equals(u.get("role")), "管理员账户不可在此删除");
    Api.check(Objects.equals(input.name(), u.get("name")), "确认账号不一致");
    Api.check(!"DELETED".equals(u.get("status")), "账户已删除");
    store.db.update(
      "UPDATE users SET status='DELETED',reason='账户已删除' WHERE id=?",
      id
    );
    store.log(auth.id(s), "删除账户（保留业务记录）", u.get("name"));
  }

  record Visit(String page) {}

  @PostMapping("/visits")
  public void visit(@RequestBody Visit v, HttpSession s) {
    auth.id(s);
    Store.word(v.page(), 80, "页面");
    store.db.update(
      "INSERT INTO site_views(day,views) VALUES(CURDATE(),1) ON DUPLICATE KEY UPDATE views=views+1"
    );
  }
}

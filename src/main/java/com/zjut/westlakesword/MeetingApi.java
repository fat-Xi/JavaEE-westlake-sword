package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 管理员与工作人员的会议信息入口，与参会报名页面分开。包含已关闭活动。 */
@RestController
@RequestMapping("/api/meetings")
public class MeetingApi {

  private final Store store;
  private final Auth auth;

  public MeetingApi(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
  }

  void allowed(HttpSession s) {
    if (!Set.of("ADMIN", "STAFF").contains(auth.role(s))) Auth.denied(
      "仅管理员和工作人员可查看会议执行信息"
    );
  }

  @GetMapping
  public List<Map<String, Object>> list(HttpSession s) {
    allowed(s);
    return store.db.queryForList("SELECT * FROM events ORDER BY starts DESC");
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable long id, HttpSession s) {
    allowed(s);
    var data = store.one(
      "SELECT e.*,i.live_url,i.replay_url FROM events e LEFT JOIN event_info i ON i.event_id=e.id WHERE e.id=?",
      id
    );
    data.put(
      "talks",
      store.db.queryForList(
        "SELECT * FROM talks WHERE event_id=? ORDER BY starts",
        id
      )
    );
    if ("ADMIN".equals(auth.role(s))) {
      data.put(
        "files",
        store.db.queryForList(
          "SELECT id,name,size FROM files WHERE event_id=?",
          id
        )
      );
      data.put(
        "posts",
        store.db.queryForList(
          "SELECT p.*,u.name FROM posts p JOIN users u ON u.id=p.user_id WHERE event_id=?",
          id
        )
      );
      data.put(
        "request",
        store.db.queryForList(
          "SELECT r.*,u.name FROM requests r JOIN users u ON u.id=r.user_id WHERE event_id=?",
          id
        )
      );
    }
    return data;
  }
}

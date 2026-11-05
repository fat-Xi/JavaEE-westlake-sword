package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 展商、成果、资讯与基础互动共用内容模型，避免多套重复 CRUD。 */
@RestController
@RequestMapping("/api")
public class ContentApi {

  private final Store store;
  private final Auth auth;
  private static final Set<String> KINDS = Set.of(
    "EXHIBIT",
    "RESULT",
    "NEWS",
    "COMMENT",
    "WALL"
  );

  public ContentApi(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
  }

  record Post(
    long eventId,
    String kind,
    String title,
    String body,
    String url,
    String image,
    String product
  ) {}

  record Review(boolean pass, String reason) {}

  @GetMapping("/events/{id}/posts")
  public List<Map<String, Object>> posts(@PathVariable long id, HttpSession s) {
    auth.approved(s, id);
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN'", id);
    return store.db.queryForList(
      "SELECT p.*,COALESCE(NULLIF(pr.nick,''),u.name) name,COALESCE(NULLIF(pr.nick,''),u.name) nick,pr.avatar,(SELECT COUNT(*) FROM likes l WHERE l.post_id=p.id) likes FROM posts p JOIN users u ON u.id=p.user_id LEFT JOIN profiles pr ON pr.user_id=u.id WHERE p.event_id=? AND p.status='APPROVED' ORDER BY p.created DESC,p.id DESC",
      id
    );
  }

  @GetMapping("/posts/mine")
  public List<Map<String, Object>> mine(HttpSession s) {
    return store.db.queryForList(
      // 只取当前登录者的记录，包含全部审核状态；作者信息来自个人资料。
      "SELECT p.*,e.title event_title,e.status event_status,COALESCE(NULLIF(pr.nick,''),u.name) nick,pr.avatar FROM posts p JOIN events e ON e.id=p.event_id JOIN users u ON u.id=p.user_id LEFT JOIN profiles pr ON pr.user_id=u.id WHERE p.user_id=? ORDER BY p.created DESC,p.id DESC",
      auth.id(s)
    );
  }

  private void valid(Post p, HttpSession s) {
    Api.check(KINDS.contains(p.kind() == null ? "" : p.kind()), "内容类型错误");
    store.one(
      "SELECT id FROM events WHERE id=? AND status='OPEN'",
      p.eventId()
    );
    Store.word(p.title(), 120, "标题");
    Store.word(p.body(), 10000, "正文");
    Store.url(p.url());
    if (p.image() != null && p.image().startsWith("data:")) Store.image(
      p.image()
    );
    else Store.url(p.image());
    Api.check(
      p.product() != null && p.product().length() <= 200,
      "产品说明最多200字"
    );
    switch (p.kind()) {
      case "EXHIBIT", "NEWS", "RESULT" -> auth.need(s, "exhibit");
      default -> auth.approved(s, p.eventId());
    }
  }

  @PostMapping("/posts")
  @Transactional
  public void add(@RequestBody Post p, HttpSession s) {
    if (
      Set.of("NEWS", "RESULT", "EXHIBIT").contains(
        p.kind() == null ? "" : p.kind()
      )
    ) {
      Api.check(
        "EXHIBITOR".equals(auth.role(s)),
        "会议资讯、成果与展商资料请由展商投稿"
      );
    }
    if (p.image() != null && !p.image().isBlank()) Store.image(p.image());
    valid(p, s);
    store.db.update(
      "INSERT INTO posts(event_id,user_id,kind,title,body,url,image,product) VALUES(?,?,?,?,?,?,?,?)",
      p.eventId(),
      auth.id(s),
      p.kind(),
      p.title(),
      p.body(),
      Store.url(p.url()),
      p.image() == null ? "" : p.image(),
      p.product()
    );
    store.log(auth.id(s), "提交内容", p.title());
  }

  @PutMapping("/posts/{id}")
  @Transactional
  public void edit(@PathVariable long id, @RequestBody Post p, HttpSession s) {
    var old = store.one("SELECT * FROM posts WHERE id=? FOR UPDATE", id);
    boolean adminEdit =
      "ADMIN".equals(auth.role(s)) &&
      Set.of("NEWS", "RESULT", "EXHIBIT").contains(old.get("kind"));
    if (!adminEdit && Store.num(old, "user_id") != auth.id(s)) Auth.denied(
      "只能编辑自己的内容"
    );
    Api.check(
      Store.num(old, "event_id") == p.eventId() &&
        old.get("kind").equals(p.kind()),
      "不能变更活动或内容类型"
    );
    if (!Objects.equals(old.get("image"), p.image())) Store.image(p.image());
    valid(p, s);
    // 修改后重新待审，不能利用编辑绕过内容审核。
    store.db.update(
      "UPDATE posts SET title=?,body=?,url=?,image=?,product=?,status='PENDING',reason='' WHERE id=?",
      p.title(),
      p.body(),
      Store.url(p.url()),
      p.image() == null ? "" : p.image(),
      p.product(),
      id
    );
  }

  @GetMapping("/review/posts")
  public List<Map<String, Object>> pending(HttpSession s) {
    auth.need(s, "review");
    return store.db.queryForList(
      "SELECT p.*,u.name FROM posts p JOIN users u ON u.id=p.user_id ORDER BY p.id DESC"
    );
  }

  @PostMapping("/review/posts/{id}")
  @Transactional
  public void review(
    @PathVariable long id,
    @RequestBody Review r,
    HttpSession s
  ) {
    auth.need(s, "review");
    var row = store.one("SELECT * FROM posts WHERE id=? FOR UPDATE", id);
    if (!r.pass()) Store.word(r.reason(), 200, "原因");
    store.db.update(
      "UPDATE posts SET status=?,reason=? WHERE id=?",
      r.pass() ? "APPROVED" : "REJECTED",
      r.pass() ? "" : r.reason(),
      id
    );
    store.note(
      Store.num(row, "user_id"),
      "内容「" +
        row.get("title") +
        "」" +
        (r.pass() ? "审核通过" : "被驳回：" + r.reason())
    );
    store.log(auth.id(s), "内容审核", id);
  }

  @GetMapping("/likes")
  public List<Long> likes(HttpSession s) {
    return store.db.queryForList(
      "SELECT post_id FROM likes WHERE user_id=?",
      Long.class,
      auth.id(s)
    );
  }

  @PostMapping("/posts/{id}/like")
  public void like(@PathVariable long id, HttpSession s) {
    var p = store.one(
      "SELECT * FROM posts WHERE id=? AND status='APPROVED'",
      id
    );
    auth.approved(s, Store.num(p, "event_id"));
    store.db.update(
      "INSERT IGNORE INTO likes(user_id,post_id) VALUES(?,?)",
      auth.id(s),
      id
    );
  }

  @DeleteMapping("/posts/{id}/like")
  public void unlike(@PathVariable long id, HttpSession s) {
    store.db.update(
      "DELETE FROM likes WHERE user_id=? AND post_id=?",
      auth.id(s),
      id
    );
  }
}

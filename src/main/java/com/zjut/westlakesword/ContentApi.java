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

  record Question(long eventId, String body, Long talkId) {}

  record Answer(String answer) {}

  @GetMapping("/events/{id}/posts")
  public List<Map<String, Object>> posts(@PathVariable long id, HttpSession s) {
    auth.approved(s, id);
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN'", id);
    var rows = store.db.queryForList(
      "SELECT p.*,COALESCE(NULLIF(pr.nick,''),u.name) name,COALESCE(NULLIF(pr.nick,''),u.name) nick,pr.avatar,(SELECT COUNT(*) FROM likes l WHERE l.post_id=p.id) likes FROM posts p JOIN users u ON u.id=p.user_id LEFT JOIN profiles pr ON pr.user_id=u.id WHERE p.event_id=? AND p.status='APPROVED' ORDER BY p.created DESC,p.id DESC",
      id
    );
    // 匿名签名对普通参会者隐藏身份，管理员审核记录仍保留真实作者。
    for (var row : rows) {
      if (Boolean.TRUE.equals(row.get("anonymous"))) {
        row.put("name", "匿名参会者");
        row.put("nick", "匿名参会者");
        row.put("avatar", "");
        row.remove("user_id");
      }
    }
    return rows;
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

  record Community(
    long eventId,
    String kind,
    String body,
    String tags,
    boolean anonymous,
    String font
  ) {}

  /** 参会者简洁编辑器：标题由服务端生成，内容仍进入原有审核队列。 */
  @PostMapping("/community")
  @Transactional
  public void community(@RequestBody Community p, HttpSession s) {
    auth.approved(s, p.eventId());
    store.one(
      "SELECT id FROM events WHERE id=? AND status='OPEN'",
      p.eventId()
    );
    Api.check(
      Set.of("COMMENT", "WALL").contains(p.kind() == null ? "" : p.kind()),
      "类型错误"
    );
    String body = Store.word(
      p.body(),
      "WALL".equals(p.kind()) ? 120 : 2000,
      "内容"
    );
    String tags = p.tags() == null ? "" : p.tags().strip();
    Api.check(tags.length() <= 200, "标签最多200字");
    String font = p.font() == null ? "sans" : p.font();
    Api.check(Set.of("sans", "serif", "hand").contains(font), "字体错误");
    store.db.update(
      "INSERT INTO posts(event_id,user_id,kind,title,body,product,anonymous,font) VALUES(?,?,?,?,?,?,?,?)",
      p.eventId(),
      auth.id(s),
      p.kind(),
      "WALL".equals(p.kind()) ? "参会签名" : "会议评论",
      body,
      tags,
      "WALL".equals(p.kind()) && p.anonymous(),
      font
    );
    store.log(auth.id(s), "提交参会互动", p.kind());
  }

  /** 撤销待审核互动：锁定记录，防止审核通过后被误撤销。 */
  @PostMapping("/community/{id}/withdraw")
  @Transactional
  public void withdrawCommunity(@PathVariable long id, HttpSession s) {
    auth.member(s);
    var row = store.one("SELECT * FROM posts WHERE id=? FOR UPDATE", id);
    Api.check("PENDING".equals(row.get("status")), "该申请已处理");
    if (Store.num(row, "user_id") != auth.id(s)) Auth.denied(
      "只能撤销自己的记录"
    );
    Api.check(
      Set.of("COMMENT", "WALL").contains(row.get("kind")),
      "仅支持撤销评论与签名"
    );
    Api.check(
      "PENDING".equals(row.get("status")),
      "该内容已处理，请刷新后查看审核结果"
    );
    store.db.update("DELETE FROM likes WHERE post_id=?", id);
    store.db.update("DELETE FROM posts WHERE id=?", id);
    store.log(auth.id(s), "撤销互动审核", id);
  }

  @DeleteMapping("/community/{id}")
  @Transactional
  public void removeCommunity(@PathVariable long id, HttpSession s) {
    auth.member(s);
    var row = store.one("SELECT * FROM posts WHERE id=? FOR UPDATE", id);
    Api.check("PENDING".equals(row.get("status")), "该申请已处理");
    if (Store.num(row, "user_id") != auth.id(s)) Auth.denied(
      "只能删除自己的记录"
    );
    Api.check(
      Set.of("COMMENT", "WALL").contains(row.get("kind")),
      "仅支持删除评论与签名"
    );
    store.db.update("DELETE FROM likes WHERE post_id=?", id);
    store.db.update("DELETE FROM posts WHERE id=?", id);
    store.log(auth.id(s), "删除参会互动", id);
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
    Api.check("PENDING".equals(row.get("status")), "该申请已处理");
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

  @GetMapping("/events/{id}/questions")
  public List<Map<String, Object>> questions(
    @PathVariable long id,
    HttpSession s
  ) {
    auth.approved(s, id);
    return store.db.queryForList(
      "SELECT q.*,COALESCE(NULLIF(pr.nick,''),u.name) name,pr.avatar,(SELECT COUNT(*) FROM votes v WHERE v.question_id=q.id) votes FROM questions q JOIN users u ON u.id=q.user_id LEFT JOIN profiles pr ON pr.user_id=u.id WHERE q.event_id=? AND (q.status='APPROVED' OR q.user_id=?) ORDER BY votes DESC,q.id DESC",
      id,
      auth.id(s)
    );
  }

  @PostMapping("/questions")
  public void question(@RequestBody Question q, HttpSession s) {
    auth.approved(s, q.eventId());
    Store.word(q.body(), 500, "问题");
    if (q.talkId() != null) store.one(
      "SELECT id FROM talks WHERE id=? AND event_id=?",
      q.talkId(),
      q.eventId()
    );
    store.db.update(
      "INSERT INTO questions(event_id,user_id,body,talk_id) VALUES(?,?,?,?)",
      q.eventId(),
      auth.id(s),
      q.body(),
      q.talkId()
    );
  }

  @GetMapping("/votes")
  public List<Long> votes(HttpSession s) {
    return store.db.queryForList(
      "SELECT question_id FROM votes WHERE user_id=?",
      Long.class,
      auth.id(s)
    );
  }

  @PostMapping("/questions/{id}/vote")
  public void vote(@PathVariable long id, HttpSession s) {
    Api.check(false, "投票已迁移至管理员发布的会议投票模块");
  }

  @DeleteMapping("/questions/{id}/vote")
  public void unvote(@PathVariable long id, HttpSession s) {
    store.db.update(
      "DELETE FROM votes WHERE user_id=? AND question_id=?",
      auth.id(s),
      id
    );
  }

  @GetMapping("/review/questions")
  public List<Map<String, Object>> reviewQuestions(HttpSession s) {
    auth.need(s, "review");
    return store.db.queryForList("SELECT * FROM questions ORDER BY id DESC");
  }

  @PostMapping("/review/questions/{id}")
  @Transactional
  public void reviewQuestion(
    @PathVariable long id,
    @RequestBody Review r,
    HttpSession s
  ) {
    auth.need(s, "review");
    var q = store.one("SELECT * FROM questions WHERE id=? FOR UPDATE", id);
    Api.check("PENDING".equals(q.get("status")), "该申请已处理");
    if (!r.pass()) Store.word(r.reason(), 200, "原因");
    store.db.update(
      "UPDATE questions SET status=?,reason=? WHERE id=?",
      r.pass() ? "APPROVED" : "REJECTED",
      r.pass() ? "" : r.reason(),
      id
    );
    store.note(
      Store.num(q, "user_id"),
      "提问「" +
        q.get("body") +
        "」" +
        (r.pass() ? "审核通过" : "被驳回：" + r.reason())
    );
    store.log(auth.id(s), "问题审核", id);
  }

  @PutMapping("/review/questions/{id}/answer")
  public void answer(
    @PathVariable long id,
    @RequestBody Answer a,
    HttpSession s
  ) {
    auth.need(s, "review");
    Store.word(a.answer(), 1000, "答复");
    store.one("SELECT id FROM questions WHERE id=? AND status='APPROVED'", id);
    store.db.update("UPDATE questions SET answer=? WHERE id=?", a.answer(), id);
    store.log(auth.id(s), "答复问题", id);
  }
}

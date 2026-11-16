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

  record Reason(String reason) {}

  record ReportDecision(Boolean remove, String reason) {}

  @GetMapping("/events/{id}/posts")
  public List<Map<String, Object>> posts(@PathVariable long id, HttpSession s) {
    auth.approved(s, id);
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN'", id);
    long viewer = auth.id(s);
    var rows = store.db.queryForList(
      """
      SELECT p.*,COALESCE(NULLIF(pr.nick,''),u.name) name,
        COALESCE(NULLIF(pr.nick,''),u.name) nick,pr.avatar,
        (SELECT COUNT(*) FROM likes l WHERE l.post_id=p.id) likes,
        (SELECT COUNT(*) FROM comment_dislikes d WHERE d.post_id=p.id) dislikes,
        EXISTS(SELECT 1 FROM comment_reports r WHERE r.post_id=p.id AND r.reporter_id=?) reported,
        CASE WHEN p.kind<>'COMMENT' THEN NULL WHEN vl.post_id IS NOT NULL THEN 'LIKE'
          WHEN vd.post_id IS NOT NULL THEN 'DISLIKE' END reaction
      FROM posts p JOIN users u ON u.id=p.user_id LEFT JOIN profiles pr ON pr.user_id=u.id
      LEFT JOIN likes vl ON vl.post_id=p.id AND vl.user_id=?
      LEFT JOIN comment_dislikes vd ON vd.post_id=p.id AND vd.user_id=?
      WHERE p.event_id=? AND p.status='APPROVED' AND p.kind<>'QUESTION_REPLY'
      ORDER BY p.created DESC,p.id DESC
      """,
      viewer,
      viewer,
      viewer,
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
      "SELECT p.*,e.title event_title,e.status event_status,COALESCE(NULLIF(pr.nick,''),u.name) nick,pr.avatar FROM posts p JOIN events e ON e.id=p.event_id JOIN users u ON u.id=p.user_id LEFT JOIN profiles pr ON pr.user_id=u.id WHERE p.user_id=? AND p.kind<>'QUESTION_REPLY' ORDER BY p.created DESC,p.id DESC",
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
      "INSERT INTO posts(event_id,user_id,kind,title,body,url,image,product,status) VALUES(?,?,?,?,?,?,?,?,?)",
      p.eventId(),
      auth.id(s),
      p.kind(),
      p.title(),
      p.body(),
      Store.url(p.url()),
      p.image() == null ? "" : p.image(),
      p.product(),
      publishStatus(p.kind())
    );
    store.log(auth.id(s), "提交内容", p.title());
  }

  record Community(
    long eventId,
    String kind,
    String body,
    String tags,
    Boolean anonymous,
    String font,
    Long replyTo
  ) {}

  private static String publishStatus(String kind) {
    return "COMMENT".equals(kind) ? "APPROVED" : "PENDING";
  }

  /** 参会者简洁编辑器；评论直接发布，签名进入审核队列。 */
  @PostMapping("/community")
  @Transactional
  public void community(@RequestBody Community p, HttpSession s) {
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
    Api.check(
      Set.of("sans", "serif", "hand", "bold", "round", "brush").contains(font),
      "字体错误"
    );
    if (p.replyTo() == null) {
      auth.approved(s, p.eventId());
      store.one(
        "SELECT id FROM events WHERE id=? AND status='OPEN'",
        p.eventId()
      );
    } else {
      Api.check("COMMENT".equals(p.kind()), "只能回复评论");
      lockComment(p.replyTo(), p.eventId(), s);
    }
    store.db.update(
      "INSERT INTO posts(event_id,user_id,kind,title,body,product,anonymous,font,reply_to_id,status) VALUES(?,?,?,?,?,?,?,?,?,?)",
      p.eventId(),
      auth.id(s),
      p.kind(),
      "WALL".equals(p.kind())
        ? "参会签名"
        : p.replyTo() == null
          ? "会议评论"
          : "评论回复",
      body,
      tags,
      "WALL".equals(p.kind()) && Boolean.TRUE.equals(p.anonymous()),
      font,
      p.replyTo(),
      publishStatus(p.kind())
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
    var reference = store.one("SELECT event_id,kind FROM posts WHERE id=?", id);
    boolean comment = "COMMENT".equals(reference.get("kind"));
    long event = Store.num(reference, "event_id");
    if (comment) lockCommentEvent(event);
    var row = store.one("SELECT * FROM posts WHERE id=? FOR UPDATE", id);
    if (Store.num(row, "user_id") != auth.id(s)) Auth.denied(
      "只能删除自己的记录"
    );
    Api.check(
      Set.of("COMMENT", "WALL").contains(row.get("kind")),
      "仅支持删除评论与签名"
    );
    String status = (String) row.get("status");
    Api.check(
      "PENDING".equals(status) ||
        "APPROVED".equals(status) ||
        (!comment && "REJECTED".equals(status)),
      "该记录当前无法删除"
    );
    if (comment) {
      removeCommentThread(event, id, "作者删除了原评论", false);
    } else {
      store.db.update("DELETE FROM likes WHERE post_id=?", id);
      store.db.update("DELETE FROM posts WHERE id=?", id);
    }
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
    Api.check(
      !"REMOVED".equals(old.get("status")),
      "管理员已删除的内容不能重新发布"
    );
    store.db.update(
      "UPDATE posts SET title=?,body=?,url=?,image=?,product=?,status=?,reason='' WHERE id=?",
      p.title(),
      p.body(),
      Store.url(p.url()),
      p.image() == null ? "" : p.image(),
      p.product(),
      publishStatus(p.kind()),
      id
    );
  }

  @GetMapping("/review/posts")
  public List<Map<String, Object>> pending(HttpSession s) {
    auth.need(s, "review");
    return store.db.queryForList(
      "SELECT p.*,u.name FROM posts p JOIN users u ON u.id=p.user_id WHERE p.kind<>'QUESTION_REPLY' ORDER BY p.id DESC"
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

  @DeleteMapping("/review/posts/{id}")
  @Transactional
  public void removePublishedComment(
    @PathVariable long id,
    @RequestBody Reason input,
    HttpSession s
  ) {
    auth.admin(s);
    String reason = Store.word(input.reason(), 200, "删除理由");
    long event = commentEvent(id);
    lockCommentEvent(event);
    var row = store.one("SELECT status FROM posts WHERE id=? FOR UPDATE", id);
    if ("REMOVED".equals(row.get("status"))) return;
    Api.check("APPROVED".equals(row.get("status")), "只能删除已公开的评论");
    removeCommentThread(event, id, reason, true);
    store.log(auth.id(s), "管理员删除评论", id);
  }

  @PostMapping("/community/{id}/reports")
  @Transactional
  public void reportComment(
    @PathVariable long id,
    @RequestBody Reason input,
    HttpSession s
  ) {
    auth.member(s);
    long event = commentEvent(id);
    var row = lockComment(id, event, s);
    long reporter = auth.id(s);
    if (Store.num(row, "user_id") == reporter) Auth.denied(
      "不能举报自己的评论"
    );
    String reason = Store.word(input.reason(), 200, "举报理由");
    if (
      store.count(
        "SELECT COUNT(*) FROM comment_reports WHERE post_id=? AND reporter_id=?",
        id,
        reporter
      ) > 0
    ) return;
    store.db.update(
      "INSERT INTO comment_reports(post_id,reporter_id,reason) VALUES(?,?,?)",
      id,
      reporter,
      reason
    );
    store.log(reporter, "举报评论", id);
  }

  @PutMapping("/review/comment-reports/{id}")
  @Transactional
  public void resolveCommentReport(
    @PathVariable long id,
    @RequestBody ReportDecision decision,
    HttpSession s
  ) {
    auth.admin(s);
    Api.check(decision.remove() != null, "请选择保留或删除评论");
    var reference = store.one(
      "SELECT post_id FROM comment_reports WHERE id=?",
      id
    );
    long post = Store.num(reference, "post_id");
    long event = commentEvent(post);
    lockCommentEvent(event);
    var report = store.one(
      "SELECT status FROM comment_reports WHERE id=? FOR UPDATE",
      id
    );
    Api.check(
      "PENDING".equals(report.get("status")),
      "该举报已处理，请刷新列表"
    );
    if (decision.remove()) {
      String reason = Store.word(decision.reason(), 200, "删除理由");
      removeCommentThread(event, post, reason, true);
      store.log(auth.id(s), "处理评论举报并删除", id);
    } else {
      store.db.update(
        "UPDATE comment_reports SET status='KEPT',reviewed_at=NOW() WHERE id=?",
        id
      );
      store.log(auth.id(s), "处理评论举报并保留", id);
    }
  }

  private long commentEvent(long id) {
    return Store.num(
      store.one("SELECT event_id FROM posts WHERE id=? AND kind='COMMENT'", id),
      "event_id"
    );
  }

  private void lockCommentEvent(long event) {
    store.one("SELECT id FROM events WHERE id=? FOR UPDATE", event);
  }

  // 持有会议行锁，期间不会有新的回复或举报加入待删除的评论串。
  private void removeCommentThread(
    long event,
    long root,
    String reason,
    boolean moderator
  ) {
    var rows = store.db.queryForList(
      "SELECT id,user_id,reply_to_id,status,body FROM posts WHERE event_id=? AND kind='COMMENT' FOR UPDATE",
      event
    );
    Map<Long, List<Map<String, Object>>> byParent = new HashMap<>();
    Deque<Map<String, Object>> pending = new ArrayDeque<>();
    for (var row : rows) {
      if (Store.num(row, "id") == root) pending.add(row);
      if (row.get("reply_to_id") != null) byParent
        .computeIfAbsent(Store.num(row, "reply_to_id"), ignored ->
          new ArrayList<>()
        )
        .add(row);
    }
    while (!pending.isEmpty()) {
      var row = pending.removeFirst();
      long id = Store.num(row, "id");
      pending.addAll(byParent.getOrDefault(id, List.of()));
      if ("REMOVED".equals(row.get("status"))) continue;
      store.db.update(
        "UPDATE posts SET status='REMOVED',reason=? WHERE id=?",
        reason,
        id
      );
      store.db.update(
        "UPDATE comment_reports SET status='REMOVED',resolution=?,reviewed_at=NOW() WHERE post_id=? AND status='PENDING'",
        reason,
        id
      );
      if (!moderator && id == root) continue;
      String body = (String) row.get("body");
      String excerpt = body.substring(
        0,
        body.offsetByCodePoints(
          0,
          Math.min(40, body.codePointCount(0, body.length()))
        )
      );
      store.note(
        Store.num(row, "user_id"),
        "你的" +
          (id == root ? "评论" : "回复") +
          "「" +
          excerpt +
          "」" +
          (id == root ? "已被管理员删除" : "因上级评论被删除而一并移除") +
          "。原因：" +
          reason
      );
    }
  }

  record Reaction(String value) {}

  @PutMapping("/community/{id}/reaction")
  @Transactional
  public void react(
    @PathVariable long id,
    @RequestBody Reaction reaction,
    HttpSession s
  ) {
    String value = Objects.toString(reaction.value(), "");
    Api.check(
      Set.of("LIKE", "DISLIKE", "NONE").contains(value),
      "请选择点赞、点踩或撤销"
    );
    long event = Store.num(
      store.one(
        "SELECT event_id FROM posts WHERE id=? AND kind='COMMENT' AND status='APPROVED'",
        id
      ),
      "event_id"
    );
    lockComment(id, event, s);
    writeCommentReaction(id, auth.id(s), value);
  }

  private Map<String, Object> lockComment(long id, long event, HttpSession s) {
    auth.member(s);
    // 先锁会议，与取消报名、关闭会议保持相同顺序，再检查评论是否仍可见。
    store.one(
      "SELECT id FROM events WHERE id=? AND status='OPEN' FOR UPDATE",
      event
    );
    auth.approved(s, event);
    return store.one(
      "SELECT id,user_id FROM posts WHERE id=? AND event_id=? AND kind='COMMENT' AND status='APPROVED' FOR UPDATE",
      id,
      event
    );
  }

  private void writeCommentReaction(long id, long user, String value) {
    if ("LIKE".equals(value)) store.db.update(
      "INSERT IGNORE INTO likes(user_id,post_id) VALUES(?,?)",
      user,
      id
    );
    else store.db.update(
      "DELETE FROM likes WHERE user_id=? AND post_id=?",
      user,
      id
    );
    if ("DISLIKE".equals(value)) store.db.update(
      "INSERT IGNORE INTO comment_dislikes(user_id,post_id) VALUES(?,?)",
      user,
      id
    );
    else store.db.update(
      "DELETE FROM comment_dislikes WHERE user_id=? AND post_id=?",
      user,
      id
    );
  }

  @PostMapping("/posts/{id}/like")
  @Transactional
  public void like(@PathVariable long id, HttpSession s) {
    var p = store.one(
      "SELECT * FROM posts WHERE id=? AND status='APPROVED' AND kind<>'QUESTION_REPLY'",
      id
    );
    long event = Store.num(p, "event_id");
    if ("COMMENT".equals(p.get("kind"))) {
      lockComment(id, event, s);
      writeCommentReaction(id, auth.id(s), "LIKE");
      return;
    }
    auth.approved(s, event);
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

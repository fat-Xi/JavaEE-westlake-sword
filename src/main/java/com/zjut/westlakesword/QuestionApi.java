package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 提问箱的赞同票与独立单选投票分开管理和统计。 */
@RestController
@RequestMapping("/api")
public class QuestionApi {

  private final Store store;
  private final Auth auth;

  public QuestionApi(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
  }

  record Question(long eventId, Long talkId, String body) {}

  record Review(boolean pass, String reason) {}

  record Answer(String answer) {}

  record State(Boolean featured, Boolean closed) {}

  @GetMapping("/events/{id}/questions")
  public List<Map<String, Object>> questions(
    @PathVariable long id,
    HttpSession s
  ) {
    auth.approved(s, id);
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN'", id);
    long user = auth.id(s);
    var rows = store.db.queryForList(
      "SELECT q.*,COALESCE(NULLIF(p.nick,''),u.name) name,p.avatar,t.title talk_title,(SELECT COUNT(*) FROM votes v WHERE v.question_id=q.id) votes,EXISTS(SELECT 1 FROM votes v WHERE v.question_id=q.id AND v.user_id=?) supported FROM questions q JOIN users u ON u.id=q.user_id LEFT JOIN profiles p ON p.user_id=u.id LEFT JOIN talks t ON t.id=q.talk_id WHERE q.event_id=? AND (q.status='APPROVED' OR q.user_id=?) ORDER BY q.featured DESC,votes DESC,q.created DESC,q.id DESC",
      user,
      id,
      user
    );
    for (var row : rows) {
      Object supported = row.get("supported");
      row.put(
        "supported",
        Boolean.TRUE.equals(supported) ||
          (supported instanceof Number n && n.intValue() > 0)
      );
      row.put("mine", Store.num(row, "user_id") == user);
    }
    return rows;
  }

  @PostMapping("/questions")
  @Transactional
  public Map<String, Long> question(@RequestBody Question q, HttpSession s) {
    auth.member(s);
    String body = Store.word(q.body(), 500, "问题");
    var event = store.one(
      "SELECT * FROM events WHERE id=? AND status='OPEN' FOR UPDATE",
      q.eventId()
    );
    auth.approved(s, q.eventId());
    Api.check(
      ((LocalDateTime) event.get("ends")).isAfter(LocalDateTime.now()),
      "会议已结束，停止征集新问题"
    );
    if (q.talkId() != null) {
      Api.check(q.talkId() > 0, "请选择有效议程");
      store.one(
        "SELECT id FROM talks WHERE id=? AND event_id=? FOR UPDATE",
        q.talkId(),
        q.eventId()
      );
    }
    store.db.update(
      "INSERT INTO questions(event_id,user_id,talk_id,body) VALUES(?,?,?,?)",
      q.eventId(),
      auth.id(s),
      q.talkId(),
      body
    );
    long id = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    store.log(auth.id(s), "提交会议提问", id);
    return Map.of("id", id);
  }

  private Map<String, Object> memberQuestion(long id, HttpSession s) {
    auth.member(s);
    var reference = store.one("SELECT event_id FROM questions WHERE id=?", id);
    // 与报名取消保持活动行锁顺序，再锁问题，避免审核、关闭和赞同交错。
    var event = store.one(
      "SELECT * FROM events WHERE id=? AND status='OPEN' FOR UPDATE",
      reference.get("event_id")
    );
    auth.approved(s, Store.num(reference, "event_id"));
    var question = store.one(
      "SELECT * FROM questions WHERE id=? FOR UPDATE",
      id
    );
    Api.check(
      "APPROVED".equals(question.get("status")),
      "仅可操作审核通过的问题"
    );
    question.put("event_ends", event.get("ends"));
    return question;
  }

  @PostMapping("/questions/{id}/support")
  @Transactional
  public void support(@PathVariable long id, HttpSession s) {
    var question = memberQuestion(id, s);
    Api.check(
      !Boolean.TRUE.equals(question.get("closed")),
      "问题已关闭，停止新增赞同"
    );
    Api.check(
      ((LocalDateTime) question.get("event_ends")).isAfter(LocalDateTime.now()),
      "会议已结束，停止新增赞同"
    );
    // 唯一键保证重复点击、并发和网络重试只计一票。
    if (
      store.db.update(
        "INSERT IGNORE INTO votes(user_id,question_id) VALUES(?,?)",
        auth.id(s),
        id
      ) > 0
    ) {
      store.log(auth.id(s), "赞同会议提问", id);
    }
  }

  @DeleteMapping({ "/questions/{id}/support", "/questions/{id}/vote" })
  @Transactional
  public void unsupport(@PathVariable long id, HttpSession s) {
    memberQuestion(id, s);
    // 关闭或结束后仍可撤销自己的赞同，不影响其他人的记录。
    if (
      store.db.update(
        "DELETE FROM votes WHERE user_id=? AND question_id=?",
        auth.id(s),
        id
      ) > 0
    ) {
      store.log(auth.id(s), "撤销提问赞同", id);
    }
  }

  @GetMapping("/votes")
  public List<Long> votes(HttpSession s) {
    auth.member(s);
    return store.db.queryForList(
      "SELECT v.question_id FROM votes v JOIN questions q ON q.id=v.question_id JOIN joins j ON j.event_id=q.event_id AND j.user_id=v.user_id JOIN events e ON e.id=q.event_id WHERE v.user_id=? AND q.status='APPROVED' AND j.status='APPROVED' AND e.status='OPEN'",
      Long.class,
      auth.id(s)
    );
  }

  @PostMapping("/questions/{id}/vote")
  public void legacyVote(@PathVariable long id) {
    Api.check(false, "问题赞同请使用提问箱；单选投票请使用会议投票模块");
  }

  @GetMapping("/review/questions")
  public List<Map<String, Object>> reviewQuestions(HttpSession s) {
    auth.need(s, "review");
    return store.db.queryForList(
      "SELECT q.*,e.title event_title,t.title talk_title,COALESCE(NULLIF(p.nick,''),u.name) name,(SELECT COUNT(*) FROM votes v WHERE v.question_id=q.id) votes FROM questions q JOIN events e ON e.id=q.event_id JOIN users u ON u.id=q.user_id LEFT JOIN profiles p ON p.user_id=u.id LEFT JOIN talks t ON t.id=q.talk_id ORDER BY q.featured DESC,votes DESC,q.created DESC,q.id DESC"
    );
  }

  @PostMapping("/review/questions/{id}")
  @Transactional
  public void review(
    @PathVariable long id,
    @RequestBody Review r,
    HttpSession s
  ) {
    auth.need(s, "review");
    String reason = r.pass() ? "" : Store.word(r.reason(), 200, "驳回原因");
    var question = store.one(
      "SELECT * FROM questions WHERE id=? FOR UPDATE",
      id
    );
    String status = r.pass() ? "APPROVED" : "REJECTED";
    if (
      status.equals(question.get("status")) &&
      reason.equals(question.get("reason"))
    ) return;
    store.db.update(
      "UPDATE questions SET status=?,reason=?,featured=CASE WHEN ? THEN featured ELSE FALSE END WHERE id=?",
      status,
      reason,
      r.pass(),
      id
    );
    store.note(
      Store.num(question, "user_id"),
      r.pass() ? "你的会议提问已通过审核" : "你的会议提问未通过审核：" + reason
    );
    store.log(auth.id(s), "问题审核", id + ":" + status);
  }

  @PutMapping("/review/questions/{id}/answer")
  @Transactional
  public void answer(
    @PathVariable long id,
    @RequestBody Answer a,
    HttpSession s
  ) {
    auth.need(s, "review");
    String answer = Store.word(a.answer(), 1000, "答复");
    var question = store.one(
      "SELECT * FROM questions WHERE id=? FOR UPDATE",
      id
    );
    Api.check("APPROVED".equals(question.get("status")), "请先审核通过该问题");
    if (answer.equals(question.get("answer"))) return;
    store.db.update("UPDATE questions SET answer=? WHERE id=?", answer, id);
    store.note(
      Store.num(question, "user_id"),
      "你的会议提问已收到主办方答复，请查看会议提问箱。"
    );
    store.log(auth.id(s), "答复问题", id);
  }

  @PutMapping("/review/questions/{id}/state")
  @Transactional
  public void state(
    @PathVariable long id,
    @RequestBody State state,
    HttpSession s
  ) {
    auth.need(s, "review");
    Api.check(
      state.featured() != null || state.closed() != null,
      "请选择精选或关闭状态"
    );
    var question = store.one(
      "SELECT * FROM questions WHERE id=? FOR UPDATE",
      id
    );
    Api.check(
      "APPROVED".equals(question.get("status")),
      "仅可处理审核通过的问题"
    );
    boolean featured =
      state.featured() == null
        ? Boolean.TRUE.equals(question.get("featured"))
        : state.featured();
    boolean closed =
      state.closed() == null
        ? Boolean.TRUE.equals(question.get("closed"))
        : state.closed();
    store.db.update(
      "UPDATE questions SET featured=?,closed=? WHERE id=?",
      featured,
      closed,
      id
    );
    store.log(
      auth.id(s),
      "调整提问精选与关闭状态",
      id + ":featured=" + featured + ",closed=" + closed
    );
  }
}

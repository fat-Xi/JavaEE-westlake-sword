package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 管理员发布单选投票；报名通过者每题只能投一次，重复请求不会重复计数。 */
@RestController
@RequestMapping("/api")
public class PollApi {

  private final Store store;
  private final Auth auth;

  public PollApi(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
  }

  record Form(
    long eventId,
    String title,
    List<String> options,
    LocalDateTime starts,
    LocalDateTime ends
  ) {}

  record Ballot(long optionId) {}

  record State(boolean enabled) {}

  @GetMapping("/events/{id}/poll-info")
  public Map<String,Object> info(@PathVariable long id, HttpSession s) {
    auth.approved(s,id);
    var info=store.db.queryForList("SELECT r.poll_title title,COALESCE(NULLIF(p.nick,''),u.name) publisher FROM requests r JOIN users u ON u.id=r.user_id LEFT JOIN profiles p ON p.user_id=u.id WHERE r.event_id=? AND r.status='CREATED' ORDER BY r.id DESC LIMIT 1",id);
    long people=store.count("SELECT COUNT(DISTINCT b.user_id) FROM ballots b JOIN polls p ON p.id=b.poll_id WHERE p.event_id=?",id);
    var result=new HashMap<String,Object>();
    result.put("title",info.isEmpty() || String.valueOf(info.get(0).get("title")).isBlank()?"会议意见征集":info.get(0).get("title"));
    result.put("publisher",info.isEmpty()?"会议主办方":info.get(0).get("publisher"));
    var headings=store.db.queryForList("SELECT heading FROM polls WHERE event_id=? AND heading<>'' ORDER BY id DESC LIMIT 1",id);
    if(!headings.isEmpty())result.put("title",headings.get(0).get("heading"));
    result.put("people",people);return result;
  }

  @GetMapping("/events/{id}/polls")
  public List<Map<String, Object>> list(@PathVariable long id, HttpSession s) {
    if ("ADMIN".equals(auth.role(s))) auth.admin(s);
    else auth.approved(s, id);
    var rows = store.db.queryForList(
      "SELECT * FROM polls WHERE event_id=? ORDER BY id DESC",
      id
    );
    for (var row : rows) {
      long poll = Store.num(row, "id");
      row.put(
        "options",
        store.db.queryForList(
          "SELECT o.id,o.label,COUNT(b.user_id) total FROM poll_options o LEFT JOIN ballots b ON b.option_id=o.id AND b.poll_id=o.poll_id WHERE o.poll_id=? GROUP BY o.id,o.label ORDER BY o.id",
          poll
        )
      );
      row.put(
        "choice",
        store.db.queryForList(
          "SELECT option_id FROM ballots WHERE poll_id=? AND user_id=?",
          Long.class,
          poll,
          auth.id(s)
        )
      );
    }
    return rows;
  }

  @PostMapping("/polls")
  @Transactional
  public void add(@RequestBody Form f, HttpSession s) {
    auth.admin(s);
    var event = store.one(
      "SELECT * FROM events WHERE id=? FOR UPDATE",
      f.eventId()
    );
    Store.word(f.title(), 120, "投票主题");
    Api.check(
      f.options() != null && f.options().size() >= 2 && f.options().size() <= 8,
      "请填写2至8个选项"
    );
    var options = f
      .options()
      .stream()
      .map(x -> Store.word(x, 120, "选项"))
      .toList();
    Api.check(new HashSet<>(options).size() == options.size(), "选项不能重复");
    Api.check(
      f.starts() != null &&
        f.ends() != null &&
        f.ends().isAfter(f.starts()) &&
        f.ends().isAfter(LocalDateTime.now()),
      "请设置有效投票时间"
    );
    Api.check(
      !f.ends().isAfter((LocalDateTime) event.get("ends")),
      "投票不能晚于会议结束"
    );
    store.db.update(
      "INSERT INTO polls(event_id,title,starts,ends) VALUES(?,?,?,?)",
      f.eventId(),
      f.title(),
      f.starts(),
      f.ends()
    );
    long id = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    for (String option : options)
      store.db.update(
        "INSERT INTO poll_options(poll_id,label) VALUES(?,?)",
        id,
        option
      );
    store.log(auth.id(s), "发布投票", id);
  }

  @PutMapping("/polls/{id}")
  @Transactional
  public void state(
    @PathVariable long id,
    @RequestBody State f,
    HttpSession s
  ) {
    auth.admin(s);
    store.one("SELECT id FROM polls WHERE id=? FOR UPDATE", id);
    store.db.update("UPDATE polls SET enabled=? WHERE id=?", f.enabled(), id);
    store.log(auth.id(s), f.enabled() ? "开放投票" : "关闭投票", id);
  }

  record Pick(long pollId, long optionId) {}
  record Batch(List<Pick> answers) {}

  /** 多题统一提交；任何一题无效时整批回滚，避免只保存部分答案。 */
  @PostMapping("/events/{id}/polls/vote")
  @Transactional
  public void batch(@PathVariable long id, @RequestBody Batch f, HttpSession s) {
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN' FOR UPDATE", id);
    auth.approved(s, id);
    Api.check(f.answers() != null && !f.answers().isEmpty() && f.answers().size() <= 100, "请选择投票答案");
    var ordered = f.answers().stream().sorted(Comparator.comparingLong(Pick::pollId)).toList();
    Api.check(ordered.stream().map(Pick::pollId).distinct().count() == ordered.size(), "不能重复提交同一题");
    for (var pick : ordered) {
      var poll = store.one("SELECT event_id FROM polls WHERE id=? FOR UPDATE", pick.pollId());
      Api.check(Store.num(poll, "event_id") == id, "投票不属于当前会议");
      vote(pick.pollId(), new Ballot(pick.optionId()), s);
    }
  }

  @PostMapping("/polls/{id}/vote")
  @Transactional
  public void vote(
    @PathVariable long id,
    @RequestBody Ballot f,
    HttpSession s
  ) {
    var poll = store.one("SELECT * FROM polls WHERE id=? FOR UPDATE", id);
    auth.approved(s, Store.num(poll, "event_id"));
    store.one(
      "SELECT id FROM events WHERE id=? AND status='OPEN'",
      poll.get("event_id")
    );
    var now = LocalDateTime.now();
    Api.check(
      Boolean.TRUE.equals(poll.get("enabled")) &&
        !now.isBefore((LocalDateTime) poll.get("starts")) &&
        now.isBefore((LocalDateTime) poll.get("ends")),
      "投票未开放或已结束"
    );
    store.one(
      "SELECT id FROM poll_options WHERE id=? AND poll_id=?",
      f.optionId(),
      id
    );
    Api.check(
      store.count(
        "SELECT COUNT(*) FROM ballots WHERE poll_id=? AND user_id=?",
        id,
        auth.id(s)
      ) == 0,
      "你已参与本次投票"
    );
    store.db.update(
      "INSERT INTO ballots(poll_id,user_id,option_id) VALUES(?,?,?)",
      id,
      auth.id(s),
      f.optionId()
    );
    store.log(auth.id(s), "参与投票", id);
  }
}

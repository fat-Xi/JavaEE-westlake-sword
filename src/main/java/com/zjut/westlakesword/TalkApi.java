package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TalkApi {

  private final Store store;
  private final Auth auth;

  private final AgendaRecommendations recommendations;

  public TalkApi(
    Store store,
    Auth auth,
    AgendaRecommendations recommendations
  ) {
    this.store = store;
    this.auth = auth;
    this.recommendations = recommendations;
  }

  record Talk(
    long eventId,
    String title,
    String speaker,
    String room,
    LocalDateTime starts,
    LocalDateTime ends,
    String tags
  ) {}

  @GetMapping("/events/{id}/talks")
  public List<Map<String, Object>> talks(@PathVariable long id, HttpSession s) {
    if ("ADMIN".equals(auth.role(s))) auth.admin(s);
    else auth.approved(s, id);
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN'", id);
    return store.db.queryForList(
      "SELECT id,event_id,title,speaker,room,starts,ends,tags FROM talks WHERE event_id=? ORDER BY starts",
      id
    );
  }

  @PostMapping("/talks")
  @Transactional
  public void add(@RequestBody Talk t, HttpSession s) {
    auth.need(s, "event");
    var event = store.one(
      "SELECT * FROM events WHERE id=? FOR UPDATE",
      t.eventId()
    );
    Store.word(t.title(), 120, "议程标题");
    Store.word(t.speaker(), 80, "讲师");
    Store.word(t.room(), 80, "会场");
    Api.check(t.tags() != null && t.tags().length() <= 200, "标签最多200字");
    Api.check(
      t.starts() != null && t.ends() != null && t.ends().isAfter(t.starts()),
      "议程时间错误"
    );
    Api.check(
      !t.starts().isBefore((LocalDateTime) event.get("starts")) &&
        !t.ends().isAfter((LocalDateTime) event.get("ends")),
      "议程超出活动时间"
    );
    Api.check(
      store.count(
        "SELECT COUNT(*) FROM talks WHERE event_id=? AND room=? AND starts<? AND ends>?",
        t.eventId(),
        t.room(),
        t.ends(),
        t.starts()
      ) == 0,
      "同一会场的议程时间冲突"
    );
    store.db.update(
      "INSERT INTO talks(event_id,title,speaker,room,starts,ends,tags) VALUES(?,?,?,?,?,?,?)",
      t.eventId(),
      t.title(),
      t.speaker(),
      t.room(),
      t.starts(),
      t.ends(),
      t.tags()
    );
    store.log(auth.id(s), "添加议程", t.title());
  }

  @DeleteMapping("/talks/{id}")
  @Transactional
  public void remove(@PathVariable long id, HttpSession s) {
    auth.need(s, "event");
    store.one("SELECT id FROM talks WHERE id=? FOR UPDATE", id);
    Api.check(
      store.count("SELECT COUNT(*) FROM plans WHERE talk_id=?", id) == 0,
      "该议程已有个人日程，不能删除"
    );
    Api.check(
      store.count("SELECT COUNT(*) FROM attendance WHERE talk_id=?", id) == 0,
      "该议程已发布签到，不能删除"
    );
    Api.check(
      store.count("SELECT COUNT(*) FROM questions WHERE talk_id=?", id) == 0,
      "该议程已有提问，不能删除"
    );
    store.db.update("DELETE FROM talks WHERE id=?", id);
    store.log(auth.id(s), "删除议程", id);
  }

  @GetMapping("/events/{id}/plans")
  public List<Map<String, Object>> plans(@PathVariable long id, HttpSession s) {
    auth.approved(s, id);
    return store.db.queryForList(
      "SELECT t.* FROM plans p JOIN talks t ON t.id=p.talk_id WHERE p.user_id=? AND t.event_id=? ORDER BY t.starts",
      auth.id(s),
      id
    );
  }

  @PostMapping("/talks/{id}/plan")
  @Transactional
  public void plan(@PathVariable long id, HttpSession s) {
    long user = auth.id(s);
    long eventId = Store.num(
      store.one("SELECT event_id FROM talks WHERE id=?", id),
      "event_id"
    );
    // 与取消报名使用相同活动锁，避免取消后并发插入残留日程。
    store.one("SELECT id FROM events WHERE id=? FOR UPDATE", eventId);
    // 锁住用户行，避免并发加入两个互相冲突的议程。
    store.one("SELECT id FROM users WHERE id=? FOR UPDATE", user);
    var t = store.one("SELECT * FROM talks WHERE id=? FOR UPDATE", id);
    auth.approved(s, Store.num(t, "event_id"));
    Api.check(
      store.count(
        "SELECT COUNT(*) FROM plans WHERE user_id=? AND talk_id=?",
        user,
        id
      ) == 0,
      "已在个人日程中"
    );
    Api.check(
      store.count(
        "SELECT COUNT(*) FROM plans p JOIN talks t ON p.talk_id=t.id WHERE p.user_id=? AND t.starts<? AND t.ends>?",
        user,
        t.get("ends"),
        t.get("starts")
      ) == 0,
      "与个人日程时间冲突，请先移除冲突项"
    );
    store.db.update("INSERT INTO plans(user_id,talk_id) VALUES(?,?)", user, id);
  }

  @DeleteMapping("/talks/{id}/plan")
  public void unplan(@PathVariable long id, HttpSession s) {
    store.db.update(
      "DELETE FROM plans WHERE user_id=? AND talk_id=?",
      auth.id(s),
      id
    );
  }

  @GetMapping("/events/{id}/recommend")
  public List<Map<String, Object>> recommend(
    @PathVariable long id,
    HttpSession s
  ) {
    auth.approved(s, id);
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN'", id);
    return recommendations.find(auth.id(s), id).stream().limit(6).toList();
  }

  /** 仅返回模块契约，不伪造 AI、地图或话题业务结果。 */
  @GetMapping("/events/{id}/modules/{name}")
  public Map<String, Object> module(
    @PathVariable long id,
    @PathVariable String name,
    HttpSession s
  ) {
    auth.approved(s, id);
    Api.check(Set.of("map", "ai", "topic").contains(name), "模块不存在");
    if ("ai".equals(name)) return Map.of(
      "name",
      name,
      "enabled",
      true,
      "message",
      "AI 问答已启用"
    );
    return Map.of(
      "name",
      name,
      "enabled",
      false,
      "message",
      "该模块按本期范围暂不实现，页面与权限框架已预留"
    );
  }
}

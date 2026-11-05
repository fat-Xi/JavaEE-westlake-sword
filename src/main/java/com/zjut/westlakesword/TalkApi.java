package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TalkApi {

  private final Store store;
  private final Auth auth;

  public TalkApi(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
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
  public List<Map<String, Object>> talks(@PathVariable long id) {
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
    long user = auth.id(s);
    var profiles = store.db.queryForList(
      "SELECT tags FROM profiles WHERE user_id=?",
      user
    );
    var interests = tags(
      profiles.isEmpty() ? null : (String) profiles.get(0).get("tags")
    );
    // 与加入日程接口使用相同的时间重叠规则，包括其他会议中的日程。
    var plans = store.db.queryForList(
      "SELECT t.id,t.event_id,t.title,t.room,t.starts,t.ends,e.title event_title FROM plans p JOIN talks t ON t.id=p.talk_id JOIN events e ON e.id=t.event_id WHERE p.user_id=? ORDER BY t.starts,t.id",
      user
    );
    var list = store.db.queryForList(
      "SELECT t.id,t.event_id,t.title,t.speaker,t.room,t.starts,t.ends,t.tags,(SELECT COUNT(*) FROM plans p WHERE p.talk_id=t.id) popularity FROM talks t WHERE t.event_id=? AND t.ends>NOW() AND NOT EXISTS (SELECT 1 FROM plans p WHERE p.talk_id=t.id AND p.user_id=?)",
      id,
      user
    );
    for (var t : list) {
      var talkTags = tags((String) t.get("tags")).keySet();
      var matched = interests
        .entrySet()
        .stream()
        .filter(tag -> talkTags.contains(tag.getKey()))
        .map(Map.Entry::getValue)
        .toList();
      LocalDateTime starts = (LocalDateTime) t.get("starts");
      LocalDateTime ends = (LocalDateTime) t.get("ends");
      var conflicts = plans
        .stream()
        .filter(
          p ->
            ((LocalDateTime) p.get("starts")).isBefore(ends) &&
            ((LocalDateTime) p.get("ends")).isAfter(starts)
        )
        .toList();
      long popularity = Store.num(t, "popularity");
      t.put("score", matched.size());
      t.put("matched_tags", matched);
      t.put("conflicts", conflicts);
      t.put(
        "reason",
        !matched.isEmpty()
          ? "匹配你的兴趣：" + String.join("、", matched)
          : popularity > 0
            ? "按收藏热度推荐，已有 " + popularity + " 人加入日程"
            : "暂无收藏记录，按议程开始时间推荐"
      );
    }
    // 先满足可参加的时间，再比较兴趣、实际收藏人数；相同分数时顺序稳定。
    list.sort(
      Comparator.<Map<String, Object>>comparingInt(t ->
        ((List<?>) t.get("conflicts")).isEmpty() ? 0 : 1
      )
        .thenComparing(
          Comparator.comparingLong((Map<String, Object> t) ->
            Store.num(t, "score")
          ).reversed()
        )
        .thenComparing(
          Comparator.comparingLong((Map<String, Object> t) ->
            Store.num(t, "popularity")
          ).reversed()
        )
        .thenComparing(t -> (LocalDateTime) t.get("starts"))
        .thenComparingLong(t -> Store.num(t, "id"))
    );
    return list.stream().limit(6).toList();
  }

  private static Map<String, String> tags(String value) {
    Map<String, String> result = new LinkedHashMap<>();
    if (value == null) return result;
    for (String tag : Normalizer.normalize(value, Normalizer.Form.NFKC).split(
      "[,，;；、\\s\\p{Z}]+"
    )) {
      if (!tag.isBlank()) result.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
    }
    return result;
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
      "AI 会议问答已启用，模型不可用时提供资料检索结果"
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

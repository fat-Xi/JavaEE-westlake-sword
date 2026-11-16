package com.zjut.westlakesword;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class AgendaRecommendations {

  private final Store store;

  AgendaRecommendations(Store store) {
    this.store = store;
  }

  List<Map<String, Object>> find(long user, Long eventId) {
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
      """
      SELECT t.id,t.event_id,t.title,t.speaker,t.room,t.starts,t.ends,t.tags,
        e.title event_title,(SELECT COUNT(*) FROM plans p WHERE p.talk_id=t.id) popularity
      FROM talks t JOIN events e ON e.id=t.event_id
      JOIN joins j ON j.event_id=e.id AND j.user_id=? AND j.status='APPROVED'
      WHERE e.status='OPEN' AND e.ends>NOW() AND t.ends>NOW()
        AND NOT EXISTS (SELECT 1 FROM plans p WHERE p.talk_id=t.id AND p.user_id=?)
      """ + (eventId == null ? "" : " AND t.event_id=?"),
      eventId == null
        ? new Object[] { user, user }
        : new Object[] { user, user, eventId }
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
    return list;
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
}

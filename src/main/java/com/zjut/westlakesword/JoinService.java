package com.zjut.westlakesword;

import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JoinService {

  private final Store store;

  public JoinService(Store store) {
    this.store = store;
  }

  private java.util.Map<String, Object> lock(long eventId) {
    return store.one("SELECT * FROM events WHERE id=? FOR UPDATE", eventId);
  }

  /** 活动行锁保证报名审核与修改容量不会在并发时超卖。 */
  @Transactional
  public void apply(long userId, long eventId, String channel) {
    var event = lock(eventId);
    Api.check("OPEN".equals(event.get("status")), "活动已关闭");
    Api.check(
      ((LocalDateTime) event.get("ends")).isAfter(LocalDateTime.now()),
      "活动已结束"
    );
    Api.check(
      channel != null && Set.of("direct", "share", "poster").contains(channel),
      "报名渠道无效"
    );
    var old = store.db.queryForList(
      "SELECT * FROM joins WHERE user_id=? AND event_id=?",
      userId,
      eventId
    );
    if (old.isEmpty()) {
      store.db.update(
        "INSERT INTO joins(user_id,event_id) VALUES(?,?)",
        userId,
        eventId
      );
    } else {
      String state = (String) old.get(0).get("status");
      Api.check(
        Set.of("CANCELLED", "REJECTED").contains(state),
        "请勿重复报名"
      );
      store.db.update(
        "UPDATE joins SET status='PENDING',reason='' WHERE id=?",
        old.get(0).get("id")
      );
    }
    long id = Store.num(
      store.one(
        "SELECT id FROM joins WHERE user_id=? AND event_id=?",
        userId,
        eventId
      ),
      "id"
    );
    // 保留首次报名来源，重提不会覆盖来源。
    store.db.update(
      "INSERT IGNORE INTO join_info(join_id,channel) VALUES(?,?)",
      id,
      channel
    );
    store.log(userId, "提交报名", eventId);
    store.note(userId, "报名已提交，等待审核");
  }

  @Transactional
  public void cancel(long userId, long eventId) {
    lock(eventId);
    var join = store.one(
      "SELECT * FROM joins WHERE user_id=? AND event_id=? FOR UPDATE",
      userId,
      eventId
    );
    long id = Store.num(join, "id");
    Api.check(
      store.count(
        "SELECT COUNT(*) FROM marks m JOIN attendance a ON a.id=m.session_id JOIN talks t ON t.id=a.talk_id WHERE m.user_id=? AND t.event_id=?",
        userId,
        eventId
      ) == 0,
      "已经签到，不能取消"
    );
    Api.check(
      store.count(
        "SELECT COUNT(*) FROM tickets WHERE join_id=? AND checked IS NOT NULL",
        id
      ) == 0,
      "已经签到，不能取消"
    );
    Api.check(!"CANCELLED".equals(join.get("status")), "已取消");
    store.db.update("UPDATE joins SET status='CANCELLED' WHERE id=?", id);
    store.db.update("DELETE FROM tickets WHERE join_id=?", id);
    store.db.update(
      "DELETE FROM plans WHERE user_id=? AND talk_id IN (SELECT id FROM talks WHERE event_id=?)",
      userId,
      eventId
    );
    store.log(userId, "取消报名", eventId);
  }

  @Transactional
  public void review(long id, boolean pass, String reason) {
    long eventId = Store.num(
      store.one("SELECT event_id FROM joins WHERE id=?", id),
      "event_id"
    );
    var event = lock(eventId);
    var join = store.one("SELECT * FROM joins WHERE id=? FOR UPDATE", id);
    Api.check("PENDING".equals(join.get("status")), "此报名已处理");
    if (pass) {
      Api.check(
        store.count(
          "SELECT COUNT(*) FROM users WHERE id=? AND role='USER' AND status='APPROVED'",
          join.get("user_id")
        ) == 1,
        "参会身份尚未审核通过或已变更"
      );
      Api.check(
        "OPEN".equals(event.get("status")) &&
          ((LocalDateTime) event.get("ends")).isAfter(LocalDateTime.now()),
        "活动关闭或已结束，不能通过审核"
      );
      Api.check(
        store.count(
          "SELECT COUNT(*) FROM joins WHERE event_id=? AND status='APPROVED'",
          eventId
        ) < Store.num(event, "capacity"),
        "已达到活动人数上限"
      );
    } else {
      Store.word(reason, 200, "驳回原因");
    }
    Api.check(reason == null || reason.length() <= 200, "原因最多200字");
    store.db.update(
      "UPDATE joins SET status=?,reason=? WHERE id=?",
      pass ? "APPROVED" : "REJECTED",
      reason == null ? "" : reason,
      id
    );
    store.note(
      Store.num(join, "user_id"),
      pass ? "报名审核通过，可以使用参会服务" : "报名被驳回：" + reason
    );
  }

  @Transactional
  public void edit(
    long id,
    String title,
    String place,
    LocalDateTime starts,
    LocalDateTime ends,
    int capacity,
    String content,
    String status
  ) {
    lock(id);
    Api.check(
      capacity >=
        store.count(
          "SELECT COUNT(*) FROM joins WHERE event_id=? AND status='APPROVED'",
          id
        ),
      "容量不能小于已通过人数"
    );
    Api.check(
      store.count(
        "SELECT COUNT(*) FROM talks WHERE event_id=? AND (starts<? OR ends>?)",
        id,
        starts,
        ends
      ) == 0,
      "活动时间必须包含全部议程"
    );
    store.db.update(
      "UPDATE events SET title=?,place=?,starts=?,ends=?,capacity=?,content=?,status=? WHERE id=?",
      title,
      place,
      starts,
      ends,
      capacity,
      content,
      status,
      id
    );
  }
}

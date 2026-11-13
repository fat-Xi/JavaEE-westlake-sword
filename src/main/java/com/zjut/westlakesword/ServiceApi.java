package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 按议程管理直播和签到。管理员发布，工作人员核销，参会者只领取个人凭证。 */
@RestController
@RequestMapping("/api")
public class ServiceApi {

  private final Store store;
  private final Auth auth;

  public ServiceApi(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
  }

  record Media(String liveUrl, String replayUrl, boolean liveOn) {}

  record Session(
    long talkId,
    LocalDateTime starts,
    LocalDateTime ends,
    boolean enabled
  ) {}

  record Scan(long sessionId, String token) {}

  @PutMapping("/talks/{id}/media")
  @Transactional
  public void media(
    @PathVariable long id,
    @RequestBody Media m,
    HttpSession s
  ) {
    auth.admin(s);
    store.one("SELECT id FROM talks WHERE id=? FOR UPDATE", id);
    String live = Store.url(m.liveUrl());
    String replay = ("/api/talks/" + id + "/replay").equals(m.replayUrl())
      ? m.replayUrl()
      : Store.url(m.replayUrl());
    if (replay.startsWith("/api/")) store.one(
      "SELECT talk_id FROM replays WHERE talk_id=?",
      id
    );
    Api.check(
      !m.liveOn() || !live.isEmpty(),
      "开启直播前请填写可内嵌播放的地址"
    );
    store.db.update(
      "UPDATE talks SET live_url=?,replay_url=?,live_on=?,had_live=(had_live OR ? OR ?) WHERE id=?",
      live,
      replay,
      m.liveOn(),
      m.liveOn(),
      !replay.isEmpty(),
      id
    );
    store.log(auth.id(s), "配置议程直播回放", id);
  }

  @GetMapping("/events/{id}/media")
  public List<Map<String, Object>> media(@PathVariable long id, HttpSession s) {
    auth.approved(s, id);
    // 直播URL仅在真实开启并处于该议程时间段时下发；结束后只返回回放。
    return store.db.queryForList(
      "SELECT id,title,starts,ends,live_on,had_live,CASE WHEN live_on=TRUE AND starts<=NOW() AND ends>NOW() THEN live_url ELSE '' END live_url,CASE WHEN ends<=NOW() THEN replay_url ELSE '' END replay_url FROM talks WHERE event_id=? ORDER BY starts",
      id
    );
  }

  @GetMapping("/my/plans")
  public List<Map<String, Object>> plans(HttpSession s) {
    auth.member(s);
    return store.db
      .queryForList(
        "SELECT t.*,e.title event_title FROM plans p JOIN talks t ON t.id=p.talk_id JOIN events e ON e.id=t.event_id JOIN joins j ON j.event_id=e.id AND j.user_id=p.user_id WHERE p.user_id=? AND j.status='APPROVED' ORDER BY t.starts",
        auth.id(s)
      )
      .stream()
      .map(t -> {
        t.remove("live_url");
        t.remove("replay_url");
        return t;
      })
      .toList();
  }

  @GetMapping("/my/subs")
  public List<Map<String, Object>> subs(HttpSession s) {
    auth.member(s);
    return store.db.queryForList(
      "SELECT e.* FROM subs b JOIN events e ON e.id=b.event_id WHERE b.user_id=? ORDER BY e.starts DESC",
      auth.id(s)
    );
  }

  @PostMapping("/attendance")
  @Transactional
  public void publish(@RequestBody Session f, HttpSession s) {
    auth.admin(s);
    var talk = store.one(
      "SELECT * FROM talks WHERE id=? FOR UPDATE",
      f.talkId()
    );
    Api.check(
      !java.time.LocalDateTime.now().isBefore(
        (java.time.LocalDateTime) talk.get("starts")
      ) &&
        java.time.LocalDateTime.now().isBefore(
          (java.time.LocalDateTime) talk.get("ends")
        ),
      "只能发布正在进行的议程签到"
    );
    Api.check(
      store.count(
        "SELECT COUNT(*) FROM attendance WHERE talk_id=?",
        f.talkId()
      ) == 0,
      "此议程已发布签到"
    );
    Api.check(
      store.count(
        "SELECT COUNT(*) FROM exhibit_reviews WHERE kind='CHECK' AND status='PENDING' AND JSON_EXTRACT(payload,'$.talkId')=?",
        f.talkId()
      ) == 0,
      "展商已提交此议程的签到申请，请先处理该申请"
    );
    Api.check(
      f.starts() != null && f.ends() != null && f.ends().isAfter(f.starts()),
      "签到结束时间必须晚于开始时间"
    );
    Api.check(
      !f
        .starts()
        .isBefore(((LocalDateTime) talk.get("starts")).minusHours(2)) &&
        !f.ends().isAfter((LocalDateTime) talk.get("ends")),
      "签到窗口应在议程开始前2小时至议程结束之间"
    );
    store.db.update(
      "INSERT INTO attendance(talk_id,starts,ends,enabled,code) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE starts=?,ends=?,enabled=?",
      f.talkId(),
      f.starts(),
      f.ends(),
      f.enabled(),
      UUID.randomUUID().toString(),
      f.starts(),
      f.ends(),
      f.enabled()
    );
    store.log(auth.id(s), "发布或调整议程签到", f.talkId());
  }

  @GetMapping("/attendance")
  public List<Map<String, Object>> sessions(
    @RequestParam long eventId,
    HttpSession s
  ) {
    String role = auth.role(s);
    if ("USER".equals(role)) auth.approved(s, eventId);
    else if (!Set.of("ADMIN", "STAFF").contains(role)) Auth.denied(
      "无权查看签到场次"
    );
    return store.db.queryForList(
      "SELECT a.*,t.title,t.event_id,t.starts talk_starts,t.ends talk_ends,t.room,(SELECT m.checked FROM marks m WHERE m.session_id=a.id AND m.user_id=?) checked,(SELECT COUNT(*) FROM marks m WHERE m.session_id=a.id) total FROM attendance a JOIN talks t ON t.id=a.talk_id WHERE t.event_id=? ORDER BY a.starts",
      auth.id(s),
      eventId
    );
  }

  @GetMapping("/attendance/{id}/results")
  public Map<String, Object> results(@PathVariable long id, HttpSession s) {
    if (!Set.of("ADMIN", "STAFF").contains(auth.role(s))) Auth.denied(
      "无权查询签到名单"
    );
    var a = store.one(
      "SELECT a.*,t.event_id FROM attendance a JOIN talks t ON t.id=a.talk_id WHERE a.id=?",
      id
    );
    return Map.of(
      "rows",
      store.db.queryForList(
        "SELECT u.name,COALESCE(NULLIF(p.nick,''),u.name) nick,p.real_name,m.checked,s.name staff FROM marks m JOIN users u ON u.id=m.user_id JOIN users s ON s.id=m.staff_id LEFT JOIN profiles p ON p.user_id=u.id WHERE session_id=? ORDER BY m.checked DESC",
        id
      ),
      "eligible",
      store.count(
        "SELECT COUNT(*) FROM joins j JOIN users u ON u.id=j.user_id WHERE event_id=? AND j.status='APPROVED' AND u.role='USER' AND u.status='APPROVED'",
        a.get("event_id")
      )
    );
  }

  @PostMapping("/attendance/scan")
  @Transactional
  public Map<String, Object> scan(@RequestBody Scan scan, HttpSession s) {
    if (!"STAFF".equals(auth.role(s))) Auth.denied(
      "管理员只能发布签到，核销由工作人员执行"
    );
    auth.need(s, "checkin");
    Api.check(scan.sessionId() > 0, "请先选择本签到台负责的核销场次");
    Api.check(
      scan.token() != null && scan.token().matches("[a-zA-Z0-9_-]{16,64}"),
      "请扫描参会者个人签到码，管理员的入口二维码不能核销"
    );
    // 与报名取消保持活动行锁顺序，避免取消与签到交错。
    var first = store.one(
      "SELECT t.event_id FROM attendance a JOIN talks t ON t.id=a.talk_id WHERE a.id=?",
      scan.sessionId()
    );
    var event = store.one(
      "SELECT * FROM events WHERE id=? FOR UPDATE",
      first.get("event_id")
    );
    var a = store.one(
      "SELECT * FROM attendance WHERE id=? FOR UPDATE",
      scan.sessionId()
    );
    var people = store.db.queryForList(
      "SELECT j.user_id,j.status,u.name,u.role,u.status user_status FROM tickets k JOIN joins j ON j.id=k.join_id JOIN users u ON u.id=j.user_id WHERE k.token=? AND j.event_id=?",
      scan.token(),
      event.get("id")
    );
    Api.check(
      !people.isEmpty(),
      "个人票据无效，或不属于当前会议。请核对会议选择与参会者出示的签到码。"
    );
    var u = people.get(0);
    Api.check(
      "APPROVED".equals(u.get("status")) &&
        "APPROVED".equals(u.get("user_status")) &&
        "USER".equals(u.get("role")),
      "参会身份或报名未通过"
    );
    if (
      store.count(
        "SELECT COUNT(*) FROM marks WHERE session_id=? AND user_id=?",
        scan.sessionId(),
        u.get("user_id")
      ) > 0
    ) return Map.of(
      "name",
      u.get("name"),
      "message",
      "已签到，请勿重复核销",
      "duplicate",
      true
    );
    LocalDateTime now = LocalDateTime.now();
    Api.check(
      Boolean.TRUE.equals(a.get("enabled")) &&
        "OPEN".equals(event.get("status")) &&
        !now.isBefore((LocalDateTime) a.get("starts")) &&
        now.isBefore((LocalDateTime) a.get("ends")),
      "签到尚未开始、已结束或管理员已关闭"
    );
    store.db.update(
      "INSERT INTO marks(session_id,user_id,staff_id) VALUES(?,?,?)",
      scan.sessionId(),
      u.get("user_id"),
      auth.id(s)
    );
    store.log(
      auth.id(s),
      "议程签到核销",
      scan.sessionId() + ":" + u.get("user_id")
    );
    return Map.of(
      "name",
      u.get("name"),
      "message",
      "签到成功",
      "duplicate",
      false
    );
  }
}

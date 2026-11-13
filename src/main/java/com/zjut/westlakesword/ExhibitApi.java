package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** 展商送审接口：只允许操作本人会议，审核通过才写入正式业务表。 */
@RestController
@RequestMapping("/api")
public class ExhibitApi {

  final Store store;
  final Auth auth;
  final JsonMapper json = JsonMapper.builder().build();

  public Store store() {
    return store;
  }

  public Auth auth() {
    return auth;
  }

  public JsonMapper mapper() {
    return json;
  }

  public ExhibitApi(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> data(Object text) {
    return json.readValue(String.valueOf(text), Map.class);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rows(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return v instanceof List<?>
      ? (List<Map<String, Object>>) v
      : new ArrayList<>();
  }

  String str(Map<String, Object> m, String k) {
    return Objects.toString(m.get(k), "").trim();
  }

  long num(Map<String, Object> m, String k) {
    try {
      return new java.math.BigDecimal(str(m, k)).longValueExact();
    } catch (NumberFormatException | ArithmeticException e) {
      Api.check(false, "请填写有效整数：" + k);
      return 0;
    }
  }

  LocalDateTime date(Map<String, Object> m, String k) {
    try {
      return LocalDateTime.parse(str(m, k));
    } catch (Exception e) {
      Api.check(false, "请填写有效时间：" + k);
      return null;
    }
  }

  void owner(HttpSession s) {
    auth.need(s, "request");
    if (!"EXHIBITOR".equals(auth.role(s))) Auth.denied("仅展商可操作");
  }

  Map<String, Object> own(long id, HttpSession s, boolean lock) {
    owner(s);
    var m = store.one(
      "SELECT * FROM exhibit_meetings WHERE id=?" + (lock ? " FOR UPDATE" : ""),
      id
    );
    if (Store.num(m, "user_id") != auth.id(s)) Auth.denied("只能访问本人会议");
    return m;
  }

  String state(Map<String, Object> m, Map<String, Object> d) {
    if (!"APPROVED".equals(m.get("status"))) return switch (
      String.valueOf(m.get("status"))
    ) {
      case "PENDING" -> "待审核";
      case "REJECTED" -> "已驳回";
      default -> "已撤销";
    };
    // 正式会议时间可能由管理员调整，状态以正式记录为准。
    if (m.get("event_id") != null) {
      var current = store.one(
        "SELECT starts,ends FROM events WHERE id=?",
        m.get("event_id")
      );
      d = new HashMap<>(d);
      d.put("starts", current.get("starts").toString());
      d.put("ends", current.get("ends").toString());
    }
    var now = LocalDateTime.now();
    return date(d, "ends").isBefore(now)
      ? "已结束"
      : date(d, "starts").isAfter(now)
        ? "未开始"
        : "进行中";
  }

  void validate(Map<String, Object> d) {
    Store.word(str(d, "title"), 120, "会议标题");
    Store.word(str(d, "place"), 120, "地点");
    Store.word(str(d, "content"), 20000, "会议介绍");
    Api.check(date(d, "ends").isAfter(date(d, "starts")), "结束时间须晚于开始");
    Api.check(
      num(d, "capacity") > 0 && num(d, "capacity") <= 100000,
      "预计人数不正确"
    );
    Api.check(
      !str(d, "cover").isEmpty() && !str(d, "poster").isEmpty(),
      "请上传封面和海报"
    );
    Store.image(str(d, "cover"));
    Store.image(str(d, "poster"));
    Api.check(str(d, "coverTitle").length() <= 120, "封面标题最多120字");
    Api.check(
      Set.of(
        "",
        "数据安全",
        "人工智能",
        "云安全",
        "现场体验",
        "行业交流",
        "技术论坛",
        "成果发布",
        "其他"
      ).contains(str(d, "meetingType")),
      "会议类型不正确"
    );
    Object images = d.get("posters");
    if (images == null) images = List.of(str(d, "poster"));
    Api.check(
      images instanceof List<?> &&
        !((List<?>) images).isEmpty() &&
        ((List<?>) images).size() <= 8,
      "请上传1至8张轮播海报"
    );
    for (Object image : (List<?>) images) {
      Api.check(
        image instanceof String && !((String) image).isBlank(),
        "海报不能为空"
      );
      Store.image((String) image);
    }
    d.put("posters", images);
    d.put("poster", ((List<?>) images).get(0));

    Api.check(rows(d, "features").size() == 3, "请填写三个会议亮点");
    for (var f : rows(d, "features")) {
      Store.word(str(f, "title"), 80, "亮点标题");
      Store.word(str(f, "body"), 300, "亮点说明");
    }
    var talks = rows(d, "talks");
    Api.check(!talks.isEmpty() && talks.size() <= 50, "需要1至50个议程");
    for (int i = 0; i < talks.size(); i++) {
      var t = talks.get(i);
      Store.word(str(t, "title"), 120, "议程标题");
      Store.word(str(t, "speaker"), 80, "嘉宾");
      Store.word(str(t, "room"), 80, "会场");
      Store.word(str(t, "tags"), 200, "标签");
      Api.check(
        date(t, "ends").isAfter(date(t, "starts")) &&
          !date(t, "starts").isBefore(date(d, "starts")) &&
          !date(t, "ends").isAfter(date(d, "ends")),
        "议程时间须在会议范围内"
      );
      for (int j = 0; j < i; j++) {
        var b = talks.get(j);
        Api.check(
          !str(t, "room").equals(str(b, "room")) ||
            !date(t, "starts").isBefore(date(b, "ends")) ||
            !date(t, "ends").isAfter(date(b, "starts")),
          "同一会场议程时间冲突"
        );
      }
    }
    Api.check(rows(d, "materials").size() <= 30, "资料最多30份");
    for (var m : rows(d, "materials")) {
      Api.check(
        Set.of("展商介绍", "会议资讯", "成果展示").contains(str(m, "type")),
        "资料分类错误"
      );
      Store.word(str(m, "title"), 120, "资料标题");
      Store.word(str(m, "body"), 10000, "资料正文");
      Api.check(str(m, "product").length() <= 200, "简介最多200字");
      Store.image(str(m, "image"));
      for (var f : rows(m, "files")) {
        String n = Store.word(str(f, "name"), 180, "文件名");
        Api.check(
          n.matches("(?i)[^/\\\\]+\\.(pdf|ppt|pptx|docx|txt)"),
          "附件格式仅支持PDF、PPT、PPTX、DOCX、TXT"
        );
        String v = str(f, "data");
        Api.check(
          v.matches("data:[^,]*;base64,[A-Za-z0-9+/=]+") &&
            v.length() < 3000000,
          "单个附件限2MB"
        );
      }
    }
    Api.check(
      json.writeValueAsString(d).length() < 24000000,
      "会议资料总量过大"
    );
  }

  @GetMapping("/exhibitor/meetings")
  public List<Map<String, Object>> list(HttpSession s) {
    owner(s);
    var out = new ArrayList<Map<String, Object>>();
    for (var r : store.db.queryForList(
      "SELECT * FROM exhibit_meetings WHERE user_id=? AND status<>'WITHDRAWN' ORDER BY id DESC",
      auth.id(s)
    ))
      out.add(view(r));
    return out;
  }

  Map<String, Object> view(Map<String, Object> r) {
    var d = data(r.get("payload"));
    if (r.get("event_id") != null && "APPROVED".equals(r.get("status"))) {
      var live = store.one(
        "SELECT * FROM events WHERE id=?",
        r.get("event_id")
      );
      for (String k : List.of(
        "title",
        "place",
        "capacity",
        "content",
        "cover",
        "poster"
      ))
        if (live.get(k) != null) d.put(k, live.get(k));
      d.put("coverTitle", live.get("cover_title"));
      d.put("meetingType", live.get("meeting_type"));
      if (live.get("posters") != null) d.put(
        "posters",
        json.readValue(str(live, "posters"), List.class)
      );
      d.put("starts", live.get("starts").toString());
      d.put("ends", live.get("ends").toString());
      if (live.get("features") != null) d.put(
        "features",
        json.readValue(str(live, "features"), List.class)
      );
    }
    d.put("id", r.get("id"));
    d.put("eventId", r.get("event_id"));
    d.put("status", state(r, d));
    d.put("reason", r.get("reason"));
    d.put("checks", new ArrayList<>());
    d.put("polls", new ArrayList<>());
    d.put("questions", new ArrayList<>());
    d.put("comments", new ArrayList<>());
    d.put("signatures", new ArrayList<>());
    d.put("log", List.of());
    if (r.get("event_id") != null && "APPROVED".equals(r.get("status"))) enrich(
      d,
      Store.num(r, "event_id")
    );
    for (var t : rows(d, "talks"))
      if (t.get("id") != null) t.put(
        "saved",
        store.count(
          "SELECT COUNT(*) FROM plans WHERE user_id=? AND talk_id=?",
          r.get("user_id"),
          t.get("id")
        ) > 0
      );
    for (var v : store.db.queryForList(
      "SELECT * FROM exhibit_reviews WHERE meeting_id=? ORDER BY id DESC",
      r.get("id")
    )) {
      var p = data(v.get("payload"));
      p.put("id", v.get("id"));
      p.put(
        "status",
        switch (str(v, "status")) {
          case "PENDING" -> "待审核";
          case "APPROVED" -> "已通过";
          case "REJECTED" -> "已驳回";
          default -> "已撤销";
        }
      );
      p.put("reason", v.get("reason"));
      String kind = str(v, "kind");
      if (kind.equals("ANSWER")) {
        for (var q : rows(d, "questions"))
          if (
            num(q, "id") == num(p, "questionId") &&
            str(v, "status").equals("PENDING")
          ) {
            q.put("answer", p.get("answer"));
            q.put("status", "待审核");
            q.put("reviewId", v.get("id"));
          }
      } else if (!str(v, "status").equals("APPROVED")) rows(
        d,
        kind.equals("CHECK") ? "checks" : "polls"
      ).add(p);
    }
    return d;
  }

  void enrich(Map<String, Object> d, long event) {
    d.put(
      "talks",
      store.db.queryForList(
        "SELECT * FROM talks WHERE event_id=? ORDER BY starts,id",
        event
      )
    );
    for (var t : rows(d, "talks")) {
      t.put("starts", t.get("starts").toString());
      t.put("ends", t.get("ends").toString());
    }
    var checks = store.db.queryForList(
      "SELECT a.*,(SELECT COUNT(*) FROM marks m WHERE m.session_id=a.id) count FROM attendance a JOIN talks t ON t.id=a.talk_id WHERE t.event_id=?",
      event
    );
    for (var c : checks) {
      c.put("status", "已通过");
      c.put("talk", talkIndex(d, Store.num(c, "talk_id")));
    }
    d.put("checks", checks);
    var polls = store.db.queryForList(
      "SELECT * FROM polls WHERE event_id=? ORDER BY id",
      event
    );
    for (var p : polls) {
      var opts = store.db.queryForList(
        "SELECT o.label,COUNT(b.user_id) total FROM poll_options o LEFT JOIN ballots b ON b.option_id=o.id WHERE o.poll_id=? GROUP BY o.id,o.label ORDER BY o.id",
        p.get("id")
      );
      p.put("question", p.get("title"));
      if (!str(p, "heading").isBlank()) p.put("title", p.get("heading"));
      p.put(
        "options",
        opts
          .stream()
          .map(x -> x.get("label"))
          .toList()
      );
      p.put(
        "results",
        opts
          .stream()
          .map(x -> x.get("total"))
          .toList()
      );
      p.put("status", "已通过");
    }
    d.put("polls", polls);
    var qs = store.db.queryForList(
      "SELECT q.*,COALESCE(NULLIF(p.nick,''),u.name) name FROM questions q JOIN users u ON u.id=q.user_id LEFT JOIN profiles p ON p.user_id=u.id WHERE q.event_id=? AND q.status='APPROVED' ORDER BY q.id DESC",
      event
    );
    for (var q : qs) {
      q.put("status", str(q, "answer").isEmpty() ? "待回答" : "已通过");
      q.put(
        "talk",
        q.get("talk_id") == null ? 0 : talkIndex(d, Store.num(q, "talk_id"))
      );
      q.put("time", q.get("created").toString());
    }
    d.put("questions", qs);
    var posts = store.db.queryForList(
      "SELECT p.*,COALESCE(NULLIF(pr.nick,''),u.name) nick,pr.avatar FROM posts p JOIN users u ON u.id=p.user_id LEFT JOIN profiles pr ON pr.user_id=u.id WHERE p.event_id=? AND p.status='APPROVED' AND p.kind IN ('COMMENT','WALL') ORDER BY p.id DESC",
      event
    );
    for (var p : posts)
      if (Boolean.TRUE.equals(p.get("anonymous"))) {
        p.put("nick", "匿名参会者");
        p.put("avatar", "");
        p.remove("user_id");
      }
    d.put(
      "comments",
      posts
        .stream()
        .filter(p -> p.get("kind").equals("COMMENT"))
        .toList()
    );
    d.put(
      "signatures",
      posts
        .stream()
        .filter(p -> p.get("kind").equals("WALL"))
        .toList()
    );
  }

  int talkIndex(Map<String, Object> d, long id) {
    var ts = rows(d, "talks");
    for (int i = 0; i < ts.size(); i++) if (
      num(ts.get(i), "id") == id
    ) return i;
    return 0;
  }

  @PostMapping("/exhibitor/meetings")
  @Transactional
  public Map<String, Long> submit(
    @RequestBody Map<String, Object> d,
    HttpSession s
  ) {
    owner(s);
    validate(d);
    Api.check(
      date(d, "starts").isAfter(LocalDateTime.now()),
      "会议开始时间须晚于当前时间"
    );
    long id = d.get("id") == null ? 0 : num(d, "id");
    if (id == 0) {
      store.db.update(
        "INSERT INTO exhibit_meetings(user_id,payload) VALUES(?,?)",
        auth.id(s),
        json.writeValueAsString(d)
      );
      id = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    } else {
      var old = own(id, s, true);
      var oldData = data(old.get("payload"));
      Api.check(
        Set.of("待审核", "未开始", "已驳回", "已撤销").contains(
          state(old, oldData)
        ),
        "进行中及已结束会议不可修改"
      );
      if (old.get("event_id") != null) {
        store.one(
          "SELECT id FROM events WHERE id=? FOR UPDATE",
          old.get("event_id")
        );
        Api.check(
          store.count(
            "SELECT COUNT(*) FROM events WHERE id=? AND starts>NOW()",
            old.get("event_id")
          ) == 1,
          "会议已开始，不可修改"
        );
        store.db.update(
          "UPDATE events SET status='CLOSED' WHERE id=?",
          old.get("event_id")
        );
      }
      store.db.update(
        "UPDATE exhibit_meetings SET payload=?,status='PENDING',reason='' WHERE id=?",
        json.writeValueAsString(d),
        id
      );
    }
    store.log(auth.id(s), "展商会议提交审核", id);
    return Map.of("id", id);
  }

  @PostMapping("/exhibitor/meetings/{id}/withdraw")
  @Transactional
  public void withdraw(@PathVariable long id, HttpSession s) {
    var r = own(id, s, true);
    Api.check(str(r, "status").equals("PENDING"), "仅待审核内容可撤销");
    store.db.update(
      "UPDATE exhibit_meetings SET status='WITHDRAWN' WHERE id=?",
      id
    );
    store.log(auth.id(s), "撤销会议审核", id);
  }

  @PostMapping("/exhibitor/meetings/{id}/interactions/{kind}")
  @Transactional
  public void interact(
    @PathVariable long id,
    @PathVariable String kind,
    @RequestBody Map<String, Object> p,
    HttpSession s
  ) {
    var m = own(id, s, true);
    Api.check(
      state(m, data(m.get("payload"))).equals("进行中"),
      "仅进行中的会议可发布互动"
    );
    Api.check(Set.of("CHECK", "POLL", "ANSWER").contains(kind), "互动类型错误");
    long event = Store.num(m, "event_id");
    store.one(
      "SELECT id FROM events WHERE id=? AND status='OPEN' AND starts<=NOW() AND ends>NOW() FOR UPDATE",
      event
    );
    validateInteraction(kind, p, event);
    if (kind.equals("CHECK")) {
      p.put(
        "talkTitle",
        store
          .one("SELECT title FROM talks WHERE id=?", num(p, "talkId"))
          .get("title")
      );
    }
    if (kind.equals("ANSWER")) {
      p.put(
        "questionText",
        store
          .one("SELECT body FROM questions WHERE id=?", num(p, "questionId"))
          .get("body")
      );
    }
    if (kind.equals("CHECK")) Api.check(
      store.count(
        "SELECT COUNT(*) FROM exhibit_reviews WHERE kind='CHECK' AND status='PENDING' AND JSON_EXTRACT(payload,'$.talkId')=?",
        num(p, "talkId")
      ) == 0,
      "该签到正在审核"
    );
    if (kind.equals("ANSWER")) Api.check(
      store.count(
        "SELECT COUNT(*) FROM exhibit_reviews WHERE meeting_id=? AND kind='ANSWER' AND status='PENDING' AND JSON_EXTRACT(payload,'$.questionId')=?",
        id,
        num(p, "questionId")
      ) == 0,
      "该回答正在审核"
    );
    store.db.update(
      "INSERT INTO exhibit_reviews(meeting_id,kind,payload) VALUES(?,?,?)",
      id,
      kind,
      json.writeValueAsString(p)
    );
    store.log(auth.id(s), "展商互动提交审核", kind + ":" + id);
  }

  void validateInteraction(String kind, Map<String, Object> p, long event) {
    if (kind.equals("ANSWER")) {
      Store.word(str(p, "answer"), 1000, "回答");
      var q = store.one(
        "SELECT * FROM questions WHERE id=? AND event_id=? AND status='APPROVED' FOR UPDATE",
        num(p, "questionId"),
        event
      );
      Api.check(str(q, "answer").isBlank(), "问题已有公开回答");
      return;
    }
    Api.check(
      date(p, "ends").isAfter(date(p, "starts")),
      "结束时间须晚于开始时间"
    );
    Api.check(date(p, "ends").isAfter(LocalDateTime.now()), "互动结束时间已过");
    if (kind.equals("CHECK")) {
      var t = store.one(
        "SELECT * FROM talks WHERE id=? AND event_id=?",
        num(p, "talkId"),
        event
      );
      Api.check(
        !date(p, "starts").isBefore(
          ((LocalDateTime) t.get("starts")).minusHours(2)
        ) && !date(p, "ends").isAfter((LocalDateTime) t.get("ends")),
        "签到时间须在议程开始前2小时至结束之间"
      );
      Api.check(
        store.count(
          "SELECT COUNT(*) FROM attendance WHERE talk_id=?",
          num(p, "talkId")
        ) == 0,
        "该议程已发布签到"
      );
      Api.check(
        store.count(
          "SELECT COUNT(*) FROM exhibit_reviews WHERE kind='CHECK' AND status='PENDING' AND JSON_EXTRACT(payload,'$.talkId')=?",
          num(p, "talkId")
        ) <= 1,
        "该签到正在审核"
      );
    } else {
      Store.word(str(p, "title"), 120, "投票大标题");
      var meeting = store.one(
        "SELECT starts,ends FROM events WHERE id=?",
        event
      );
      Api.check(
        !date(p, "starts").isBefore((LocalDateTime) meeting.get("starts")) &&
          !date(p, "ends").isAfter((LocalDateTime) meeting.get("ends")),
        "投票时间须在会议时间内"
      );
      Store.word(str(p, "question"), 120, "题目");
      Object opts = p.get("options");
      Api.check(
        opts instanceof List<?> &&
          ((List<?>) opts).size() >= 2 &&
          ((List<?>) opts).size() <= 8,
        "填写2至8个选项"
      );
      var list = (List<?>) opts;
      Api.check(new HashSet<>(list).size() == list.size(), "选项不可重复");
      for (Object o : list) Store.word(String.valueOf(o), 120, "选项");
    }
  }

  @PostMapping("/exhibitor/interactions/{id}/withdraw")
  @Transactional
  public void withdrawInteraction(@PathVariable long id, HttpSession s) {
    var p = store.one("SELECT * FROM exhibit_reviews WHERE id=?", id);
    own(Store.num(p, "meeting_id"), s, true);
    p = store.one("SELECT * FROM exhibit_reviews WHERE id=? FOR UPDATE", id);
    Api.check(str(p, "status").equals("PENDING"), "仅待审核互动可撤销");
    store.db.update(
      "UPDATE exhibit_reviews SET status='WITHDRAWN' WHERE id=?",
      id
    );
    store.log(auth.id(s), "撤销互动审核", id);
  }

  record Review(boolean pass, String reason) {}

  @GetMapping("/exhibit-review")
  public Map<String, Object> reviewList(HttpSession s) {
    auth.admin(s);
    return Map.of(
      "meetings",
      store.db.queryForList(
        "SELECT m.id,m.status,m.reason,m.created,u.name,JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.title')) title,m.payload FROM exhibit_meetings m JOIN users u ON u.id=m.user_id WHERE m.status='PENDING' ORDER BY m.id DESC"
      ),
      "interactions",
      store.db.queryForList(
        "SELECT r.*,JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.title')) title,u.name FROM exhibit_reviews r JOIN exhibit_meetings m ON m.id=r.meeting_id JOIN users u ON u.id=m.user_id WHERE r.status='PENDING' ORDER BY r.id DESC"
      )
    );
  }

  @PostMapping("/exhibit-review/meetings/{id}")
  @Transactional
  public void reviewMeeting(
    @PathVariable long id,
    @RequestBody Review review,
    HttpSession s
  ) {
    auth.admin(s);
    var m = store.one(
      "SELECT * FROM exhibit_meetings WHERE id=? FOR UPDATE",
      id
    );
    Api.check(str(m, "status").equals("PENDING"), "审核状态已改变，请刷新");
    if (!review.pass()) {
      Store.word(review.reason(), 200, "驳回原因");
      store.db.update(
        "UPDATE exhibit_meetings SET status='REJECTED',reason=? WHERE id=?",
        review.reason(),
        id
      );
      store.note(
        Store.num(m, "user_id"),
        "会议「" +
          str(data(m.get("payload")), "title") +
          "」被驳回：" +
          review.reason()
      );
      store.log(auth.id(s), "驳回展商会议", id);
      return;
    }
    var d = data(m.get("payload"));
    validate(d);
    Api.check(
      date(d, "starts").isAfter(LocalDateTime.now()),
      "会议开始时间已过，请驳回后重新安排"
    );
    long event;
    if (m.get("event_id") == null) {
      store.db.update(
        "INSERT INTO events(title,place,starts,ends,capacity,content,status) VALUES(?,?,?,?,?,?,'OPEN')",
        str(d, "title"),
        str(d, "place"),
        date(d, "starts"),
        date(d, "ends"),
        num(d, "capacity"),
        str(d, "content")
      );
      event = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
      store.db.update("INSERT INTO event_info(event_id) VALUES(?)", event);
    } else {
      event = Store.num(m, "event_id");
      var old = store.one("SELECT * FROM events WHERE id=? FOR UPDATE", event);
      Api.check(
        ((LocalDateTime) old.get("starts")).isAfter(LocalDateTime.now()),
        "会议已开始，不可覆盖"
      );
      Api.check(
        num(d, "capacity") >=
          store.count(
            "SELECT COUNT(*) FROM joins WHERE event_id=? AND status='APPROVED'",
            event
          ),
        "人数不可小于已通过报名人数"
      );
      store.db.update(
        "UPDATE events SET title=?,place=?,starts=?,ends=?,capacity=?,content=?,status='OPEN' WHERE id=?",
        str(d, "title"),
        str(d, "place"),
        date(d, "starts"),
        date(d, "ends"),
        num(d, "capacity"),
        str(d, "content"),
        event
      );
    }
    store.db.update(
      "UPDATE events SET cover=?,poster=?,features=?,cover_title=?,meeting_type=?,posters=? WHERE id=?",
      str(d, "cover"),
      str(d, "poster"),
      json.writeValueAsString(d.get("features")),
      str(d, "coverTitle"),
      str(d, "meetingType"),
      json.writeValueAsString(d.get("posters")),
      event
    );
    // 保留原议程ID和关联日程；删除已有签到数据的议程不允许静默丢失。
    Set<Long> keep = new HashSet<>();
    for (var t : rows(d, "talks")) {
      long tid = t.get("id") instanceof Number ? num(t, "id") : 0;
      if (tid > 0) {
        store.one("SELECT id FROM talks WHERE id=? AND event_id=?", tid, event);
        store.db.update(
          "UPDATE talks SET title=?,speaker=?,room=?,starts=?,ends=?,tags=? WHERE id=?",
          str(t, "title"),
          str(t, "speaker"),
          str(t, "room"),
          date(t, "starts"),
          date(t, "ends"),
          str(t, "tags"),
          tid
        );
      } else {
        store.db.update(
          "INSERT INTO talks(event_id,title,speaker,room,starts,ends,tags) VALUES(?,?,?,?,?,?,?)",
          event,
          str(t, "title"),
          str(t, "speaker"),
          str(t, "room"),
          date(t, "starts"),
          date(t, "ends"),
          str(t, "tags")
        );
        tid = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        t.put("id", tid);
      }
      keep.add(tid);
    }
    for (var t : store.db.queryForList(
      "SELECT id FROM talks WHERE event_id=?",
      event
    )) {
      long tid = Store.num(t, "id");
      if (!keep.contains(tid)) {
        Api.check(
          store.count("SELECT COUNT(*) FROM attendance WHERE talk_id=?", tid) ==
            0,
          "需先处理被删除议程的签到场次"
        );
        store.db.update("DELETE FROM plans WHERE talk_id=?", tid);
        store.db.update(
          "UPDATE questions SET talk_id=NULL WHERE talk_id=?",
          tid
        );
        store.db.update("DELETE FROM replays WHERE talk_id=?", tid);
        store.db.update("DELETE FROM talks WHERE id=?", tid);
      }
    }
    // 只替换本展商提供的资料，保留参会评论及其他来源内容。
    store.db.update(
      "DELETE l FROM likes l JOIN posts p ON p.id=l.post_id WHERE p.event_id=? AND p.user_id=? AND p.kind IN ('EXHIBIT','NEWS','RESULT')",
      event,
      m.get("user_id")
    );
    store.db.update(
      "DELETE FROM posts WHERE event_id=? AND user_id=? AND kind IN ('EXHIBIT','NEWS','RESULT')",
      event,
      m.get("user_id")
    );
    store.db.update(
      "DELETE FROM files WHERE event_id=? AND user_id=?",
      event,
      m.get("user_id")
    );
    for (var p : rows(d, "materials")) {
      String kind = switch (str(p, "type")) {
        case "会议资讯" -> "NEWS";
        case "成果展示" -> "RESULT";
        default -> "EXHIBIT";
      };
      store.db.update(
        "INSERT INTO posts(event_id,user_id,kind,title,body,image,product,status) VALUES(?,?,?,?,?,?,?,'APPROVED')",
        event,
        m.get("user_id"),
        kind,
        str(p, "title"),
        str(p, "body"),
        str(p, "image"),
        str(p, "product")
      );
      for (var f : rows(p, "files")) {
        String value = str(f, "data");
        byte[] bytes = Base64.getDecoder().decode(
          value.substring(value.indexOf(',') + 1)
        );
        store.db.update(
          "INSERT INTO files(event_id,user_id,name,path,size,data) VALUES(?,?,?,?,?,?)",
          event,
          m.get("user_id"),
          str(f, "name"),
          UUID.randomUUID().toString(),
          bytes.length,
          bytes
        );
      }
    }
    store.db.update(
      "UPDATE exhibit_meetings SET status='APPROVED',event_id=?,payload=?,reason='' WHERE id=?",
      event,
      json.writeValueAsString(d),
      id
    );
    store.note(
      Store.num(m, "user_id"),
      "会议审核通过并发布：" + str(d, "title")
    );
    store.log(auth.id(s), "审核发布展商会议", id);
  }

  @PostMapping("/exhibit-review/interactions/{id}")
  @Transactional
  public void reviewInteraction(
    @PathVariable long id,
    @RequestBody Review review,
    HttpSession s
  ) {
    auth.admin(s);
    var r = store.one("SELECT * FROM exhibit_reviews WHERE id=?", id);
    var m = store.one(
      "SELECT * FROM exhibit_meetings WHERE id=? FOR UPDATE",
      r.get("meeting_id")
    );
    r = store.one("SELECT * FROM exhibit_reviews WHERE id=? FOR UPDATE", id);
    Api.check(str(r, "status").equals("PENDING"), "审核状态已改变");
    if (!review.pass()) {
      Store.word(review.reason(), 200, "驳回原因");
      store.db.update(
        "UPDATE exhibit_reviews SET status='REJECTED',reason=? WHERE id=?",
        review.reason(),
        id
      );
      store.note(
        Store.num(m, "user_id"),
        "互动申请「" + r.get("kind") + "」被驳回：" + review.reason()
      );
      store.log(auth.id(s), "驳回展商互动", id);
      return;
    }
    Api.check(
      state(m, data(m.get("payload"))).equals("进行中"),
      "会议已非进行中，不能发布互动"
    );
    long event = Store.num(m, "event_id");
    store.one(
      "SELECT id FROM events WHERE id=? AND status='OPEN' AND starts<=NOW() AND ends>NOW() FOR UPDATE",
      event
    );
    var p = data(r.get("payload"));
    String kind = str(r, "kind");
    validateInteraction(kind, p, event);
    if (kind.equals("CHECK")) store.db.update(
      "INSERT INTO attendance(talk_id,starts,ends,enabled,code) VALUES(?,?,?,TRUE,?)",
      num(p, "talkId"),
      date(p, "starts"),
      date(p, "ends"),
      UUID.randomUUID().toString()
    );
    else if (kind.equals("ANSWER")) store.db.update(
      "UPDATE questions SET answer=? WHERE id=?",
      str(p, "answer"),
      num(p, "questionId")
    );
    else {
      store.db.update(
        "INSERT INTO polls(event_id,title,starts,ends,enabled,heading) VALUES(?,?,?,?,TRUE,?)",
        event,
        str(p, "question"),
        date(p, "starts"),
        date(p, "ends"),
        str(p, "title")
      );
      long poll = store.db.queryForObject(
        "SELECT LAST_INSERT_ID()",
        Long.class
      );
      for (Object option : (List<?>) p.get("options"))
        store.db.update(
          "INSERT INTO poll_options(poll_id,label) VALUES(?,?)",
          poll,
          option
        );
      p.put("pollId", poll);
    }
    store.db.update(
      "UPDATE exhibit_reviews SET status='APPROVED',payload=? WHERE id=?",
      json.writeValueAsString(p),
      id
    );
    String label = switch (kind) {
      case "CHECK" -> "签到";
      case "POLL" -> "投票";
      default -> "回答";
    };
    store.note(
      Store.num(m, "user_id"),
      "会议「" +
        str(data(m.get("payload")), "title") +
        "」的" +
        label +
        "申请审核通过，已发布"
    );
    store.log(auth.id(s), "审核发布展商互动", id);
  }

  @PostMapping("/exhibitor/meetings/{id}/plans/{talk}")
  @Transactional
  public void plan(
    @PathVariable long id,
    @PathVariable long talk,
    HttpSession s
  ) {
    var m = own(id, s, false);
    Api.check("APPROVED".equals(m.get("status")), "会议尚未审核通过");
    store.one(
      "SELECT id FROM talks WHERE id=? AND event_id=?",
      talk,
      m.get("event_id")
    );
    store.db.update(
      "INSERT IGNORE INTO plans(user_id,talk_id) VALUES(?,?)",
      auth.id(s),
      talk
    );
  }

  @DeleteMapping("/exhibitor/meetings/{id}/plans/{talk}")
  @Transactional
  public void unplan(
    @PathVariable long id,
    @PathVariable long talk,
    HttpSession s
  ) {
    var m = own(id, s, false);
    store.one(
      "SELECT id FROM talks WHERE id=? AND event_id=?",
      talk,
      m.get("event_id")
    );
    store.db.update(
      "DELETE FROM plans WHERE user_id=? AND talk_id=?",
      auth.id(s),
      talk
    );
  }

  @GetMapping("/exhibitor/meetings/{id}/replays/{talk}")
  public org.springframework.http.ResponseEntity<org.springframework.core.io.ByteArrayResource> replay(
    @PathVariable long id,
    @PathVariable long talk,
    HttpSession s
  ) {
    var m = own(id, s, false);
    store.one(
      "SELECT id FROM talks WHERE id=? AND event_id=? AND ends<=NOW()",
      talk,
      m.get("event_id")
    );
    var r = store.one("SELECT type,data FROM replays WHERE talk_id=?", talk);
    return org.springframework.http.ResponseEntity.ok()
      .contentType(
        org.springframework.http.MediaType.parseMediaType(str(r, "type"))
      )
      .cacheControl(org.springframework.http.CacheControl.noStore())
      .body(
        new org.springframework.core.io.ByteArrayResource(
          (byte[]) r.get("data")
        )
      );
  }
}

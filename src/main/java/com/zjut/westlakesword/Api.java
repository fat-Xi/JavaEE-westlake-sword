package com.zjut.westlakesword;

import jakarta.servlet.http.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 用户、活动与报名入口。议程、内容和现场模块分别放在独立控制器中。 */
@RestController
@RequestMapping("/api")
public class Api {

  private final Store store;
  private final Auth auth;
  private final JoinService joins;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public Api(Store store, Auth auth, JoinService joins) {
    this.store = store;
    this.auth = auth;
    this.joins = joins;
  }

  @GetMapping("/events/summary")
  public Map<String, Object> summary() {
    return Map.of(
      "talks",
      store.count(
        "SELECT COUNT(*) FROM talks t JOIN events e ON e.id=t.event_id WHERE e.status='OPEN'"
      ),
      "speakers",
      store.count(
        "SELECT COUNT(DISTINCT t.speaker) FROM talks t JOIN events e ON e.id=t.event_id WHERE e.status='OPEN'"
      )
    );
  }

  record Login(String name, String password) {}

  record Profile(
    String nick,
    String phone,
    String company,
    String tags,
    String realName,
    String gender,
    String email,
    String avatar
  ) {}

  record Register(
    String name,
    String password,
    String role,
    String nick,
    String phone,
    String company,
    String tags,
    String realName,
    String gender,
    String email,
    String avatar
  ) {}

  static void validProfile(Profile p) {
    Store.word(p.nick(), 40, "网名");
    Store.word(p.realName(), 40, "真实姓名");
    check(
      p.gender() != null && Set.of("男", "女", "未设置").contains(p.gender()),
      "性别错误"
    );
    check(
      p.email() != null &&
        p.email().length() <= 120 &&
        p.email().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"),
      "邮箱格式错误"
    );
    check(
      p.phone() != null && p.phone().matches("[0-9+() -]{6,30}"),
      "电话格式错误"
    );
    check(
      p.company() != null &&
        p.company().length() <= 100 &&
        p.tags() != null &&
        p.tags().length() <= 200,
      "单位或兴趣标签过长"
    );
    // 头像仅接受受限大小的位图，不保存脚本或任意文件地址。
    check(
      p.avatar() != null &&
        (p.avatar().isEmpty() ||
          (p.avatar().length() <= 700000 &&
            p
              .avatar()
              .matches("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+"))),
      "头像需为500KB以内的PNG/JPEG/WebP图片"
    );
  }

  void saveProfile(long id, Profile p) {
    validProfile(p);
    store.db.update("INSERT IGNORE INTO profiles(user_id) VALUES(?)", id);
    store.db.update(
      "UPDATE profiles SET nick=?,phone=?,company=?,tags=?,real_name=?,gender=?,email=?,avatar=? WHERE user_id=?",
      p.nick(),
      p.phone(),
      p.company(),
      "USER".equals(
        store.one("SELECT role FROM users WHERE id=?", id).get("role")
      )
        ? p.tags()
        : "",
      p.realName(),
      p.gender(),
      p.email(),
      p.avatar(),
      id
    );
  }

  record Password(String oldPassword, String password) {}

  record Event(
    String title,
    String place,
    LocalDateTime starts,
    LocalDateTime ends,
    int capacity,
    String content,
    String status,
    String liveUrl,
    String replayUrl
  ) {}

  record Review(boolean pass, String reason) {}

  record Apply(String channel) {}

  static void check(boolean ok, String message) {
    if (!ok) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  @PostMapping("/register")
  @Transactional
  public Map<String, String> register(@RequestBody Register form) {
    check(
      form.name() != null && form.name().matches("[a-zA-Z0-9_]{3,30}"),
      "账号需为3至30位字母、数字或下划线"
    );
    check(
      form.password() != null &&
        form.password().length() >= 8 &&
        form.password().length() <= 50,
      "密码需为8至50位"
    );
    check(
      Set.of("USER", "EXHIBITOR").contains(
        form.role() == null ? "" : form.role()
      ),
      "只能注册展商或参会者"
    );
    store.db.update(
      "INSERT INTO users(name,password,role,status) VALUES(?,?,?,'PENDING')",
      form.name(),
      encoder.encode(form.password()),
      Store.word(form.role(), 20, "注册身份")
    );
    long id = Store.num(
      store.one("SELECT id FROM users WHERE name=?", form.name()),
      "id"
    );
    saveProfile(
      id,
      new Profile(
        form.nick(),
        form.phone(),
        form.company(),
        form.tags(),
        form.realName(),
        form.gender(),
        form.email(),
        form.avatar()
      )
    );
    store.log(id, "注册", form.role());
    return Map.of("message", "注册成功，请登录");
  }

  @PostMapping("/login")
  public Map<String, Object> login(
    @RequestBody Login form,
    HttpServletRequest request
  ) {
    var rows = store.db.queryForList(
      "SELECT * FROM users WHERE name=?",
      form.name()
    );
    if (
      rows.isEmpty() ||
      form.password() == null ||
      !encoder.matches(form.password(), (String) rows.get(0).get("password"))
    ) {
      throw new ResponseStatusException(
        HttpStatus.UNAUTHORIZED,
        "账号或密码错误"
      );
    }
    Api.check(!"DELETED".equals(rows.get(0).get("status")), "账户已删除");
    long id = Store.num(rows.get(0), "id");
    request.getSession().invalidate();
    HttpSession session = request.getSession(true);
    session.setAttribute("uid", id);
    store.db.update("UPDATE users SET logins=logins+1 WHERE id=?", id);
    store.log(id, "登录", "成功");
    return me(session);
  }

  @GetMapping("/me")
  public Map<String, Object> me(HttpSession session) {
    var user = store.one(
      "SELECT u.id,u.name,u.role,u.status,u.reason,COALESCE(NULLIF(p.nick,''),u.name) nick,p.avatar FROM users u LEFT JOIN profiles p ON p.user_id=u.id WHERE u.id=?",
      auth.id(session)
    );
    user.put(
      "perms",
      "ADMIN".equals(user.get("role"))
        ? List.of("event", "review", "content", "stats", "exhibit", "request")
        : store.db.queryForList(
            "SELECT code FROM perms WHERE role=?",
            String.class,
            user.get("role")
          )
    );
    return user;
  }

  @PostMapping("/logout")
  public void logout(HttpSession session) {
    session.invalidate();
  }

  @GetMapping("/profile")
  public Map<String, Object> profile(HttpSession session) {
    long id = auth.id(session);
    store.db.update("INSERT IGNORE INTO profiles(user_id) VALUES(?)", id);
    return store.one(
      "SELECT p.*,p.real_name realName,u.name,u.role,u.status,u.reason FROM profiles p JOIN users u ON u.id=p.user_id WHERE user_id=?",
      id
    );
  }

  @PutMapping("/profile")
  public void profile(@RequestBody Profile p, HttpSession session) {
    long id = auth.id(session);
    saveProfile(id, p);
    // 被驳回者补充资料后可重新申请身份审核；账号和角色不接受个人修改。
    store.db.update(
      "UPDATE users SET status='PENDING',reason='' WHERE id=? AND status='REJECTED'",
      id
    );
  }

  @PutMapping("/password")
  public void password(@RequestBody Password p, HttpSession session) {
    long id = auth.id(session);
    check(
      p.oldPassword() != null &&
        encoder.matches(
          p.oldPassword(),
          (String) store
            .one("SELECT password FROM users WHERE id=?", id)
            .get("password")
        ),
      "原密码不正确"
    );
    check(
      p.password() != null &&
        p.password().length() >= 8 &&
        p.password().length() <= 50,
      "新密码需为8至50位"
    );
    store.db.update(
      "UPDATE users SET password=? WHERE id=?",
      encoder.encode(p.password()),
      id
    );
    store.log(id, "修改密码", "成功");
    session.invalidate();
  }

  @GetMapping("/events")
  public List<Map<String, Object>> events() {
    return store.db.queryForList(
      "SELECT e.*,COALESCE(i.views,0) views FROM events e LEFT JOIN event_info i ON i.event_id=e.id WHERE e.status='OPEN' ORDER BY e.starts DESC"
    );
  }

  @GetMapping("/events/{id}")
  public Map<String, Object> detail(@PathVariable long id) {
    var event = store.one(
      "SELECT * FROM events WHERE id=? AND status='OPEN'",
      id
    );
    store.db.update(
      "INSERT INTO event_info(event_id,views) VALUES(?,1) ON DUPLICATE KEY UPDATE views=views+1",
      id
    );
    event.putAll(
      store.one("SELECT views FROM event_info WHERE event_id=?", id)
    );
    return event;
  }

  @GetMapping("/admin/events")
  public List<Map<String, Object>> adminEvents(HttpSession session) {
    auth.need(session, "event");
    return store.db.queryForList(
      "SELECT e.*,i.live_url,i.replay_url FROM events e LEFT JOIN event_info i ON e.id=i.event_id ORDER BY e.id DESC"
    );
  }

  @PostMapping("/admin/events")
  @Transactional
  public void add(@RequestBody Event e, HttpSession session) {
    auth.need(session, "event");
    validate(e);
    store.db.update(
      "INSERT INTO events(title,place,starts,ends,capacity,content,status) VALUES(?,?,?,?,?,?,?)",
      e.title(),
      e.place(),
      e.starts(),
      e.ends(),
      e.capacity(),
      e.content(),
      e.status()
    );
    long id = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    media(id, e);
    store.log(auth.id(session), "发布活动", id);
  }

  @PutMapping("/admin/events/{id}")
  @Transactional
  public void edit(
    @PathVariable long id,
    @RequestBody Event e,
    HttpSession session
  ) {
    auth.need(session, "event");
    validate(e);
    joins.edit(
      id,
      e.title(),
      e.place(),
      e.starts(),
      e.ends(),
      e.capacity(),
      e.content(),
      e.status()
    );
    media(id, e);
    store.db.update(
      "INSERT INTO notes(user_id,body) SELECT user_id,? FROM subs WHERE event_id=?",
      "你订阅的活动「" + e.title() + "」已更新",
      id
    );
    store.log(auth.id(session), "编辑活动", id);
  }

  void media(long id, Event e) {
    store.db.update(
      "INSERT INTO event_info(event_id,live_url,replay_url) VALUES(?,?,?) ON DUPLICATE KEY UPDATE live_url=?,replay_url=?",
      id,
      Store.url(e.liveUrl()),
      Store.url(e.replayUrl()),
      Store.url(e.liveUrl()),
      Store.url(e.replayUrl())
    );
  }

  void validate(Event e) {
    Store.word(e.title(), 120, "标题");
    Store.word(e.place(), 120, "地点");
    Store.word(e.content(), 20000, "说明");
    check(
      e.starts() != null && e.ends() != null && e.ends().isAfter(e.starts()),
      "结束时间必须晚于开始时间"
    );
    check(e.capacity() > 0, "容量需大于0");
    check(
      Set.of("OPEN", "CLOSED").contains(e.status() == null ? "" : e.status()),
      "活动状态错误"
    );
  }

  @PostMapping("/events/{id}/join")
  public void join(
    @PathVariable long id,
    @RequestBody(required = false) Apply body,
    HttpSession session
  ) {
    auth.member(session);
    joins.apply(auth.id(session), id, body == null ? "direct" : body.channel());
  }

  @DeleteMapping("/events/{id}/join")
  public void cancel(@PathVariable long id, HttpSession session) {
    auth.member(session);
    joins.cancel(auth.id(session), id);
  }

  @GetMapping("/joins")
  public List<Map<String, Object>> mine(HttpSession session) {
    return store.db.queryForList(
      "SELECT j.*,e.title,e.starts,e.place,i.channel,(SELECT k.checked FROM tickets k WHERE k.join_id=j.id) legacy_checked FROM joins j JOIN events e ON e.id=j.event_id LEFT JOIN join_info i ON i.join_id=j.id WHERE j.user_id=? ORDER BY j.id DESC",
      auth.id(session)
    );
  }

  @GetMapping("/events/{id}/access")
  public Map<String, String> access(
    @PathVariable long id,
    HttpSession session
  ) {
    auth.approved(session, id);
    return Map.of("message", "可以使用参会服务");
  }

  @GetMapping("/admin/joins")
  public List<Map<String, Object>> list(HttpSession session) {
    auth.need(session, "event");
    return store.db.queryForList(
      "SELECT j.*,u.name,e.title,i.channel FROM joins j JOIN users u ON u.id=j.user_id JOIN events e ON e.id=j.event_id LEFT JOIN join_info i ON i.join_id=j.id ORDER BY j.id DESC"
    );
  }

  @PostMapping("/admin/joins/{id}")
  public void review(
    @PathVariable long id,
    @RequestBody Review r,
    HttpSession session
  ) {
    auth.need(session, "event");
    joins.review(id, r.pass(), r.reason());
    store.log(auth.id(session), "报名审核", id + ":" + r.pass());
  }

  @GetMapping("/subs")
  public List<Long> subs(HttpSession session) {
    return store.db.queryForList(
      "SELECT event_id FROM subs WHERE user_id=?",
      Long.class,
      auth.id(session)
    );
  }

  @PostMapping("/events/{id}/sub")
  public void subscribe(@PathVariable long id, HttpSession session) {
    auth.member(session);
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN'", id);
    store.db.update(
      "INSERT IGNORE INTO subs(user_id,event_id) VALUES(?,?)",
      auth.id(session),
      id
    );
  }

  @DeleteMapping("/events/{id}/sub")
  public void unsubscribe(@PathVariable long id, HttpSession session) {
    store.db.update(
      "DELETE FROM subs WHERE user_id=? AND event_id=?",
      auth.id(session),
      id
    );
  }

  @GetMapping("/notes")
  public List<Map<String, Object>> notes(HttpSession session) {
    return store.db.queryForList(
      "SELECT * FROM notes WHERE user_id=? ORDER BY id DESC LIMIT 100",
      auth.id(session)
    );
  }

  @PostMapping("/notes/read")
  public void read(HttpSession session) {
    store.db.update(
      "UPDATE notes SET seen=TRUE WHERE user_id=?",
      auth.id(session)
    );
  }
}

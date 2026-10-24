package com.zjut.westlakesword;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 本地课程演示初始化。只补不存在的账号，不重置任何已有密码。 */
@Component
@org.springframework.core.annotation.Order(0)
public class Setup implements CommandLineRunner {

  private final Store store;
  private final boolean demo;
  private final Path upload;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public Setup(
    Store store,
    @Value("${app.demo}") boolean demo,
    @Value("${app.upload}") String upload
  ) {
    this.store = store;
    this.demo = demo;
    this.upload = Path.of(upload);
  }

  long user(String name, String role, String password) {
    if (store.count("SELECT COUNT(*) FROM users WHERE name=?", name) == 0) {
      store.db.update(
        "INSERT INTO users(name,password,role) VALUES(?,?,?)",
        name,
        encoder.encode(password),
        role
      );
    }
    return Store.num(
      store.one("SELECT id FROM users WHERE name=?", name),
      "id"
    );
  }

  long event(
    String title,
    LocalDateTime start,
    LocalDateTime end,
    int capacity
  ) {
    store.db.update(
      "INSERT INTO events(title,place,starts,ends,capacity,content,status) VALUES(?,?,?,?,?,?,'OPEN')",
      title,
      "杭州 · 西湖交流中心（演示）",
      start,
      end,
      capacity,
      "课程设计演示活动，并非真实大会安排。围绕数字安全、云安全与数据治理开展主题交流。\n报名通过后可安排个人日程、参与提问投票、下载资料并领取签到码。"
    );
    long id = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    store.db.update(
      "INSERT INTO event_info(event_id,live_url,replay_url) VALUES(?,?,?)",
      id,
      "https://www.huodongxing.com/conference",
      "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"
    );
    return id;
  }

  @Override
  @Transactional
  public void run(String... args) throws Exception {
    String password = System.getenv("ADMIN_PASSWORD");
    if (password != null && password.length() >= 8) user(
      "admin",
      "ADMIN",
      password
    );
    if (
      store.count("SELECT COUNT(*) FROM app_meta WHERE name='roles-v3'") == 0
    ) {
      store.db.update(
        "UPDATE users SET role='ADMIN' WHERE role IN ('OPS','REVIEWER')"
      );
      store.db.update("DELETE FROM perms");
      store.db.update(
        "INSERT INTO perms(role,code) VALUES('STAFF','checkin'),('EXHIBITOR','exhibit'),('EXHIBITOR','request')"
      );
      store.db.update("INSERT INTO app_meta(name) VALUES('roles-v3')");
    }
    if (
      store.count(
        "SELECT COUNT(*) FROM app_meta WHERE name='content-owner-v4'"
      ) == 0
    ) {
      // 只修复旧初始化器的演示内容归属，不改写用户真实投稿。
      store.db.update(
        "UPDATE posts p JOIN users u ON u.id=p.user_id SET p.user_id=(SELECT x.id FROM users x WHERE x.name='demo_admin' LIMIT 1) WHERE u.name IN ('demo_user','demo_pending','demo_checked') AND p.kind IN ('NEWS','RESULT') AND p.title LIKE '【演示】%' AND EXISTS(SELECT 1 FROM users a WHERE a.name='demo_admin')"
      );
      store.db.update("INSERT INTO app_meta(name) VALUES('content-owner-v4')");
    }
    if (
      !demo ||
      store.count("SELECT COUNT(*) FROM app_meta WHERE name='demo-v2'") != 0
    ) return;
    String pass = "Demo12345!";
    long admin = user("demo_admin", "ADMIN", pass);

    long staff = user("demo_staff", "STAFF", pass);
    long exhibit = user("demo_exhibit", "EXHIBITOR", pass);

    long member = user("demo_user", "USER", pass);
    long pending = user("demo_pending", "USER", pass);
    long checked = user("demo_checked", "USER", pass);
    for (long id : List.of(member, pending, checked)) {
      store.db.update(
        "INSERT IGNORE INTO profiles(user_id,nick,company,tags) VALUES(?,?,?,?)",
        id,
        "演示参会者",
        "课程设计小组",
        "数据安全,云安全"
      );
    }
    LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
    long current = event(
      "【演示】数字安全创新交流日",
      now.minusHours(1),
      now.plusHours(6),
      120
    );
    long next = event(
      "【演示】云安全与数据治理论坛",
      now.plusDays(7),
      now.plusDays(7).plusHours(8),
      80
    );
    long past = event(
      "【演示】安全技术公开课 · 回顾",
      now.minusDays(7),
      now.minusDays(7).plusHours(3),
      100
    );
    for (long e : List.of(current, next)) {
      LocalDateTime base = e == current ? now.plusMinutes(20) : now.plusDays(7);
      for (int i = 0; i < 3; i++) {
        LocalDateTime start = i == 2 ? base.plusHours(2) : base;
        store.db.update(
          "INSERT INTO talks(event_id,title,speaker,room,starts,ends,tags) VALUES(?,?,?,?,?,?,?)",
          e,
          List.of("数据安全实践", "云原生防护", "安全人才交流").get(i),
          "演示讲师" + (i + 1),
          "分会场" + (i + 1),
          start,
          start.plusHours(1),
          i == 0 ? "数据安全" : "云安全"
        );
      }
    }
    for (long u : List.of(member, pending, checked)) {
      store.db.update(
        "INSERT INTO joins(user_id,event_id,status) VALUES(?,?,?)",
        u,
        current,
        u == pending ? "PENDING" : "APPROVED"
      );
      long j = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
      store.db.update(
        "INSERT INTO join_info(join_id,channel) VALUES(?,?)",
        j,
        u == member ? "share" : "direct"
      );
      if (u == checked) store.db.update(
        "INSERT INTO tickets(join_id,token,checked,staff_id) VALUES(?,?,NOW(),?)",
        j,
        UUID.randomUUID().toString(),
        staff
      );
      store.note(u, "欢迎体验演示活动，所有演示内容均非真实大会数据。");
    }
    for (String kind : List.of(
      "NEWS",
      "RESULT",
      "EXHIBIT",
      "COMMENT",
      "WALL"
    )) {
      String title = switch (kind) {
        case "NEWS" -> "【演示】参会指南与交通提醒";
        case "RESULT" -> "【演示】安全实验室成果展示";
        case "EXHIBIT" -> "【演示】青禾安全展台";
        case "COMMENT" -> "期待与大家交流数据保护经验";
        default -> "我在西湖论剑等你！";
      };
      store.db.update(
        "INSERT INTO posts(event_id,user_id,kind,title,body,product,status) VALUES(?,?,?,?,?,?,'APPROVED')",
        current,
        kind.equals("EXHIBIT")
          ? exhibit
          : Set.of("NEWS", "RESULT").contains(kind)
            ? admin
            : member,
        kind,
        title,
        "这是一条课程设计演示内容。欢迎体验展商展示、资讯发布与评论互动，实际项目中由管理员或展商提交，经管理员审核后公开。",
        "演示产品：数据安全管理平台"
      );
    }
    store.db.update(
      "INSERT INTO questions(event_id,user_id,body,answer,status) VALUES(?,?,?,?,'APPROVED')",
      current,
      member,
      "如何在团队项目里落实最小权限？",
      "演示答复：按角色配置接口权限，并在后端检查用户身份和资源归属。"
    );
    long q = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    store.db.update(
      "INSERT INTO votes(user_id,question_id) VALUES(?,?)",
      checked,
      q
    );
    Files.createDirectories(upload);
    String file = "demo-guide.txt";
    Files.writeString(
      upload.resolve(file),
      "西湖论剑课程设计演示资料\n这是演示下载文件，并非真实大会资料。\n建议体验：报名、审核、日程、投票、签到。\n"
    );
    store.db.update(
      "INSERT INTO files(event_id,user_id,name,path,size) VALUES(?,?,?,?,?)",
      current,
      admin,
      "演示参会指南.txt",
      file,
      Files.size(upload.resolve(file))
    );
    store.log(admin, "初始化演示数据", "demo-v2，仅执行一次");
    store.db.update("INSERT INTO app_meta(name) VALUES('demo-v2')");
  }
}

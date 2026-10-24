package com.zjut.westlakesword;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 一次性演示补充，不修改真实账号、不重置密码、不反复延长测试会议。 */
@Component
@Order(20)
public class DemoV5 implements CommandLineRunner {

  private final Store store;
  private final boolean demo;

  public DemoV5(Store store, @Value("${app.demo}") boolean demo) {
    this.store = store;
    this.demo = demo;
  }

  long id() {
    return store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  @Transactional
  public void run(String... args) throws Exception {
    if (
      !demo ||
      store.count("SELECT COUNT(*) FROM app_meta WHERE name='demo-v5'") > 0
    ) return;
    if (
      store.count("SELECT COUNT(*) FROM users WHERE name='demo_exhibit'") == 0
    ) return;
    for (var row : store.db.queryForList(
      "SELECT id,name,role FROM users WHERE name IN ('demo_admin','demo_staff','demo_exhibit','demo_user','demo_pending','demo_checked')"
    )) {
      long uid = Store.num(row, "id");
      String role = (String) row.get("role");
      String nick = switch (role) {
        case "ADMIN" -> "会享管理员";
        case "STAFF" -> "现场小助手";
        case "EXHIBITOR" -> "西湖安全展商";
        default -> "参会小伙伴";
      };
      var image = new java.awt.image.BufferedImage(
        160,
        160,
        java.awt.image.BufferedImage.TYPE_INT_RGB
      );
      var g = image.createGraphics();
      g.setColor(new java.awt.Color(210, 231, 228));
      g.fillRect(0, 0, 160, 160);
      g.setColor(new java.awt.Color(40, 103, 112));
      g.fillOval(55, 28, 50, 50);
      g.fillRoundRect(30, 85, 100, 70, 40, 40);
      g.dispose();
      var bytes = new java.io.ByteArrayOutputStream();
      javax.imageio.ImageIO.write(image, "png", bytes);
      String avatar =
        "data:image/png;base64," +
        Base64.getEncoder().encodeToString(bytes.toByteArray());
      store.db.update("INSERT IGNORE INTO profiles(user_id) VALUES(?)", uid);
      store.db.update(
        "UPDATE profiles SET nick=IF(nick='',?,nick),real_name=IF(real_name='',?,real_name),gender=IF(gender='未设置','男',gender),email=IF(email='',?,email),phone=IF(phone='','00000000000',phone),company=IF(company='','课程设计演示团队',company),avatar=IF(avatar IS NULL OR avatar='',?,avatar),tags=IF(?='USER',tags,'') WHERE user_id=?",
        nick,
        "演示" + uid,
        row.get("name") + "@example.com",
        avatar,
        role,
        uid
      );
    }
    long vendor = Store.num(
      store.one("SELECT id FROM users WHERE name='demo_exhibit'"),
      "id"
    );
    store.db.update(
      "UPDATE posts p JOIN users u ON u.id=p.user_id SET p.user_id=? WHERE u.name IN ('demo_admin','demo_user','demo_pending','demo_checked') AND p.kind IN ('NEWS','RESULT','EXHIBIT') AND p.title LIKE '【演示】%'",
      vendor
    );
    LocalDateTime start = LocalDateTime.now()
        .minusMinutes(10)
        .withSecond(0)
        .withNano(0),
      end = start.plusYears(1);
    store.db.update(
      "INSERT INTO events(title,place,starts,ends,capacity,content,status) VALUES(?,?,?,?,?,?,'OPEN')",
      "【测试】全年签到体验会议",
      "杭州 · 测试会场",
      start,
      end,
      500,
      "专用于课程演示与签到测试，非真实会议。会议、议程与签到窗口均持续一年。使用 demo_user 出示凭证，demo_staff 核销；demo_checked 已签到，demo_pending 报名待审核。"
    );
    long event = id();
    store.db.update("INSERT INTO event_info(event_id) VALUES(?)", event);
    store.db.update(
      "INSERT INTO talks(event_id,title,speaker,room,starts,ends,tags) VALUES(?,?,?,?,?,?,?)",
      event,
      "全年签到测试议程",
      "演示讲师",
      "测试签到台",
      start,
      end,
      "数据安全"
    );
    long talk = id();
    store.db.update(
      "INSERT INTO attendance(talk_id,starts,ends,enabled,code) VALUES(?,?,?,TRUE,?)",
      talk,
      start,
      end,
      UUID.randomUUID().toString()
    );
    long session = id();
    long staff = Store.num(
      store.one("SELECT id FROM users WHERE name='demo_staff'"),
      "id"
    );
    for (String name : List.of("demo_user", "demo_checked", "demo_pending")) {
      long uid = Store.num(
        store.one("SELECT id FROM users WHERE name=?", name),
        "id"
      );
      store.db.update(
        "INSERT INTO joins(user_id,event_id,status) VALUES(?,?,?)",
        uid,
        event,
        name.equals("demo_pending") ? "PENDING" : "APPROVED"
      );
      long join = id();
      if (!name.equals("demo_pending")) store.db.update(
        "INSERT INTO tickets(join_id,token) VALUES(?,?)",
        join,
        UUID.randomUUID().toString()
      );
      if (name.equals("demo_checked")) store.db.update(
        "INSERT INTO marks(session_id,user_id,staff_id) VALUES(?,?,?)",
        session,
        uid,
        staff
      );
    }
    store.db.update(
      "INSERT INTO polls(event_id,title,starts,ends) VALUES(?,?,?,?)",
      event,
      "下次更想参与哪个安全主题？",
      start,
      end
    );
    long poll = id();
    for (String option : List.of("云安全实践", "数据治理", "安全开发"))
      store.db.update(
        "INSERT INTO poll_options(poll_id,label) VALUES(?,?)",
        poll,
        option
      );
    store.db.update(
      "INSERT INTO requests(user_id,title,place,starts,ends,capacity,contact,content,news,result,exhibit,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING')",
      vendor,
      "【待审核演示】数据安全协作交流会",
      "杭州 · 创新交流厅",
      end.plusDays(7),
      end.plusDays(7).plusHours(4),
      120,
      "演示联系人 / demo_exhibit@example.com",
      "申请半天主题会议，需投影、签到台及交流展示区。",
      "展商拟邀请安全开发团队交流实践。",
      "演示成果：安全开发流程工具与展示材料。",
      "课程演示展商，提供数据安全产品介绍。"
    );
    long request = id();
    store.db.update(
      "INSERT INTO request_history(request_id,user_id,action) VALUES(?,?,?)",
      request,
      vendor,
      "提交待审核演示申请（含资讯、成果与展商资料）"
    );
    store.db.update("INSERT INTO app_meta(name) VALUES('demo-v5')");
  }
}

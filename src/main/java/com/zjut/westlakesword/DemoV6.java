package com.zjut.westlakesword;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 可重复启动的样例补充：仅新增演示记录，不回写真实账号或重置审核结果。 */
@Component
@Order(30)
public class DemoV6 implements CommandLineRunner {

  private final Store store;
  private final Setup setup;
  private final boolean demo;

  public DemoV6(Store store, Setup setup, @Value("${app.demo}") boolean demo) {
    this.store = store;
    this.setup = setup;
    this.demo = demo;
  }

  long id() {
    return store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  @Transactional
  public void run(String... args) throws Exception {
    if (
      !demo ||
      store.count("SELECT COUNT(*) FROM app_meta WHERE name='demo-v6'") > 0 ||
      store.count("SELECT COUNT(*) FROM users WHERE name='demo_exhibit'") == 0
    ) return;
    long vendor = Store.num(
      store.one("SELECT id FROM users WHERE name='demo_exhibit'"),
      "id"
    );
    long member = Store.num(
      store.one("SELECT id FROM users WHERE name='demo_user'"),
      "id"
    );
    var picture = new java.awt.image.BufferedImage(
      960,
      480,
      java.awt.image.BufferedImage.TYPE_INT_RGB
    );
    var g = picture.createGraphics();
    g.setRenderingHint(
      java.awt.RenderingHints.KEY_ANTIALIASING,
      java.awt.RenderingHints.VALUE_ANTIALIAS_ON
    );
    g.setPaint(
      new java.awt.GradientPaint(
        0,
        0,
        new java.awt.Color(32, 102, 111),
        960,
        480,
        new java.awt.Color(158, 197, 175)
      )
    );
    g.fillRect(0, 0, 960, 480);
    g.setColor(new java.awt.Color(216, 237, 219));
    g.setStroke(new java.awt.BasicStroke(12));
    g.drawOval(690, -60, 350, 350);
    g.drawOval(630, -120, 470, 470);
    g.setColor(java.awt.Color.WHITE);
    g.setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.BOLD, 46));
    g.drawString("数据安全协作交流会", 55, 180);
    g.setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 24));
    g.drawString("WESTLAKE · CONNECT / 展商申请配图", 58, 238);
    g.drawString("课程设计测试样例 · 非真实会议海报", 58, 390);
    g.dispose();
    var buffer = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(picture, "png", buffer);
    String image =
      "data:image/png;base64," +
      Base64.getEncoder().encodeToString(buffer.toByteArray());
    var pending = store.db.queryForList(
      "SELECT id FROM requests WHERE user_id=? AND title='【待审核演示】数据安全协作交流会' AND status='PENDING' ORDER BY id DESC",
      vendor
    );
    long request;
    if (pending.isEmpty()) {
      var start = LocalDateTime.now()
        .plusDays(14)
        .withHour(9)
        .withMinute(0)
        .withSecond(0)
        .withNano(0);
      store.db.update(
        "INSERT INTO requests(user_id,title,place,starts,ends,capacity,contact,content,news,result,exhibit,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING')",
        vendor,
        "【待审核演示】附图附件办会申请",
        "杭州 · 交流厅",
        start,
        start.plusHours(4),
        120,
        "演示展商 / demo_exhibit@example.com",
        "申请数据安全交流会议，含海报与场地准备清单。",
        "测试资讯：分享安全开发实践。",
        "测试成果：数据治理展示方案。",
        "课程演示展商资料。"
      );
      request = id();
    } else request = Store.num(pending.get(0), "id");
    store.db.update(
      "UPDATE requests SET image=? WHERE id=? AND (image IS NULL OR image='')",
      image,
      request
    );
    String name = "会议筹备与资料清单-演示.txt";
    byte[] data =
      "【课程设计演示附件】\n会议：数据安全协作交流会\n1. 场地：120个座位、讲台、投影和麦克风。\n2. 议程：开场、主题分享、成果交流、互动讨论。\n3. 资料：会议海报、展商介绍、成果说明。\n4. 签到：由管理员发布场次，工作人员核销个人票据。\n本文件仅用于测试附件预览、下载和审核后建会同步。\n".getBytes(
        StandardCharsets.UTF_8
      );
    store.db.update(
      "INSERT INTO request_files(request_id,name,path,size,data) VALUES(?,?,?,?,?)",
      request,
      name,
      "demo-v6-request.txt",
      data.length,
      data
    );
    store.db.update(
      "INSERT INTO request_history(request_id,user_id,action) VALUES(?,?,?)",
      request,
      vendor,
      "补充课程演示配图和会议筹备附件，等待管理员审核"
    );
    for (var event : store.db.queryForList(
      "SELECT id FROM events WHERE status='OPEN' AND (title='【测试】全年签到体验会议' OR title='【演示】数字安全创新交流日')"
    )) {
      long eventId = Store.num(event, "id");
      for (String kind : new String[] { "COMMENT", "WALL" })
        store.db.update(
          "INSERT INTO posts(event_id,user_id,kind,title,body,status) VALUES(?,?,?,?,?,'APPROVED')",
          eventId,
          member,
          kind,
          kind.equals("COMMENT")
            ? "【演示评论】期待安全实践交流"
            : "【演示签名】在会享留下足迹",
          kind.equals("COMMENT")
            ? "希望在这场交流中听到更多数据安全和开发实践案例，欢迎大家分享经验。这是用于展示评论列表的测试内容。"
            : "相聚西湖，连接每一种可能。——课程演示参会者"
        );
    }
    // demo_wait是身份待审核；demo_pending仍是身份通过、会议报名待审核，两者不要混淆。
    if (store.count("SELECT COUNT(*) FROM users WHERE name='demo_wait'") == 0) {
      long uid = setup.user("demo_wait", "USER", "Demo12345!");
      store.db.update("UPDATE users SET status='PENDING' WHERE id=?", uid);
      String avatar = (String) store
        .one("SELECT avatar FROM profiles WHERE user_id=?", member)
        .get("avatar");
      store.db.update(
        "INSERT INTO profiles(user_id,nick,real_name,gender,email,phone,company,tags,avatar) VALUES(?,?,?,?,?,?,?,?,?)",
        uid,
        "待审核体验用户",
        "演示待审用户",
        "未设置",
        "demo_wait@example.com",
        "00000000000",
        "课程设计演示团队",
        "数据安全",
        avatar
      );
      store.note(
        uid,
        "注册资料已提交，请等待管理员审核身份。通过前可在个人主页完善资料。"
      );
      store.log(uid, "注册", "演示：身份待审核");
    }
    store.db.update("INSERT INTO app_meta(name) VALUES('demo-v6')");
  }
}

package com.zjut.westlakesword;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 将原型的五个体验用例落为真实记录；只初始化一次，不覆盖原有会议或用户。 */
@Component
@Order(40)
public class PrototypeDemo implements CommandLineRunner {
  private final Store store;
  private final boolean demo;
  public PrototypeDemo(Store store, @Value("${app.demo}") boolean demo) {this.store=store;this.demo=demo;}
  private long id(){return store.db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
  @Transactional
  public void run(String... args){
    if(!demo || store.count("SELECT COUNT(*) FROM app_meta WHERE name='prototype-cases'")>0)return;
    if(store.count("SELECT COUNT(*) FROM users WHERE name='demo_user'")==0)return;
    long user=Store.num(store.one("SELECT id FROM users WHERE name='demo_user'"),"id");
    long other=Store.num(store.one("SELECT id FROM users WHERE name='demo_checked'"),"id");
    long vendor=Store.num(store.one("SELECT id FROM users WHERE name='demo_exhibit'"),"id");
    var now=LocalDateTime.now().withNano(0);
    String[] names={"西湖论剑 · 数字安全未来峰会","AI 原生应用与可信计算论坛","云上共生 · 企业安全实践","行业交流会 · 报名待审核示例","现场签到体验日 · 正在签到"};
    for(int i=0;i<names.length;i++){
      var start=i==1?now.plusDays(8):i==2?now.minusDays(5):now.minusHours(3);
      var end=i==1?now.plusDays(9):i==2?now.minusDays(4):now.plusYears(1);
      store.db.update("INSERT INTO events(title,place,starts,ends,capacity,content) VALUES(?,?,?,?,?,?)",names[i],"杭州 / 线上",start,end,300,"主办方介绍、参会须知、交通地址、联系方式及海报文案由展商提交，管理员审核发布。");
      long event=id();store.db.update("INSERT INTO event_info(event_id,views) VALUES(?,0)",event);
      store.db.update("INSERT INTO joins(user_id,event_id,status) VALUES(?,?,?)",user,event,i==3?"PENDING":"APPROVED");
      if(i==3)continue;
      store.db.update("INSERT INTO joins(user_id,event_id,status) VALUES(?,?,'APPROVED')",other,event);
      String[] titles={"开幕主旨","数据安全实践","AI 治理圆桌"};
      for(int j=0;j<3;j++){
        var ts=i==1||i==2?start.plusHours(j*2):j==0?now.minusHours(3):j==1?now.minusHours(1):now.plusDays(1);
        var te=i==1||i==2?ts.plusHours(2):j==0?now.minusHours(1):j==1?now.plusDays(1):end;
        store.db.update("INSERT INTO talks(event_id,title,speaker,room,starts,ends,tags) VALUES(?,?,?,?,?,?,?)",event,titles[j],"行业专家 "+(j+1),"A"+(j+1)+" 会场",ts,te,"数据安全,AI");
        long talk=id();if(i!=1&&i!=2&&j==1)store.db.update("INSERT INTO attendance(talk_id,starts,ends,enabled,code) VALUES(?,?,?,TRUE,?)",talk,ts,te,UUID.randomUUID().toString());
      }
      if(i!=0)continue;
      for(String title:new String[]{"可信计算实验室","数据治理平台","云安全实践中心"})store.db.update("INSERT INTO posts(event_id,user_id,kind,title,body,status) VALUES(?,?,'EXHIBIT',?,?,'APPROVED')",event,vendor,title,"展商提交并通过审核的介绍、成果及相关资料。");
      String[] comments={"关于隐私保护的实践非常有启发，期待更多真实案例。","讲师对风险建模的解释很清楚！"};
      for(int j=0;j<2;j++)store.db.update("INSERT INTO posts(event_id,user_id,kind,title,body,product,status) VALUES(?,?,'COMMENT','会议评论',?,?,'APPROVED')",event,j==0?user:other,comments[j],j==0?"#数据安全实践 #超赞 👍":"#开幕主旨");
      String[] signs={"让每一次连接，更可信。","相聚西湖，一起向未来！","在交流中，看见新可能","保持好奇，持续创造。"};
      for(int j=0;j<4;j++)store.db.update("INSERT INTO posts(event_id,user_id,kind,title,body,anonymous,font,status) VALUES(?,?,'WALL','参会签名',?,?,'serif','APPROVED')",event,j==0?user:other,signs[j],j==1);
      store.db.update("INSERT INTO questions(event_id,user_id,body,answer,status) VALUES(?,?,?,?, 'APPROVED')",event,other,"如何在开发阶段开展隐私风险评估？","建议从数据分级、权限边界及日志脱敏开始，分阶段建立验证清单。");
      store.db.update("INSERT INTO questions(event_id,user_id,body,answer,status) VALUES(?,?,?,?, 'APPROVED')",event,user,"有没有适合课程项目的实践资料？","我们会在展商资料中提供入门实践说明，可前往展商内容页查看。");
      String[] questions={"你最想深入了解哪个技术方向？","你更喜欢哪种交流形式？","后续内容希望采用什么时长？"};
      String[][] options={{"数据安全实践","AI 治理与可信应用","云端安全架构"},{"案例拆解与实战","专家圆桌","小组工作坊"},{"30分钟精讲","60分钟专题","半天深度课程"}};
      for(int j=0;j<3;j++){store.db.update("INSERT INTO polls(event_id,title,starts,ends) VALUES(?,?,?,?)",event,questions[j],start,end);long poll=id();for(String option:options[j])store.db.update("INSERT INTO poll_options(poll_id,label) VALUES(?,?)",poll,option);}
    }
    store.db.update("INSERT INTO app_meta(name) VALUES('prototype-cases')");
  }
}

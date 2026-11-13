package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.bind.annotation.*;

/** 展商专用检索上下文，不复用参会者私有数据接口。 */
@RestController
@RequestMapping("/api/exhibitor")
public class ExhibitAi {

  final ExhibitApi exhibit;
  final AiModelClient client;
  final Set<Long> busy = ConcurrentHashMap.newKeySet();
  final Semaphore slots = new Semaphore(4);

  public ExhibitAi(ExhibitApi exhibit, AiModelClient client) {
    this.exhibit = exhibit;
    this.client = client;
  }

  record Ask(String question) {}

  @PostMapping("/ai")
  public Map<String, Object> ask(@RequestBody Ask input, HttpSession s) {
    exhibit.owner(s);
    long user = exhibit.auth().id(s);
    String question = Store.word(input.question(), 500, "问题");
    Api.check(busy.add(user), "已有问题正在处理中");
    boolean acquired = slots.tryAcquire();
    try {
      Api.check(acquired, "问答服务繁忙，请稍后重试");
      if (
        question.matches(".*(其他展商|别人的|手机号|密码|参会者名单).*")
      ) return Map.of(
        "mode",
        "refused",
        "answer",
        "只能查询你名下的会议与汇总数据，不能访问其他展商或参会者的隐私资料。"
      );
      var meetings = exhibit.list(s);
      // 数据型问题直接从授权记录生成，模型不参与数字、审核结果或问题内容的推断。
      var matched = meetings
        .stream()
        .filter(m -> question.contains(String.valueOf(m.get("title"))))
        .toList();
      var selected = matched.isEmpty() ? meetings : matched;
      String direct = facts(question, selected);
      if (direct != null) {
        exhibit.owner(s);
        return Map.of(
          "mode",
          "platform",
          "answer",
          direct,
          "sources",
          List.of()
        );
      }
      StringBuilder info = new StringBuilder(
        "这里只提供办会操作规则，未提供的问题不应推断具体会议状态或统计数字。"
      );
      String guide =
        "你是展商办会助手。只提供当前展商会议发布、审核、资料、议程及互动管理建议，不执行修改。会议基础信息含封面、可选封面标题、会议类型、1至8张轮播海报、名称、地点、起止时间和人数；详细信息含介绍与三个亮点，至少一个完整议程。资料分展商介绍、会议资讯、成果展示。会议修改须重新审核。进行中可提交签到、投票和回答，均经管理员审批才公开；待审核可撤销。展商发布签到，工作人员核销。报名、个人兴趣推荐属于参会者服务。签到累计是跨议程记录，非去重人数。";
      var sources = List.of(
        new AiKnowledge.Source("EX_GUIDE", "guide", "展商办会规则", guide, "/"),
        new AiKnowledge.Source(
          "EX_OWN",
          "data",
          "本人会议汇总",
          info.isEmpty() ? "你目前没有会议。" : info.toString(),
          "/"
        )
      );
      String answer;
      String mode = "platform";
      if (client.configured()) {
        try {
          var result = client.answerExhibitor(question, sources);
          answer = result.answerable()
            ? result.answer()
            : "我可以帮助处理会议发布、审核与互动相关问题，请补充具体需求。";
          mode = "model";
        } catch (AiModelClient.Unavailable e) {
          answer = guide;
          mode = "faq";
        }
      } else {
        answer = guide;
        mode = "faq";
      }
      exhibit.owner(s);
      return Map.of("mode", mode, "answer", answer, "sources", sources);
    } finally {
      busy.remove(user);
      if (acquired) slots.release();
    }
  }

  /** 固定统计口径；审核进度与会议运行状态分开，不把“已结束”误报为驳回。 */
  String facts(String question, List<Map<String, Object>> meetings) {
    String q = question.replaceAll("\\s+", "");
    boolean progress = q.matches(
      ".*(审核|审查|审批|审理|进度|通过了吗|通过没).*"
    );
    boolean questions = q.matches(
      ".*(待回答|带回答|待回复|未回答|未回复|哪些问题|什么问题|有人提问).*"
    );
    boolean check = q.matches(".*(签到|核销|到场).*");
    boolean poll = q.matches(".*(投票|票数|票数结果).*");
    if (
      !(progress || questions || check || poll || q.contains("我的会议"))
    ) return null;
    if (meetings.isEmpty()) return "你目前没有可查询的会议。";
    StringBuilder out = new StringBuilder("以下为你名下会议的当前记录。\n");
    if (progress) {
      out.append("【会议审核进度】\n");
      for (var m : meetings) {
        String status = String.valueOf(m.get("status"));
        out.append("• ").append(m.get("title")).append("：");
        if (Set.of("待审核", "已驳回", "已撤销").contains(status)) out.append(
          status
        );
        else out.append("审核已通过，会议").append(status);
        if (!exhibit.str(m, "reason").isBlank()) out
          .append("；原因：")
          .append(m.get("reason"));
        out.append("。\n");
        for (String key : List.of("checks", "polls", "questions")) {
          long pending = exhibit
            .rows(m, key)
            .stream()
            .filter(r -> "待审核".equals(r.get("status")))
            .count();
          if (pending > 0) out
            .append("  另有")
            .append(pending)
            .append(
              switch (key) {
                case "checks" -> "个签到";
                case "polls" -> "个投票";
                default -> "条回答";
              }
            )
            .append("待审核。\n");
        }
      }
      out.append("待审核不代表被拒绝，系统未提供预计审批时间。\n");
    }
    if (questions) {
      out.append("【待回答问题】\n");
      int total = 0,
        pending = 0;
      for (var m : meetings)
        for (var r : exhibit.rows(m, "questions")) {
          if ("待审核".equals(r.get("status"))) pending++;
          if (!"待回答".equals(r.get("status"))) continue;
          total++;
          out
            .append("• ")
            .append(m.get("title"))
            .append("：")
            .append(r.get("body"));
          var talks = exhibit.rows(m, "talks");
          int i = ((Number) r.getOrDefault("talk", 0)).intValue();
          if (r.get("talk_id") != null && i >= 0 && i < talks.size()) out
            .append("（议程：")
            .append(talks.get(i).get("title"))
            .append("）");
          out.append("\n");
        }
      if (total == 0) out.append("当前没有待回答的公开问题。\n");
      else out
        .append("共")
        .append(total)
        .append("条待回答问题，可进入对应会议的“互动 → 回答问题”处理。\n");
      if (pending > 0) out
        .append("另有")
        .append(pending)
        .append("条回答已提交，正在等待审核，不计入待回答。\n");
    }
    if (check) {
      out.append("【签到结果】\n");
      for (var m : meetings) {
        out.append("• ").append(m.get("title")).append("：\n");
        var rows = exhibit.rows(m, "checks");
        if (rows.isEmpty()) out.append("  暂未发布签到场次。\n");
        for (var c : rows) {
          var talks = exhibit.rows(m, "talks");
          int i = ((Number) c.getOrDefault("talk", 0)).intValue();
          String title =
            i >= 0 && i < talks.size()
              ? String.valueOf(talks.get(i).get("title"))
              : "对应议程";
          out.append("  ").append(title).append("：").append(c.get("status"));
          if ("已通过".equals(c.get("status"))) out
            .append("，已核销 ")
            .append(c.getOrDefault("count", 0))
            .append(" 人；窗口 ")
            .append(c.get("starts"))
            .append(" — ")
            .append(c.get("ends"));
          out.append("。\n");
        }
      }
      out.append(
        "人数按各签到场次统计，跨议程相加是签到人次，不是去重参会人数。\n"
      );
    }
    if (poll) {
      out.append("【投票结果】\n");
      for (var m : meetings) {
        out.append("• ").append(m.get("title")).append("：\n");
        var rows = exhibit.rows(m, "polls");
        if (rows.isEmpty()) out.append("  暂未发布投票。\n");
        for (var p : rows) {
          out
            .append("  ")
            .append(p.get("title"))
            .append("：")
            .append(p.get("status"))
            .append("。\n");
          if (!"已通过".equals(p.get("status"))) continue;
          out.append("  题目：").append(p.get("question")).append("\n");
          List<?> options = (List<?>) p.get("options"),
            counts = (List<?>) p.get("results");
          long total = counts
            .stream()
            .mapToLong(n -> ((Number) n).longValue())
            .sum();
          out
            .append("  本题参与 ")
            .append(total)
            .append(" 人；截止 ")
            .append(p.get("ends"))
            .append("。\n");
          for (int i = 0; i < options.size(); i++) out
            .append("  - ")
            .append(options.get(i))
            .append("：")
            .append(counts.get(i))
            .append(" 票\n");
        }
      }
      out.append("多道题的参与人数不能直接相加当作去重人数。\n");
    }
    if (!(progress || questions || check || poll)) for (var m : meetings)
      out
        .append("• ")
        .append(m.get("title"))
        .append("：")
        .append(m.get("status"))
        .append("\n");
    return out.toString().strip();
  }
}

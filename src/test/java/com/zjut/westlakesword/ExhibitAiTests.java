package com.zjut.westlakesword;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ExhibitAiTests {

  final ExhibitAi ai = new ExhibitAi(new ExhibitApi(null, null), null);

  Map<String, Object> meeting() {
    return new HashMap<>(
      Map.of(
        "title",
        "安全论坛",
        "status",
        "进行中",
        "reason",
        "",
        "talks",
        List.of(Map.of("title", "主旨演讲")),
        "questions",
        List.of(),
        "checks",
        List.of(),
        "polls",
        List.of()
      )
    );
  }

  @Test
  void progressUsesApprovalNotRunningState() {
    var m = meeting();
    String answer = ai.facts("会议审查进度", List.of(m));
    assertTrue(answer.contains("审核已通过，会议进行中"));
    assertFalse(answer.contains("已核销"));
  }

  @Test
  void unansweredIncludesQuestionTextAndExcludesPendingAnswers() {
    var m = meeting();
    m.put(
      "questions",
      List.of(
        Map.of(
          "status",
          "待回答",
          "body",
          "资料怎么下载？",
          "talk",
          0,
          "talk_id",
          1
        ),
        Map.of("status", "待审核", "body", "已回答的问题", "talk", 0)
      )
    );
    String answer = ai.facts("有哪些问题带回答", List.of(m));
    assertTrue(answer.contains("资料怎么下载？"));
    assertFalse(answer.contains("已回答的问题"));
    assertTrue(answer.contains("1条回答已提交"));
  }

  @Test
  void combinedResultsContainActualCounts() {
    var m = meeting();
    m.put(
      "checks",
      List.of(
        Map.of(
          "status",
          "已通过",
          "talk",
          0,
          "count",
          3,
          "starts",
          "09:00",
          "ends",
          "10:00"
        )
      )
    );
    m.put(
      "polls",
      List.of(
        Map.of(
          "status",
          "已通过",
          "title",
          "议题征集",
          "question",
          "下次讲什么",
          "options",
          List.of("云安全", "AI"),
          "results",
          List.of(2, 1),
          "ends",
          "17:00"
        )
      )
    );
    String answer = ai.facts("签到和投票结果", List.of(m));
    assertTrue(answer.contains("已核销 3 人"));
    assertTrue(answer.contains("云安全：2 票"));
    assertTrue(answer.contains("本题参与 3 人"));
  }

  @Test
  void emptyRecordsAreExplicit() {
    String answer = ai.facts("签到和投票结果", List.of(meeting()));
    assertTrue(answer.contains("暂未发布签到"));
    assertTrue(answer.contains("暂未发布投票"));
  }

  @Test
  void rejectionReasonNotInvented() {
    var m = meeting();
    m.put("status", "已驳回");
    m.put("reason", "请补充议程");
    String answer = ai.facts("审批通过了吗", List.of(m));
    assertTrue(answer.contains("已驳回；原因：请补充议程"));
    assertFalse(answer.contains("审核已通过"));
  }
}

package com.zjut.westlakesword;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AiKnowledgeTests {

  @Test
  void chineseIntentFindsTimeAndVenue() {
    var time = new AiKnowledge.Source(
      "TIME1",
      "time",
      "会议时间",
      "开始时间：明天九点",
      "/?event=1"
    );
    var venue = new AiKnowledge.Source(
      "VENUE1",
      "venue",
      "会议地点",
      "杭州交流中心",
      "/?event=1"
    );
    assertEquals(
      time,
      AiKnowledge.rank("什么时候开始？", List.of(venue, time)).get(0)
    );
    assertEquals(
      venue,
      AiKnowledge.rank("在什么地方？", List.of(time, venue)).get(0)
    );
  }

  @Test
  void understandsOverviewParaphrases() {
    var source = new AiKnowledge.Source(
      "E1_0",
      "overview",
      "数字安全峰会",
      "本届聚焦智能防御与治理。",
      "/?event=1"
    );
    for (String q : List.of("这次会议有什么内容", "主要讲什么", "讨论什么"))
      assertEquals(source, AiKnowledge.rank(q, List.of(source)).get(0));
  }

  @Test
  void topicMatchRanksAboveGenericAgenda() {
    var target = new AiKnowledge.Source(
      "T2",
      "talk",
      "云安全治理",
      "嘉宾：陈老师",
      "/?event=1&talk=2"
    );
    var other = new AiKnowledge.Source(
      "T1",
      "talk",
      "隐私计算",
      "嘉宾：李老师",
      "/?event=1&talk=1"
    );
    assertEquals(
      target,
      AiKnowledge.rank("云安全治理议程谁主讲？", List.of(other, target)).get(0)
    );
  }

  @Test
  void refusesUnrelatedQuestionsAndNormalizesFullWidthWords() {
    var source = new AiKnowledge.Source(
      "VENUE1",
      "venue",
      "会议地点",
      "杭州交流中心",
      "/?event=1"
    );
    assertTrue(AiKnowledge.rank("火星基地暗号", List.of(source)).isEmpty());
    assertTrue(
      AiKnowledge.rank("杭州今天的天气如何", List.of(source)).isEmpty()
    );
    assertTrue(
      AiKnowledge.terms("ＤＥＥＰＳＥＥＫ，数据安全").containsAll(
        Set.of("deepseek", "数据", "安全")
      )
    );
  }

  @Test
  void limitsContextAndOrdersTiesDeterministically() {
    List<AiKnowledge.Source> sources = new ArrayList<>();
    for (int i = 9; i >= 0; i--) sources.add(
      new AiKnowledge.Source("T" + i, "talk", "议程", "嘉宾", "/?event=1")
    );
    var result = AiKnowledge.rank("有哪些议程？", sources);
    assertEquals(5, result.size());
    assertEquals("T0", result.get(0).id());
    assertEquals(result, AiKnowledge.rank("有哪些议程？", sources));
  }
}

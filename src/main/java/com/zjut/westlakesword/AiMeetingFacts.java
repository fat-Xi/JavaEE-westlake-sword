package com.zjut.westlakesword;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** 直接回答会议事实查询，并为会议总结准备资料。 */
final class AiMeetingFacts {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private enum Query {
    SUMMARY,
    AGENDA,
    SPEAKERS,
    MATERIALS,
    POLLS,
    OPEN_POLLS,
  }

  private final Store store;
  private final AiKnowledge knowledge;

  AiMeetingFacts(Store store, AiKnowledge knowledge) {
    this.store = store;
    this.knowledge = knowledge;
  }

  private static boolean has(String q, String... terms) {
    return Arrays.stream(terms).anyMatch(q::contains);
  }

  private static Query query(String q) {
    if (
      q.contains("亮点") &&
      has(q, "总结", "概括", "归纳", "概览", "有哪些", "有什么", "介绍")
    ) return Query.SUMMARY;
    if (
      has(
        q,
        "总结这场会议",
        "总结这次会议",
        "总结会议内容",
        "概括本次会议",
        "概括会议内容",
        "归纳会议要点"
      )
    ) return Query.SUMMARY;
    boolean listing = has(
      q,
      "哪些",
      "什么",
      "有无",
      "有没有",
      "是否有",
      "列出",
      "清单"
    );
    if (
      has(q, "投票", "票题") && !has(q, "选项", "结果", "票数", "我投", "投过")
    ) {
      if (
        has(
          q,
          "正在投票",
          "正在进行的投票",
          "进行中的投票",
          "还有投票",
          "现在能投",
          "可以投的",
          "能投什么"
        )
      ) return Query.OPEN_POLLS;
      if (listing) return Query.POLLS;
    }
    if (
      listing &&
      has(q, "资料", "附件", "文件") &&
      has(q, "下载", "已发布", "已公开", "有哪些资料", "有什么资料", "资料清单")
    ) return Query.MATERIALS;
    if (
      has(
        q,
        "有哪些嘉宾",
        "有什么嘉宾",
        "哪几位嘉宾",
        "嘉宾名单",
        "有哪些讲师",
        "讲师名单",
        "有哪些演讲者"
      )
    ) return Query.SPEAKERS;
    if (
      has(
        q,
        "有哪些议程",
        "有什么议程",
        "议程清单",
        "议程列表",
        "议程有哪些",
        "安排了哪些议程"
      )
    ) return Query.AGENDA;
    return null;
  }

  AiParticipant.Direct answer(String q, long user, Long eventId) {
    Query query = query(q);
    if (query == null) return null;
    if (eventId == null) {
      var matches = store.db
        .queryForList(
          "SELECT e.id,e.title FROM events e JOIN joins j ON j.event_id=e.id WHERE e.status='OPEN' AND j.user_id=? AND j.status='APPROVED'",
          user
        )
        .stream()
        .filter(row -> {
          String title = AiParticipant.normalized((String) row.get("title"));
          return !title.isEmpty() && q.contains(title);
        })
        .toList();
      if (matches.size() != 1) return new AiParticipant.Direct(
        "platform",
        "请先在「咨询会议」选择一场报名已通过的会议，或告诉我完整会议名称。",
        List.of()
      );
      eventId = Store.num(matches.get(0), "id");
    }
    var event = store.one(
      "SELECT e.title,e.features,e.content FROM events e JOIN joins j ON j.event_id=e.id WHERE e.id=? AND e.status='OPEN' AND j.user_id=? AND j.status='APPROVED'",
      eventId,
      user
    );
    String title = (String) event.get("title");
    return switch (query) {
      case SUMMARY -> summary(eventId, title, event);
      case AGENDA, SPEAKERS -> listing(
        eventId,
        title,
        query == Query.SPEAKERS ? "议程中列出的嘉宾" : "已发布的议程",
        talks(eventId, 11, query == Query.SPEAKERS),
        "会议议程"
      );
      case MATERIALS -> materials(eventId, title);
      case POLLS, OPEN_POLLS -> polls(
        eventId,
        title,
        query == Query.OPEN_POLLS
      );
    };
  }

  private AiParticipant.Direct summary(
    long id,
    String title,
    Map<String, Object> event
  ) {
    var sources = knowledge.summarySources(
      id,
      title,
      Objects.toString(event.get("content"), ""),
      String.join("\n", features(event.get("features")))
    );
    return new AiParticipant.Direct(
      "platform",
      "「" + title + "」现有的会议资料不足以总结出具体亮点。",
      sources,
      Set.of(id),
      !sources.isEmpty()
    );
  }

  private List<AiKnowledge.Source> talks(
    long event,
    int limit,
    boolean speakerFirst
  ) {
    return store.db
      .queryForList(
        "SELECT id,title,speaker,room,starts,ends FROM talks WHERE event_id=?" +
          (speakerFirst ? " AND TRIM(speaker)<>''" : "") +
          " ORDER BY starts,id LIMIT ?",
        event,
        limit
      )
      .stream()
      .map(row -> {
        String slot =
          AiParticipant.time(row.get("starts")) +
          " 至 " +
          AiParticipant.time(row.get("ends")) +
          "（北京时间） · " +
          row.get("room");
        return new AiKnowledge.Source(
          "MEETING_TALK_" + row.get("id"),
          "talk",
          (String) row.get(speakerFirst ? "speaker" : "title"),
          row.get(speakerFirst ? "title" : "speaker") + " · " + slot,
          "/?event=" + event + "&tab=talks#talk-" + row.get("id")
        );
      })
      .toList();
  }

  private static List<String> features(Object raw) {
    if (!(raw instanceof String content) || content.isBlank()) return List.of();
    List<String> features = new ArrayList<>();
    try {
      var items = JSON.readTree(content);
      if (!items.isArray()) return List.of();
      for (var item : items) {
        String title = item.path("title").asText("").strip();
        String body = item.path("body").asText("").strip();
        if (!title.isEmpty()) features.add(
          title +
            (body.isEmpty()
              ? ""
              : " · " +
                (body.length() <= 180 ? body : body.substring(0, 180) + "…"))
        );
        if (features.size() == 4) break;
      }
    } catch (RuntimeException ignored) {
      return List.of();
    }
    return features;
  }

  private AiParticipant.Direct materials(long id, String title) {
    List<AiKnowledge.Source> results = new ArrayList<>();
    for (var row : store.db.queryForList(
      "SELECT id,name FROM files WHERE event_id=? ORDER BY id DESC LIMIT 11",
      id
    ))
      results.add(
        new AiKnowledge.Source(
          "FILE_" + row.get("id"),
          "materials",
          (String) row.get("name"),
          "会议上传资料",
          "/api/files/" + row.get("id") + "/download"
        )
      );
    for (var row : store.db.queryForList(
      "SELECT id,title,kind FROM posts WHERE event_id=? AND status='APPROVED' AND kind IN ('NEWS','RESULT','EXHIBIT') AND TRIM(COALESCE(url,''))<>'' ORDER BY id DESC LIMIT 11",
      id
    ))
      results.add(
        new AiKnowledge.Source(
          "POST_ATTACHMENT_" + row.get("id"),
          "materials",
          (String) row.get("title"),
          "该条已发布内容附有下载链接",
          "/?event=" +
            id +
            "&tab=content&kind=" +
            row.get("kind") +
            "#post-" +
            row.get("id")
        )
      );
    return listing(id, title, "可下载的资料", results, "展商内容");
  }

  private AiParticipant.Direct polls(long id, String title, boolean open) {
    var rows = store.db.queryForList(
      """
      SELECT id,title,starts,ends,
        CASE WHEN enabled=FALSE THEN '已关闭' WHEN NOW()<starts THEN '未开始'
          WHEN NOW()>=ends THEN '已结束' ELSE '进行中' END phase
      FROM polls WHERE event_id=?
      """ +
        (open ? " AND enabled=TRUE AND starts<=NOW() AND ends>NOW()" : "") +
        " ORDER BY starts,id LIMIT 11",
      id
    );
    var sources = rows
      .stream()
      .map(row ->
        new AiKnowledge.Source(
          "POLL_" + row.get("id"),
          "poll",
          (String) row.get("title"),
          row.get("phase") +
            " · " +
            AiParticipant.time(row.get("starts")) +
            " 至 " +
            AiParticipant.time(row.get("ends")) +
            "（北京时间）",
          "/?event=" + id + "&tab=polls"
        )
      )
      .toList();
    return listing(
      id,
      title,
      open ? "正在进行的投票" : "已发布的投票",
      sources,
      "议程投票"
    );
  }

  private static AiParticipant.Direct listing(
    long id,
    String title,
    String kind,
    List<AiKnowledge.Source> results,
    String page
  ) {
    if (results.isEmpty()) return new AiParticipant.Direct(
      "platform",
      title + "目前没有" + kind + "。",
      List.of(),
      Set.of(id)
    );
    var sources = results.stream().limit(10).toList();
    StringBuilder answer = new StringBuilder(title)
      .append("的")
      .append(kind)
      .append("如下。");
    for (var source : sources)
      answer
        .append("\n")
        .append(source.title())
        .append(" · ")
        .append(source.excerpt());
    if (results.size() > 10) answer
      .append("\n以上是前 10 项，其余请到「")
      .append(page)
      .append("」查看。");
    return new AiParticipant.Direct(
      "platform",
      answer.toString(),
      sources,
      Set.of(id)
    );
  }
}

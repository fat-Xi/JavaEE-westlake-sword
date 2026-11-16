package com.zjut.westlakesword;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 仅索引当前会议已公开的内容；不读取个人档案、待审内容或文件字节。 */
@Component
public class AiKnowledge {

  public record Source(
    String id,
    String kind,
    String title,
    String excerpt,
    String url
  ) {}

  private final Store store;
  private static final Map<String, List<String>> INTENTS = Map.ofEntries(
    Map.entry(
      "time",
      List.of(
        "时间",
        "日期",
        "几点",
        "什么时候",
        "何时",
        "哪天",
        "开始",
        "结束",
        "进行中",
        "when"
      )
    ),
    Map.entry(
      "venue",
      List.of("地点", "地址", "哪里", "在哪", "地方", "会场", "位置", "where")
    ),
    Map.entry(
      "talk",
      List.of("议程", "嘉宾", "讲师", "主讲", "演讲", "谁", "speaker")
    ),
    Map.entry(
      "registration",
      List.of("报名", "审核", "资格", "注册", "registration")
    ),
    Map.entry("plan", List.of("日程", "冲突", "收藏", "推荐")),
    Map.entry("questions", List.of("提问", "赞同", "投票", "答复", "精选")),
    Map.entry("materials", List.of("资料", "下载", "附件", "文件")),
    Map.entry(
      "overview",
      List.of("介绍", "简介", "主题", "内容", "讲什么", "讨论什么")
    ),
    Map.entry("exhibit", List.of("展商", "展览", "产品")),
    Map.entry("news", List.of("资讯", "新闻", "公告")),
    Map.entry("result", List.of("成果", "白皮书", "案例"))
  );
  private static final Set<String> STOP = Set.of(
    "会议",
    "活动",
    "问题",
    "什么",
    "如何",
    "怎么",
    "哪些",
    "可以",
    "请问",
    "一下",
    "这个",
    "那个",
    "当前",
    "我们",
    "我的",
    "相关",
    "是否",
    "告诉",
    "知道",
    "时候",
    "想要",
    "帮我"
  );
  private static final Pattern WORDS = Pattern.compile(
    "[a-z0-9]{2,}|[\\p{IsHan}]+"
  );

  public AiKnowledge(Store store) {
    this.store = store;
  }

  public List<Source> search(long eventId, String question) {
    var event = store.one(
      "SELECT id,title,place,starts,ends,content FROM events WHERE id=? AND status='OPEN'",
      eventId
    );
    String title = (String) event.get("title");
    String home = "/?event=" + eventId;
    List<Source> documents = new ArrayList<>();
    chunks(
      documents,
      "E" + eventId,
      "overview",
      title + " · 会议介绍",
      text(event, "content"),
      home + "&tab=intro"
    );
    LocalDateTime now = LocalDateTime.now();
    String stage = now.isBefore((LocalDateTime) event.get("starts"))
      ? "未开始"
      : now.isBefore((LocalDateTime) event.get("ends"))
        ? "正在进行"
        : "已结束";
    documents.add(
      new Source(
        "TIME" + eventId,
        "time",
        title + " · 会议时间",
        "会议：" +
          title +
          "\n开始时间：" +
          event.get("starts") +
          "\n结束时间：" +
          event.get("ends") +
          "\n当前阶段：" +
          stage +
          "\n资料查询时刻：" +
          now +
          "\n以上时间为北京时间。",
        home + "&tab=intro"
      )
    );
    documents.add(
      new Source(
        "VENUE" + eventId,
        "venue",
        title + " · 会议地点",
        "会议：" + title + "\n地点：" + event.get("place"),
        home + "&tab=intro"
      )
    );
    documents.add(
      new Source(
        "REG" + eventId,
        "registration",
        "报名与审核规则",
        "参会者注册后需管理员审核身份，身份审核与会议报名审核相互独立。报名提交后等待管理员审核；报名审核通过后才可使用该会议的资料、视频、个人日程、提问和签到服务。重复报名不会重复占用名额。已签到后不能取消报名。",
        home + "&tab=intro"
      )
    );
    documents.add(
      new Source(
        "PLAN" + eventId,
        "plan",
        "个人日程与推荐",
        "在会议议程页可将议程加入个人日程；同一议程不能重复加入，与已有日程时间重叠时会被拒绝，包括其他会议中的日程。个人主页可查看或移除日程、修改兴趣标签；推荐按兴趣与实际收藏热度排序，并提示冲突。",
        home + "&tab=talks"
      )
    );
    documents.add(
      new Source(
        "BOX" + eventId,
        "questions",
        "提问箱与投票规则",
        "可提交全会问题或关联本会议议程，审核前和驳回后仅作者可见。对已公开的提问，参会者可赞同或点踩，并可切换或撤销。只有对应会议主办方可以提交答复，经管理员审核后公开。参会者之间可在评论区回复交流。问题关闭或会议结束后停止新增互动，已有赞同或点踩可撤销。独立单选投票每人每题一次，提交后不可改票，与问题互动分开计数。",
        home + "&tab=questions"
      )
    );
    documents.add(
      new Source(
        "FILES" + eventId,
        "materials",
        "会议资料下载",
        "在会议详情的「展商内容」查看已公开的会议资料。参会者身份及会议报名均审核通过后可下载资料，下载接口会再次核验资源所属会议和访问权限。",
        home + "&tab=content"
      )
    );
    for (var row : talks(eventId, 200)) {
      documents.add(
        new Source(
          "T" + row.get("id"),
          "talk",
          text(row, "title"),
          "会议：" +
            title +
            "\n议程：" +
            row.get("title") +
            "\n嘉宾：" +
            row.get("speaker") +
            "\n会场：" +
            row.get("room") +
            "\n开始：" +
            row.get("starts") +
            "\n结束：" +
            row.get("ends") +
            "\n标签：" +
            row.get("tags") +
            "\n时间为北京时间。",
          home + "&talk=" + row.get("id")
        )
      );
    }
    for (var row : publishedPosts(eventId, 200)) {
      chunks(
        documents,
        "P" + row.get("id"),
        text(row, "kind").toLowerCase(Locale.ROOT),
        text(row, "title"),
        text(row, "body") + "\n" + text(row, "product"),
        postUrl(home, row)
      );
    }
    for (var row : answeredQuestions(eventId, 200)) {
      String body = text(row, "body");
      chunks(
        documents,
        "Q" + row.get("id"),
        "faq",
        "主办方答复：" + body.substring(0, Math.min(60, body.length())),
        "问题：" + body + "\n主办方答复：" + row.get("answer"),
        home + "&tab=questions#question-" + row.get("id")
      );
    }
    return rank(question, documents);
  }

  List<Source> summarySources(
    long eventId,
    String title,
    String introduction,
    String organizerHighlights
  ) {
    String home = "/?event=" + eventId;
    List<Source> sources = new ArrayList<>();
    addSummarySource(
      sources,
      "INTRO" + eventId,
      "overview",
      title + " · 会议介绍",
      introduction,
      home + "&tab=intro"
    );
    addSummarySource(
      sources,
      "HIGHLIGHTS" + eventId,
      "overview",
      title + " · 主办方发布的亮点",
      organizerHighlights,
      home + "&tab=intro"
    );
    for (var row : talks(eventId, 5)) {
      String topic = text(row, "title").strip();
      String tags = text(row, "tags").strip();
      addSummarySource(
        sources,
        "T" + row.get("id"),
        "talk",
        title + " · 议程主题 " + topic,
        tags.isEmpty() ? topic : topic + "\n主题标签 " + tags,
        home + "&tab=talks#talk-" + row.get("id")
      );
    }
    for (var row : publishedPosts(eventId, 3)) {
      addSummarySource(
        sources,
        "P" + row.get("id"),
        text(row, "kind").toLowerCase(Locale.ROOT),
        text(row, "title"),
        text(row, "body") + "\n" + text(row, "product"),
        postUrl(home, row)
      );
    }
    for (var row : answeredQuestions(eventId, 2)) {
      addSummarySource(
        sources,
        "Q" + row.get("id"),
        "faq",
        "主办方答复",
        "问题 " + text(row, "body") + "\n主办方答复 " + text(row, "answer"),
        home + "&tab=questions#question-" + row.get("id")
      );
    }
    return List.copyOf(sources);
  }

  private static void addSummarySource(
    List<Source> sources,
    String id,
    String kind,
    String title,
    String body,
    String url
  ) {
    String content = body.strip();
    if (!content.isEmpty()) sources.add(
      new Source(
        id,
        kind,
        title,
        content.length() <= 800 ? content : content.substring(0, 800) + "…",
        url
      )
    );
  }

  private List<Map<String, Object>> talks(long eventId, int limit) {
    return store.db.queryForList(
      "SELECT id,title,speaker,room,starts,ends,tags FROM talks WHERE event_id=? ORDER BY starts,id LIMIT ?",
      eventId,
      limit
    );
  }

  private List<Map<String, Object>> publishedPosts(long eventId, int limit) {
    return store.db.queryForList(
      "SELECT id,kind,title,body,product FROM posts WHERE event_id=? AND status='APPROVED' AND kind IN ('NEWS','RESULT','EXHIBIT') ORDER BY id DESC LIMIT ?",
      eventId,
      limit
    );
  }

  private List<Map<String, Object>> answeredQuestions(long eventId, int limit) {
    return store.db.queryForList(
      "SELECT id,body,answer FROM questions WHERE event_id=? AND status='APPROVED' AND answer IS NOT NULL AND TRIM(answer)<>'' ORDER BY featured DESC,id DESC LIMIT ?",
      eventId,
      limit
    );
  }

  private static String postUrl(String home, Map<String, Object> row) {
    return (
      home + "&tab=content&kind=" + row.get("kind") + "#post-" + row.get("id")
    );
  }

  private static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  private static void chunks(
    List<Source> documents,
    String id,
    String kind,
    String title,
    String body,
    String url
  ) {
    for (int start = 0, part = 0; start < body.length(); start += 700, part++) {
      documents.add(
        new Source(
          id + "_" + part,
          kind,
          title,
          body.substring(start, Math.min(start + 800, body.length())),
          url
        )
      );
    }
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(
      Locale.ROOT
    );
  }

  static Set<String> terms(String value) {
    Set<String> tokens = new HashSet<>();
    var matcher = WORDS.matcher(normalize(value));
    while (matcher.find()) {
      String word = matcher.group();
      if (word.charAt(0) < 128) tokens.add(word);
      else for (int i = 0; i + 1 < word.length(); i++) {
        String token = word.substring(i, i + 2);
        if (!STOP.contains(token)) tokens.add(token);
      }
    }
    return tokens;
  }

  static List<Source> rank(String question, List<Source> documents) {
    String normalized = normalize(question);
    Set<String> query = terms(question);
    Set<String> intents = new HashSet<>();
    INTENTS.forEach((kind, aliases) -> {
      if (aliases.stream().anyMatch(normalized::contains)) intents.add(kind);
    });
    record Match(Source source, long score) {}
    return documents
      .stream()
      .map(source -> {
        Set<String> title = terms(source.title());
        Set<String> content = terms(source.excerpt());
        long score =
          query.stream().filter(title::contains).count() * 3 +
          query.stream().filter(content::contains).count();
        if (intents.contains(source.kind())) score += 15;
        return new Match(source, score);
      })
      .filter(match -> match.score() >= 3)
      .sorted(
        Comparator.comparingLong(Match::score)
          .reversed()
          .thenComparing(match -> match.source().id())
      )
      .limit(5)
      .map(Match::source)
      .toList();
  }
}

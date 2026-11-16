package com.zjut.westlakesword;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

/** 参会者的平台知识和只读查询。个人查询的用户 ID 只接受服务端 Session。 */
@Component
public class AiParticipant {

  public record Direct(
    String mode,
    String answer,
    List<AiKnowledge.Source> sources,
    Set<Long> eventIds,
    boolean summarize
  ) {
    Direct(
      String mode,
      String answer,
      List<AiKnowledge.Source> sources,
      Set<Long> eventIds
    ) {
      this(mode, answer, sources, eventIds, false);
    }

    Direct(String mode, String answer, List<AiKnowledge.Source> sources) {
      this(mode, answer, sources, Set.of());
    }
  }

  private final Store store;
  private final AgendaRecommendations recommendations;
  private final AiMeetingFacts meetingFacts;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern(
    "yyyy-MM-dd HH:mm"
  );
  private static final Map<String, String> STATES = Map.of(
    "PENDING",
    "待审核",
    "APPROVED",
    "已通过",
    "REJECTED",
    "已驳回",
    "CANCELLED",
    "已取消"
  );
  private static final List<AiKnowledge.Source> RULES = List.of(
    rule(
      "HELP",
      "overview",
      "参会者可使用的服务",
      "可以浏览公开会议、查询可报名会议、提交或取消本人报名、查看本人审核状态；报名通过后使用会议议程、推荐、个人日程、签到、资料、视频、提问投票、评论和签名墙。可以维护本人资料、查看通知。不能执行用户审核、权限配置、展商办会或投稿、签到核销等管理操作，也不能读取他人的私有资料。"
    ),
    rule(
      "ACCOUNT",
      "account",
      "账号登录、个人资料与密码",
      "注册参会者后需要管理员审核身份。身份与会议报名分别审核。登录后从个人主页查看或修改本人头像、网名、真实姓名、单位、联系方式和兴趣标签；修改密码需输入正确旧密码，新密码8至50位，修改后需重新登录。登录过期时请重新登录。平台没有通过AI重置密码、提升角色或查询他人联系方式的功能。"
    ),
    rule(
      "REGISTER",
      "registration",
      "报名、审核、驳回与取消",
      "在发现活动中打开会议，点击立即报名并等待管理员审核。身份审核通过不等于会议报名已通过，审核没有承诺固定处理时长。可以查看本人报名状态及驳回原因；驳回后或取消后可重新报名。同一会议待审核或已通过时不能重复报名。已签到后不能取消报名。名额由管理员审核通过时核验，待审不保证获得名额。平台没有支付、购票退款或AI代为审核功能。"
    ),
    rule(
      "PLAN",
      "plan",
      "个人日程、兴趣推荐与冲突",
      "会议报名通过后，在会议议程或推荐区点击加入日程；在个人主页查看、移除日程。重复议程不能再次加入，跨会议时间重叠也会被拦截；先移除冲突项再添加。修改个人主页的兴趣标签后返回会议刷新推荐；没有标签时按真实收藏热度推荐。"
    ),
    rule(
      "CHECKIN",
      "checkin",
      "签到、二维码与本人凭证",
      "进入报名通过的会议的签到服务，在签到开放窗口出示本人签到码，也可下载个人码图片，交由工作人员核销。管理员发布的入口二维码不是个人凭证。同一场次只记一次签到，多个议程分别核销。参会者不能核销别人的票据，AI不展示票据字符串或代替工作人员签到。"
    ),
    rule(
      "FILES",
      "materials",
      "会议资料下载",
      "会议报名通过后，在会议详情的「展商内容」找到已公开的资料并下载。无权限时检查账号身份和会议报名是否分别审核通过，并确认文件属于当前会议。链接打不开时可刷新会议页面、重新登录后重试。正式会议资料由展商提交、管理员审核。"
    ),
    rule(
      "MEDIA",
      "media",
      "直播与回放",
      "会议报名通过后在直播和回放查看。议程进行中且管理员开启直播才出现直播入口；议程结束并发布回放后才能观看回放。未开放直播、尚未开始或尚未上传回放时没有对应内容，平台不自动录制。播放失败可刷新并核对网络、议程状态，持续失败可向主办方提问。"
    ),
    rule(
      "QUESTION",
      "questions",
      "提问、赞同与单选投票",
      "会议提问可关联议程或选择全会；待审和驳回问题仅作者可见，审核通过后公开。可以赞同或撤销赞同，管理员可精选、答复和关闭问题。关闭或会议结束后不能新增赞同，已赞同者仍可撤销。议程投票在开放时间每人每题只能投一次，提交后不能改票，与提问赞同分开计数。"
    ),
    rule(
      "COMMUNITY",
      "community",
      "评论、签名、分享和通知",
      "会议报名通过后，评论和评论回复提交后直接公开。可举报他人的违规评论，管理员审核举报后可保留或删除评论，也可发现违规时直接删除。删除会同时移除下方所有回复，并通过站内通知向受影响的作者说明理由。会议提问只由对应主办方提交答复，审核通过后公开。签名审核通过后公开，作者可在个人主页查看评论和签名。展商内容支持点赞和取消点赞。议程分享按钮复制分享链接。订阅会议后可在个人主页查看订阅及站内通知；当前没有邀请同行或短信提醒服务。"
    ),
    rule(
      "SUPPORT",
      "support",
      "页面、按钮和操作异常排查",
      "按钮没有反应、页面加载失败或操作报错时，先查看页面提示，确认网络连接，刷新页面或退出后重新登录。检查本人身份、该会议报名状态、会议或议程是否结束以及功能开放时间；重复报名、重复日程、时间冲突、无权限、投票关闭都会被拦截。仍有问题时可在会议提问箱说明具体会议、操作步骤和报错文字，请勿提交密码或密钥。"
    )
  );

  public AiParticipant(
    Store store,
    AgendaRecommendations recommendations,
    AiKnowledge knowledge
  ) {
    this.store = store;
    this.recommendations = recommendations;
    this.meetingFacts = new AiMeetingFacts(store, knowledge);
  }

  private static AiKnowledge.Source rule(
    String id,
    String kind,
    String title,
    String text
  ) {
    return new AiKnowledge.Source(
      "PLATFORM_" + id,
      kind,
      title,
      text,
      "/?page=mine"
    );
  }

  static String normalized(String q) {
    return Normalizer.normalize(q, Normalizer.Form.NFKC)
      .toLowerCase(Locale.ROOT)
      .replaceAll("[^\\p{L}\\p{N}]", "");
  }

  private static boolean has(String q, String... words) {
    return Arrays.stream(words).anyMatch(q::contains);
  }

  static boolean privateRequest(String question) {
    String q = normalized(question);
    return (
      q.matches(
        ".*(管理员|后台|系统|你的)(的)?(账号|账户|登录|初始|默认|访问)?(的)?(密码|密钥|口令|apikey).*"
      ) ||
      has(
        q,
        "apikey",
        "api密钥",
        "系统提示词",
        "提升权限",
        "修改他人",
        "审核他人",
        "替别人签到",
        "帮别人签到"
      ) ||
      (has(q, "其他人", "其他用户", "别人", "所有人", "他人", "全体用户") &&
        has(
          q,
          "电话",
          "邮箱",
          "身份证",
          "报名名单",
          "个人信息",
          "联系方式",
          "报名状态",
          "报名的会议",
          "参加的会议",
          "签到记录",
          "日程",
          "安排",
          "通知",
          "消息"
        ))
    );
  }

  static boolean modelIdentity(String question) {
    return normalized(question).matches(
      ".*你(是|用|使用|接入|运行).{0,12}模型.*|.*(当前模型|模型名称|模型叫什么|使用的什么模型).*"
    );
  }

  static boolean availableQuestion(String q) {
    return (
      !has(q, "议程", "讲座", "场次", "分论坛") &&
      has(
        q,
        "可报名",
        "可以报名",
        "能报名",
        "还能报名",
        "可以参加哪些",
        "能参加哪些",
        "哪些可以参加"
      ) &&
      (has(q, "哪些", "什么", "列表", "列出", "几场", "有啥") ||
        q.equals("可报名会议") ||
        q.equals("可报名活动"))
    );
  }

  static boolean catalogQuestion(String q) {
    return (
      has(q, "会议", "活动", "论坛") &&
      has(
        q,
        "有哪些",
        "有什么",
        "有啥",
        "列表",
        "列出",
        "多少",
        "几场",
        "几个"
      ) &&
      !has(
        q,
        "议程",
        "嘉宾",
        "资料",
        "内容",
        "主题",
        "安排",
        "投票",
        "选项",
        "评论",
        "签名",
        "提问"
      )
    );
  }

  static boolean inScope(String question) {
    return has(
      normalized(question),
      "平台",
      "网站",
      "网页",
      "页面",
      "加载",
      "报错",
      "按钮",
      "账号",
      "账户",
      "登录",
      "注册",
      "密码",
      "个人",
      "报名",
      "审核",
      "会议",
      "活动",
      "参会",
      "议程",
      "会场",
      "嘉宾",
      "讲师",
      "推荐",
      "日程",
      "行程",
      "收藏",
      "冲突",
      "签到",
      "二维码",
      "凭证",
      "电子票",
      "核销",
      "直播",
      "回放",
      "视频",
      "资料",
      "下载",
      "附件",
      "通知",
      "订阅",
      "提醒",
      "分享",
      "链接",
      "资讯",
      "成果",
      "展商",
      "评论",
      "点赞",
      "签名",
      "提问",
      "投票",
      "赞同",
      "反馈",
      "帮助",
      "客服"
    );
  }

  private static boolean agendaRecommendation(String q) {
    return (
      has(q, "议程", "讲座", "场次", "分论坛") &&
      (has(q, "推荐", "适合我") ||
        (has(q, "哪些", "哪几", "哪场", "哪一场") &&
          has(q, "值得", "能参加", "可以参加"))) &&
      !has(
        q,
        "怎么",
        "如何",
        "怎样",
        "为什么",
        "在哪",
        "哪里",
        "根据什么",
        "按什么",
        "原理",
        "算法",
        "规则",
        "机制",
        "推荐功能"
      )
    );
  }

  public Direct direct(String question, long user, String model, Long eventId) {
    String q = normalized(question);
    boolean workflow = has(
      q.replace("怎么样", ""),
      "如何",
      "怎样",
      "怎么",
      "为什么",
      "多久",
      "什么时候",
      "何时",
      "需要",
      "流程",
      "步骤"
    );
    if (privateRequest(q)) return new Direct(
      "refused",
      "我只能协助参会者使用平台和查询本人的记录，不能提供管理端凭据、越权操作或他人的私有信息。",
      List.of()
    );
    if (modelIdentity(q)) return new Direct(
      "platform",
      "我是会享参会助手，当前配置的模型是 " +
        model +
        "。我可以帮你查询会议、报名、日程、签到、资料和互动相关问题。",
      List.of()
    );
    if (
      Set.of(
        "你好",
        "您好",
        "hello",
        "hi",
        "你是谁",
        "你能做什么",
        "你能干什么",
        "你能干啥",
        "你有什么功能",
        "你能回答什么",
        "你能帮我什么"
      ).contains(q)
    ) return new Direct(
      "platform",
      "你好，我是会享参会助手。可以查询公开会议和可报名活动，查看本人的报名、日程、签到及通知，并解答参会操作问题。",
      List.of(RULES.get(0))
    );
    if (availableQuestion(q)) return catalog(user, true);
    if (agendaRecommendation(q)) return recommend(user, eventId);
    boolean joinedList = has(
      q,
      "已报名的会议",
      "我报名的会议",
      "我报过的会议",
      "我参加的会议"
    );
    if (
      (joinedList ||
        has(
          q,
          "我的报名",
          "我报名",
          "我报了",
          "我已经报",
          "报名状态",
          "报名情况",
          "我的审核",
          "我通过审核",
          "我的会议"
        )) &&
      !workflow
    ) return registrations(user, eventId, joinedList);
    boolean upcomingPlans = has(
      q,
      "接下来有什么安排",
      "接下来有哪些安排",
      "接下来的安排",
      "接下来的日程",
      "接下来有哪些日程"
    );
    if (
      (upcomingPlans ||
        has(
          q,
          "我的日程",
          "我的行程",
          "我的个人日程",
          "我的会议日程",
          "我的参会安排",
          "我添加的日程",
          "我的安排",
          "我收藏的议程"
        )) &&
      !workflow
    ) return plans(user, eventId, upcomingPlans);
    if (
      has(
        q,
        "我的签到",
        "我签到了吗",
        "我签到没",
        "我有没有签到",
        "我的核销"
      ) &&
      !workflow
    ) return checkins(user, eventId);
    if (
      has(q, "我的通知", "我的消息", "未读通知", "未读消息") && !workflow
    ) return notes(user, has(q, "未读"));
    if (!workflow) {
      var meetingAnswer = meetingFacts.answer(q, user, eventId);
      if (meetingAnswer != null) return meetingAnswer;
    }
    if (catalogQuestion(q)) return catalog(user, false);
    return null;
  }

  private Direct recommend(long user, Long eventId) {
    var rows = recommendations
      .find(user, eventId)
      .stream()
      .filter(row -> ((List<?>) row.get("conflicts")).isEmpty())
      .limit(3)
      .toList();
    if (rows.isEmpty()) return new Direct(
      "platform",
      "目前没有可推荐的议程。推荐范围是报名已通过的会议，已结束、已加入日程和时间冲突的场次会被排除。",
      List.of()
    );
    StringBuilder answer = new StringBuilder(
      eventId == null
        ? "从你已通过报名的会议中，推荐以下议程（北京时间）："
        : "这场会议里，推荐以下议程（北京时间）："
    );
    List<AiKnowledge.Source> sources = new ArrayList<>();
    Set<Long> eventIds = new HashSet<>();
    for (var row : rows) {
      long event = Store.num(row, "event_id"),
        talk = Store.num(row, "id");
      eventIds.add(event);
      String title = row.get("title") + " · " + row.get("event_title");
      String details =
        time(row.get("starts")) +
        " 至 " +
        time(row.get("ends")) +
        " · " +
        row.get("room");
      String reason = (String) row.get("reason");
      answer
        .append("\n\n")
        .append(sources.size() + 1)
        .append(". ")
        .append(title)
        .append("\n")
        .append(details)
        .append("\n")
        .append(reason);
      sources.add(
        new AiKnowledge.Source(
          "RECOMMENDED_TALK_" + talk,
          "talk",
          title,
          details + "\n" + reason,
          "/?event=" + event + "&tab=talks#talk-" + talk
        )
      );
    }
    return new Direct(
      "platform",
      answer.toString(),
      sources,
      Set.copyOf(eventIds)
    );
  }

  private List<Map<String, Object>> catalogRows(long user) {
    return store.db.queryForList(
      "SELECT e.id,e.title,e.place,e.starts,e.ends,e.capacity,(SELECT COUNT(*) FROM joins j WHERE j.event_id=e.id AND j.status='APPROVED') approved,(SELECT j.status FROM joins j WHERE j.event_id=e.id AND j.user_id=?) join_status FROM events e WHERE e.status='OPEN' ORDER BY CASE WHEN e.ends<=NOW() THEN 2 WHEN e.starts>NOW() THEN 1 ELSE 0 END,e.starts,e.id",
      user
    );
  }

  static boolean available(Map<String, Object> e, LocalDateTime now) {
    return (
      ((LocalDateTime) e.get("ends")).isAfter(now) &&
      !Set.of("PENDING", "APPROVED").contains(
        Objects.toString(e.get("join_status"), "")
      ) &&
      Store.num(e, "approved") < Store.num(e, "capacity")
    );
  }

  static String time(Object value) {
    return value instanceof LocalDateTime d
      ? d.format(TIME)
      : Objects.toString(value, "—");
  }

  private static String overview(Map<String, Object> e) {
    LocalDateTime now = LocalDateTime.now();
    String phase = now.isBefore((LocalDateTime) e.get("starts"))
      ? "未开始"
      : now.isBefore((LocalDateTime) e.get("ends"))
        ? "进行中"
        : "已结束";
    String join = Objects.toString(e.get("join_status"), "");
    String enrollment = Set.of("APPROVED", "PENDING").contains(join)
      ? "你的报名：" + STATES.get(join)
      : available(e, now)
        ? "可提交报名，仍需审核"
        : phase.equals("已结束")
          ? "已停止报名"
          : "当前名额已满";
    return (
      "时间：" +
      time(e.get("starts")) +
      " 至 " +
      time(e.get("ends")) +
      "（北京时间）\n地点：" +
      e.get("place") +
      "\n状态：" +
      phase +
      "；" +
      enrollment +
      "；剩余审核名额：" +
      Math.max(0, Store.num(e, "capacity") - Store.num(e, "approved"))
    );
  }

  private Direct catalog(long user, boolean onlyAvailable) {
    var all = catalogRows(user);
    var matches = all
      .stream()
      .filter(e -> !onlyAvailable || available(e, LocalDateTime.now()))
      .toList();
    if (matches.isEmpty()) return new Direct(
      "platform",
      onlyAvailable
        ? "目前没有符合可报名条件的会议：需要会议未结束、本人未处于待审核或已通过状态，并且还有名额。可以到发现活动查看后续发布。"
        : "目前平台没有已发布的会议，可以稍后到发现活动查看。",
      List.of()
    );
    StringBuilder answer = new StringBuilder(
      onlyAvailable ? "目前你可提交报名的会议有 " : "当前平台已发布的会议有 "
    )
      .append(matches.size())
      .append(" 场：");
    List<AiKnowledge.Source> sources = new ArrayList<>();
    for (var e : matches.stream().limit(20).toList()) {
      String text = overview(e);
      answer
        .append("\n\n")
        .append(sources.size() + 1)
        .append(". ")
        .append(e.get("title"))
        .append("\n")
        .append(text);
      sources.add(
        new AiKnowledge.Source(
          "CATALOG_" + e.get("id"),
          "catalog",
          (String) e.get("title"),
          text,
          "/?event=" + e.get("id")
        )
      );
    }
    if (matches.size() > 20) answer.append(
      "\n\n以上展示前20场，其余请到发现活动查看。"
    );
    answer.append("\n\n名额和审核状态可能变化，请以提交及审核时的结果为准。");
    return new Direct("platform", answer.toString(), sources);
  }

  private Direct registrations(long user, Long eventId, boolean joinedList) {
    var rows = store.db.queryForList(
      "SELECT e.id,e.title,j.status,j.reason FROM joins j JOIN events e ON e.id=j.event_id WHERE j.user_id=?" +
        (eventId == null ? "" : " AND j.event_id=?") +
        (joinedList ? " AND j.status IN ('PENDING','APPROVED')" : "") +
        " ORDER BY j.id DESC LIMIT 30",
      eventId == null ? new Object[] { user } : new Object[] { user, eventId }
    );
    StringBuilder answer = new StringBuilder(
      eventId == null
        ? "你的会议报名记录如下，最多显示最近 30 条。"
        : "这场会议的报名记录如下。"
    );
    if (rows.isEmpty()) return personal(
      "MY_JOINS",
      "本人的报名状态",
      eventId != null
        ? "你在这场会议里暂无报名记录。"
        : joinedList
          ? "你目前没有待审核或已通过的会议报名。"
          : "你目前没有会议报名记录，可以前往发现活动报名。"
    );
    else for (var row : rows)
      answer
        .append("\n")
        .append(row.get("title"))
        .append("：")
        .append(STATES.getOrDefault(row.get("status"), "状态待确认"))
        .append(
          Objects.toString(row.get("reason"), "").isBlank()
            ? ""
            : "；原因：" + row.get("reason")
        );
    return personal("MY_JOINS", "本人的报名状态", answer.toString());
  }

  private Direct plans(long user, Long eventId, boolean upcoming) {
    var rows = store.db.queryForList(
      "SELECT e.title event_title,t.title,t.starts,t.ends,t.room FROM plans p JOIN talks t ON t.id=p.talk_id JOIN events e ON e.id=t.event_id JOIN joins j ON j.event_id=e.id AND j.user_id=p.user_id WHERE p.user_id=? AND j.status='APPROVED'" +
        (eventId == null ? "" : " AND t.event_id=?") +
        (upcoming ? " AND e.status='OPEN' AND t.ends>NOW()" : "") +
        " ORDER BY t.starts,t.id LIMIT 30",
      eventId == null ? new Object[] { user } : new Object[] { user, eventId }
    );
    String scope = eventId == null ? "你的" : "这场会议中你的";
    StringBuilder answer = new StringBuilder(
      rows.isEmpty()
        ? scope +
            (upcoming
              ? "个人日程暂无未结束的安排。"
              : "个人日程还没有已加入的议程。")
        : scope +
            (upcoming
              ? "接下来的个人日程如下，时间为北京时间。"
              : "个人日程如下，时间为北京时间。")
    );
    for (var row : rows)
      answer
        .append("\n\n")
        .append(row.get("event_title"))
        .append(" · ")
        .append(row.get("title"))
        .append("\n")
        .append(time(row.get("starts")))
        .append(" 至 ")
        .append(time(row.get("ends")))
        .append(" · ")
        .append(row.get("room"));
    return personal("MY_PLANS", "本人的个人日程", answer.toString());
  }

  private Direct checkins(long user, Long eventId) {
    var rows = store.db.queryForList(
      "SELECT e.title event_title,t.title,m.checked FROM marks m JOIN attendance a ON a.id=m.session_id JOIN talks t ON t.id=a.talk_id JOIN events e ON e.id=t.event_id WHERE m.user_id=?" +
        (eventId == null ? "" : " AND t.event_id=?") +
        " ORDER BY m.checked DESC LIMIT 30",
      eventId == null ? new Object[] { user } : new Object[] { user, eventId }
    );
    StringBuilder answer = new StringBuilder(
      rows.isEmpty()
        ? eventId == null
          ? "目前没有查询到你的议程签到记录。"
          : "这场会议暂无你的议程签到记录。"
        : "你已完成的议程签到如下。"
    );
    for (var row : rows)
      answer
        .append("\n")
        .append(row.get("event_title"))
        .append(" · ")
        .append(row.get("title"))
        .append("：")
        .append(time(row.get("checked")));
    return personal("MY_CHECKINS", "本人的签到记录", answer.toString());
  }

  private Direct notes(long user, boolean unread) {
    var rows = store.db.queryForList(
      "SELECT body,seen FROM notes WHERE user_id=?" +
        (unread ? " AND COALESCE(seen,FALSE)=FALSE" : "") +
        " ORDER BY id DESC LIMIT 10",
      user
    );
    StringBuilder answer = new StringBuilder(
      rows.isEmpty()
        ? unread
          ? "你目前没有未读站内通知。"
          : "你目前没有站内通知。"
        : unread
          ? "这是你最近的未读通知，最多显示 10 条。"
          : "这是你最近的站内通知，最多显示 10 条。"
    );
    for (var row : rows)
      answer
        .append("\n")
        .append(Boolean.TRUE.equals(row.get("seen")) ? "[已读] " : "[未读] ")
        .append(row.get("body"));
    return personal(
      "MY_NOTES",
      unread ? "本人的未读通知" : "本人的站内通知",
      answer.toString()
    );
  }

  private static Direct personal(String id, String title, String text) {
    return new Direct(
      "platform",
      text,
      List.of(
        new AiKnowledge.Source(id, "personal", title, text, "/?page=mine")
      )
    );
  }

  public List<AiKnowledge.Source> context(
    String question,
    List<AiKnowledge.Source> selected,
    Long eventId
  ) {
    String q = normalized(question);
    List<AiKnowledge.Source> publicFacts = List.of();
    if (
      eventId == null &&
      has(
        q,
        "时间",
        "几点",
        "何时",
        "什么时候",
        "地点",
        "哪里",
        "在哪",
        "会场",
        "开始",
        "结束",
        "介绍",
        "主题"
      )
    ) {
      var rows = store.db.queryForList(
        "SELECT id,title,place,starts,ends FROM events WHERE status='OPEN' ORDER BY starts DESC LIMIT 200"
      );
      var facts = rows
        .stream()
        .map(e ->
          new AiKnowledge.Source(
            "PUBLIC_" + e.get("id"),
            "public",
            (String) e.get("title"),
            "会议：" +
              e.get("title") +
              "\n时间：" +
              time(e.get("starts")) +
              " 至 " +
              time(e.get("ends")) +
              "\n地点：" +
              e.get("place"),
            "/?event=" + e.get("id")
          )
        )
        .toList();
      publicFacts = AiKnowledge.rank(question, facts);
    }
    if (
      selected.isEmpty() && publicFacts.isEmpty() && !inScope(question)
    ) return List.of();
    var rules = AiKnowledge.rank(question, RULES);
    if (rules.isEmpty()) rules = RULES;
    Map<String, AiKnowledge.Source> merged = new LinkedHashMap<>();
    selected.forEach(s -> merged.put(s.id(), s));
    publicFacts.forEach(s -> merged.put(s.id(), s));
    rules.forEach(s -> merged.put(s.id(), s));
    merged.putIfAbsent(RULES.get(0).id(), RULES.get(0));
    return merged.values().stream().limit(12).toList();
  }
}

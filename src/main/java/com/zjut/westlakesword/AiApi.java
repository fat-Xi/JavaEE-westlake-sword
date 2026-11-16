package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AiApi {

  record Ask(String question) {}

  record PlatformAsk(String question, Long eventId) {}

  record Reply(
    String mode,
    String answer,
    List<AiKnowledge.Source> sources,
    String notice,
    String model,
    String replyId
  ) {
    Reply(
      String mode,
      String answer,
      List<AiKnowledge.Source> sources,
      String notice,
      String model
    ) {
      this(mode, answer, sources, notice, model, null);
    }
  }

  private record VoiceReply(
    long userId,
    Long eventId,
    Set<Long> additionalEventIds,
    String answer,
    Instant expiresAt
  ) {}

  private static final String VOICE_REPLIES =
    AiApi.class.getName() + ".voiceReplies";

  private final Store store;
  private final Auth auth;
  private final AiKnowledge knowledge;
  private final AiModelClient client;
  private final AiParticipant participant;
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
  private final Semaphore slots = new Semaphore(4);
  private static final Logger LOG = LoggerFactory.getLogger(AiApi.class);

  public AiApi(
    Store store,
    Auth auth,
    AiKnowledge knowledge,
    AiModelClient client,
    AiParticipant participant
  ) {
    this.store = store;
    this.auth = auth;
    this.knowledge = knowledge;
    this.client = client;
    this.participant = participant;
  }

  @GetMapping("/ai/events")
  public List<Map<String, Object>> events(HttpSession s) {
    auth.member(s);
    return store.db.queryForList(
      "SELECT e.id,e.title,e.place,e.starts,e.ends FROM events e JOIN joins j ON j.event_id=e.id WHERE j.user_id=? AND j.status='APPROVED' AND e.status='OPEN' ORDER BY e.starts DESC,e.id",
      auth.id(s)
    );
  }

  @GetMapping("/ai/status")
  public Map<String, Object> status(HttpSession s) {
    auth.member(s);
    return Map.of("configured", client.configured(), "model", client.model());
  }

  private void eligible(long id, HttpSession s) {
    auth.approved(s, id);
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN'", id);
  }

  private static Reply refused() {
    return new Reply(
      "refused",
      "我可以协助解答参会服务问题。现有平台信息不足以回答这个具体问题，请补充会议名称或操作中遇到的提示；与参会无关的内容不在服务范围内。",
      List.of(),
      "",
      ""
    );
  }

  private static Reply fallback(
    List<AiKnowledge.Source> sources,
    boolean configured
  ) {
    StringBuilder text = new StringBuilder("检索到以下会议资料：");
    for (var source : sources)
      text
        .append("\n\n")
        .append(source.title())
        .append("\n")
        .append(source.excerpt());
    return new Reply(
      "faq",
      text.toString(),
      sources,
      configured
        ? "智能服务暂时不可用，已切换为资料检索结果。"
        : "智能问答暂不可用，以下为平台资料检索结果。",
      ""
    );
  }

  @PostMapping("/events/{id}/ai")
  public Reply ask(@PathVariable long id, @RequestBody Ask ask, HttpSession s) {
    return respond(id, ask.question(), s);
  }

  @PostMapping("/ai/ask")
  public Reply platformAsk(@RequestBody PlatformAsk ask, HttpSession s) {
    return respond(ask.eventId(), ask.question(), s);
  }

  private void access(Long id, HttpSession s) {
    if (id == null) auth.member(s);
    else eligible(id, s);
  }

  @SuppressWarnings("unchecked")
  private Map<String, VoiceReply> voiceReplies(HttpSession session) {
    var replies = (Map<String, VoiceReply>) session.getAttribute(VOICE_REPLIES);
    if (replies == null) {
      replies = new LinkedHashMap<>();
      session.setAttribute(VOICE_REPLIES, replies);
    }
    return replies;
  }

  private Reply remember(
    Reply reply,
    long userId,
    Long eventId,
    Set<Long> additionalEventIds,
    HttpSession session
  ) {
    String id = UUID.randomUUID().toString();
    synchronized (session) {
      try {
        var replies = voiceReplies(session);
        replies
          .values()
          .removeIf(value -> !value.expiresAt().isAfter(Instant.now()));
        if (replies.size() >= 10) replies.remove(
          replies.keySet().iterator().next()
        );
        replies.put(
          id,
          new VoiceReply(
            userId,
            eventId,
            additionalEventIds,
            reply.answer(),
            Instant.now().plusSeconds(900)
          )
        );
      } catch (IllegalStateException e) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
      }
    }
    return new Reply(
      reply.mode(),
      reply.answer(),
      reply.sources(),
      reply.notice(),
      reply.model(),
      id
    );
  }

  String voiceAnswer(String id, HttpSession session) {
    VoiceReply reply;
    synchronized (session) {
      try {
        reply = voiceReplies(session).get(id);
      } catch (IllegalStateException e) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
      }
    }
    if (
      reply == null ||
      reply.userId() != auth.id(session) ||
      !reply.expiresAt().isAfter(Instant.now())
    ) {
      throw new ResponseStatusException(
        HttpStatus.NOT_FOUND,
        "回答已过期或不属于当前会话，请重新提问"
      );
    }
    access(reply.eventId(), session);
    for (long event : reply.additionalEventIds()) eligible(event, session);
    return reply.answer();
  }

  private Reply summary(String question, AiParticipant.Direct direct) {
    var sources = direct.sources();
    var links = sources.stream().limit(3).toList();
    if (client.configured()) {
      try {
        var answer = client.summarize(question, sources);
        if (answer.answerable()) return modelReply(answer, sources);
        return new Reply("platform", direct.answer(), links, "", "");
      } catch (AiModelClient.Unavailable e) {
        LOG.warn("AI provider unavailable ({})", e.getMessage());
      }
    }
    return new Reply(
      "faq",
      "暂时无法生成会议亮点总结。可以打开下方资料查看原文。",
      links,
      "",
      ""
    );
  }

  private Reply modelReply(
    AiModelClient.Answer answer,
    List<AiKnowledge.Source> sources
  ) {
    return new Reply(
      "model",
      answer.answer(),
      sources
        .stream()
        .filter(source -> answer.sourceIds().contains(source.id()))
        .toList(),
      "",
      client.model()
    );
  }

  private Reply respond(Long id, String input, HttpSession s) {
    access(id, s);
    String question = Store.word(input, 500, "问题");
    long user = auth.id(s);
    if (!inFlight.add(user)) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "已有问题正在处理中，请稍后再试"
    );
    boolean acquired = slots.tryAcquire();
    try {
      if (!acquired) throw new ResponseStatusException(
        HttpStatus.TOO_MANY_REQUESTS,
        "问答服务繁忙，请稍后再试"
      );
      Reply reply;
      var direct = participant.direct(question, user, client.model(), id);
      var additionalEventIds =
        id == null && direct != null ? direct.eventIds() : Set.<Long>of();
      if (direct != null && direct.summarize()) {
        for (long event : additionalEventIds) eligible(event, s);
        reply = summary(question, direct);
      } else if (direct != null) {
        reply = new Reply(
          direct.mode(),
          direct.answer(),
          direct.sources(),
          "",
          ""
        );
      } else {
        var selected =
          id == null
            ? List.<AiKnowledge.Source>of()
            : knowledge.search(id, question);
        var sources = participant.context(question, selected, id);
        if (sources.isEmpty()) reply = new Reply(
          "refused",
          "这个问题超出参会服务范围。我可以帮你查询会议、报名、个人日程、签到、资料及平台互动相关问题。",
          List.of(),
          "",
          ""
        );
        else if (!client.configured()) reply = fallback(
          sources.stream().limit(5).toList(),
          false
        );
        else {
          try {
            var answer = client.answer(question, sources);
            reply = answer.answerable()
              ? modelReply(answer, sources)
              : refused();
          } catch (AiModelClient.Unavailable e) {
            LOG.warn("AI provider unavailable ({})", e.getMessage());
            reply = fallback(sources.stream().limit(5).toList(), true);
          }
        }
      }
      // 长请求结束前重新检查资格，报名被撤销或会议关闭后不再返回资料。
      access(id, s);
      for (long event : additionalEventIds) eligible(event, s);
      return remember(reply, user, id, additionalEventIds, s);
    } finally {
      if (acquired) slots.release();
      inFlight.remove(user);
    }
  }
}

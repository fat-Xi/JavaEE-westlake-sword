package com.zjut.westlakesword;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

class AiApiTests {

  final AtomicBoolean allowed = new AtomicBoolean(true);
  final Store store = new Store(null) {
    @Override
    public Map<String, Object> one(String sql, Object... args) {
      return Map.of("id", 1L);
    }
  };
  final Auth auth = new Auth(store) {
    @Override
    public void member(HttpSession s) {
      if (!allowed.get()) Auth.denied("身份未通过");
    }

    @Override
    public void approved(HttpSession s, long id) {
      if (!allowed.get()) Auth.denied("报名已撤销");
    }

    @Override
    public long id(HttpSession s) {
      return 1;
    }
  };
  final List<AiKnowledge.Source> sources = List.of(
    new AiKnowledge.Source(
      "TIME1",
      "time",
      "会议时间",
      "会议明天九点开始",
      "/?event=1"
    )
  );
  final AiParticipant participant = new AiParticipant(store) {
    @Override
    public Direct direct(String question, long user, String model) {
      return null;
    }

    @Override
    public List<AiKnowledge.Source> context(
      String question,
      List<AiKnowledge.Source> selected
    ) {
      return selected;
    }
  };

  AiKnowledge knowledge(List<AiKnowledge.Source> selected) {
    return new AiKnowledge(store) {
      @Override
      public List<Source> search(long id, String question) {
        return selected;
      }
    };
  }

  @Test
  void missingConfigurationFallsBackToActualMaterials() {
    var api = new AiApi(
      store,
      auth,
      knowledge(sources),
      new AiModelClient("", "model", "", 1),
      participant
    );
    var reply = api.ask(
      1,
      new AiApi.Ask("什么时候开始"),
      new MockHttpSession()
    );
    assertEquals("faq", reply.mode());
    assertTrue(reply.answer().contains("明天九点"));
    assertEquals(sources, reply.sources());
    assertTrue(reply.model().isEmpty());
  }

  @Test
  void emptyRetrievalRefusesWithoutCallingProvider() {
    var client = new AiModelClient("", "model", "", 1) {
      @Override
      public boolean configured() {
        throw new AssertionError("Provider must not be consulted");
      }
    };
    var reply = new AiApi(
      store,
      auth,
      knowledge(List.of()),
      client,
      participant
    ).ask(1, new AiApi.Ask("火星暗号"), new MockHttpSession());
    assertEquals("refused", reply.mode());
    assertTrue(reply.sources().isEmpty());
  }

  @Test
  void rechecksPermissionAndReleasesBusySlot() throws Exception {
    CountDownLatch entered = new CountDownLatch(1),
      release = new CountDownLatch(1);
    var client = new AiModelClient("", "model", "", 1) {
      @Override
      public boolean configured() {
        return true;
      }

      @Override
      public Answer answer(String question, List<AiKnowledge.Source> sources) {
        entered.countDown();
        try {
          if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError(
            "Test did not release provider"
          );
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
        return new Answer(true, "明天九点", List.of("TIME1"));
      }
    };
    var api = new AiApi(store, auth, knowledge(sources), client, participant);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      var future = executor.submit(() ->
        api.ask(1, new AiApi.Ask("什么时候开始"), new MockHttpSession())
      );
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals(
        429,
        assertThrows(ResponseStatusException.class, () ->
          api.ask(1, new AiApi.Ask("重复请求"), new MockHttpSession())
        )
          .getStatusCode()
          .value()
      );
      allowed.set(false);
      release.countDown();
      var failure = assertThrows(ExecutionException.class, () ->
        future.get(3, TimeUnit.SECONDS)
      );
      assertEquals(
        403,
        ((ResponseStatusException) failure.getCause()).getStatusCode().value()
      );
      allowed.set(true);
      assertEquals(
        "model",
        api.ask(1, new AiApi.Ask("什么时候开始"), new MockHttpSession()).mode()
      );
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void invalidatedSessionBecomesUnauthorized() {
    var session = new MockHttpSession();
    session.setAttribute("uid", 1L);
    session.invalidate();
    assertEquals(
      401,
      assertThrows(ResponseStatusException.class, () ->
        new Auth(store).id(session)
      )
        .getStatusCode()
        .value()
    );
  }

  @Test
  void platformIdentityWorksWithoutApprovedConferenceOrModelRequest() {
    var client = new AiModelClient("", "configured-model", "", 1) {
      @Override
      public Answer answer(String q, List<AiKnowledge.Source> sources) {
        throw new AssertionError("Local identity must not call provider");
      }
    };
    var api = new AiApi(
      store,
      auth,
      knowledge(List.of()),
      client,
      new AiParticipant(store)
    );
    var reply = api.platformAsk(
      new AiApi.PlatformAsk("你是什么模型", null),
      new MockHttpSession()
    );
    assertEquals("platform", reply.mode());
    assertTrue(reply.answer().contains("configured-model"));
  }

  @Test
  void platformWorkflowUsesRulesWithoutConferenceKeywordMatch() {
    var client = new AiModelClient("", "model", "", 1) {
      @Override
      public boolean configured() {
        return true;
      }

      @Override
      public Answer answer(String q, List<AiKnowledge.Source> sources) {
        assertTrue(
          sources.stream().anyMatch(s -> s.id().equals("PLATFORM_ACCOUNT"))
        );
        return new Answer(
          true,
          "在个人主页修改密码。",
          List.of("PLATFORM_ACCOUNT")
        );
      }
    };
    var reply = new AiApi(
      store,
      auth,
      knowledge(List.of()),
      client,
      new AiParticipant(store)
    ).platformAsk(
      new AiApi.PlatformAsk("我的账号怎么修改密码", null),
      new MockHttpSession()
    );
    assertEquals("model", reply.mode());
    assertTrue(reply.answer().contains("个人主页"));
  }
}

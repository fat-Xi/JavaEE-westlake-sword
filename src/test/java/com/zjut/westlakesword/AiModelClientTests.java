package com.zjut.westlakesword;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class AiModelClientTests {

  HttpServer server;
  ExecutorService executor;
  final JsonMapper json = JsonMapper.builder().build();
  final AtomicReference<String> body = new AtomicReference<>(),
    authorization = new AtomicReference<>();
  final AtomicReference<String> content = new AtomicReference<>(
    "{\"answerable\":true,\"answer\":\"会议在杭州举行。\",\"source_ids\":[\"VENUE1\"]}"
  );
  final AtomicInteger status = new AtomicInteger(200),
    calls = new AtomicInteger();
  final AtomicLong delay = new AtomicLong();
  final List<AiKnowledge.Source> sources = List.of(
    new AiKnowledge.Source("VENUE1", "venue", "会议地点", "杭州", "/?event=1")
  );

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext("/v1/chat/completions", exchange -> {
      try {
        calls.incrementAndGet();
        authorization.set(
          exchange.getRequestHeaders().getFirst("Authorization")
        );
        body.set(
          new String(
            exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8
          )
        );
        if (delay.get() > 0) Thread.sleep(delay.get());
        byte[] response = json.writeValueAsBytes(
          status.get() == 200
            ? Map.of(
                "choices",
                List.of(Map.of("message", Map.of("content", content.get())))
              )
            : Map.of("error", "do not expose test-key")
        );
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Location", "/never-follow");
        exchange.sendResponseHeaders(status.get(), response.length);
        exchange.getResponseBody().write(response);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  AiModelClient client(int timeout) {
    return new AiModelClient(
      "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
      "mock-meeting-model",
      "test-key",
      timeout
    );
  }

  @Test
  void sendsCompatibleRequestAndReturnsValidatedSources() {
    var answer = client(3).answer("会场在哪里？", sources);
    assertTrue(answer.answerable());
    assertEquals("会议在杭州举行。", answer.answer());
    assertEquals(List.of("VENUE1"), answer.sourceIds());
    assertEquals("Bearer test-key", authorization.get());
    var request = json.readTree(body.get());
    assertEquals("mock-meeting-model", request.path("model").asText());
    assertFalse(request.path("stream").asBoolean());
    assertTrue(
      request
        .path("messages")
        .path(0)
        .path("content")
        .asText()
        .contains("不可信")
    );
    assertFalse(request.path("messages").toString().contains("test-key"));
    assertEquals(1, calls.get());
  }

  @Test
  void neverCallsNetworkWithoutCredentials() {
    var client = new AiModelClient("https://example.com/v1", "model", "", 2);
    assertFalse(client.configured());
    assertEquals(
      "not_configured",
      assertThrows(AiModelClient.Unavailable.class, () ->
        client.answer("地点", sources)
      ).getMessage()
    );
    assertEquals(0, calls.get());
  }

  @Test
  void refusesNonHttpsRemoteEndpoints() {
    assertFalse(
      new AiModelClient(
        "http://example.com/v1",
        "model",
        "test-key",
        2
      ).configured()
    );
  }

  @Test
  void rejectsInventedCitations() {
    assertEquals(
      "invalid_citation",
      assertThrows(AiModelClient.Unavailable.class, () ->
        client(2).parse(
          "{\"answerable\":true,\"answer\":\"编造\",\"source_ids\":[\"OTHER\"]}",
          sources
        )
      ).getMessage()
    );
  }

  @Test
  void rejectsMissingSourcesAndMalformedAnswers() {
    for (String response : List.of(
      "不是JSON",
      "{\"answerable\":true,\"answer\":\"回答\",\"source_ids\":[]}",
      "{\"answerable\":true,\"answer\":123,\"source_ids\":[\"VENUE1\"]}"
    )) {
      assertThrows(AiModelClient.Unavailable.class, () ->
        client(2).parse(response, sources)
      );
    }
  }

  @Test
  void acceptsRefusalAndFencedJson() {
    assertFalse(
      client(2).parse("{\"answerable\":false}", sources).answerable()
    );
    assertTrue(
      client(2)
        .parse("```json\n" + content.get() + "\n```", sources)
        .answerable()
    );
  }

  @Test
  void upstreamErrorsNeverExposeResponseBody() {
    status.set(401);
    var error = assertThrows(AiModelClient.Unavailable.class, () ->
      client(2).answer("地点", sources)
    );
    assertEquals("http_401", error.getMessage());
    assertFalse(error.toString().contains("test-key"));
  }

  @Test
  void doesNotFollowRedirectsWithCredentials() {
    status.set(302);
    assertEquals(
      "http_302",
      assertThrows(AiModelClient.Unavailable.class, () ->
        client(2).answer("地点", sources)
      ).getMessage()
    );
    assertEquals(1, calls.get());
  }

  @Test
  void timesOutInsteadOfBlockingIndefinitely() {
    delay.set(1800);
    assertEquals(
      "timeout",
      assertThrows(AiModelClient.Unavailable.class, () ->
        client(1).answer("地点", sources)
      ).getMessage()
    );
  }
}

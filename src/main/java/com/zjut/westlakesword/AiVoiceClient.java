package com.zjut.westlakesword;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AiVoiceClient {

  private static final String ASR_MODEL = "qwen-audio-3.1-asr-flash-streaming";
  private static final String TTS_MODEL = "qwen-audio-3.1-tts-plus";
  static final int MAX_RECORDING_BYTES = 30 * 16000 * 2;
  private static final int MAX_AUDIO_BYTES = 16 * 1024 * 1024;
  private static final long SPEECH_IDLE_NANOS = TimeUnit.SECONDS.toNanos(20);
  private static final long SPEECH_TOTAL_NANOS = TimeUnit.MINUTES.toNanos(8);
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();
  private final URI asrUrl;
  private final URI ttsUrl;
  private final String key;
  private final String voice;
  private final ScheduledExecutorService timeouts =
    Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "ai-voice-timeout");
      thread.setDaemon(true);
      return thread;
    });

  public AiVoiceClient(
    @Value("${app.ai.voice.enabled:true}") boolean enabled,
    @Value("${app.ai.base-url:}") String chatUrl,
    @Value("${app.ai.api-key:}") String key,
    @Value("${app.ai.voice.asr-url:}") String asrUrl,
    @Value("${app.ai.voice.tts-url:}") String ttsUrl,
    @Value("${app.ai.voice.voice:longanlingxin_v3.1}") String voice
  ) {
    this.key = key.strip();
    this.voice = voice.strip();
    URI base = endpoint(chatUrl, "https", "http");
    String workspace =
      base != null && base.getHost().endsWith(".maas.aliyuncs.com")
        ? base.getHost()
        : "";
    this.asrUrl = enabled
      ? endpoint(
          asrUrl.isBlank() && !workspace.isEmpty()
            ? "wss://" + workspace + "/api-ws/v1/inference"
            : asrUrl,
          "wss",
          "ws"
        )
      : null;
    this.ttsUrl = enabled
      ? endpoint(
          ttsUrl.isBlank() && !workspace.isEmpty()
            ? "https://" +
                workspace +
                "/api/v1/services/audio/tts/SpeechSynthesizer"
            : ttsUrl,
          "https",
          "http"
        )
      : null;
  }

  boolean canTranscribe() {
    return asrUrl != null && !key.isBlank();
  }

  boolean canSynthesize() {
    return ttsUrl != null && !key.isBlank() && !voice.isBlank();
  }

  private static boolean loopback(URI uri) {
    return Set.of("127.0.0.1", "localhost").contains(uri.getHost());
  }

  private static URI endpoint(String value, String secure, String local) {
    try {
      URI uri = URI.create(value.strip());
      if (
        uri.getHost() != null &&
        uri.getUserInfo() == null &&
        uri.getFragment() == null &&
        uri.getQuery() == null &&
        (secure.equals(uri.getScheme()) ||
          (local.equals(uri.getScheme()) && loopback(uri)))
      ) return uri;
    } catch (IllegalArgumentException ignored) {
      // A missing or invalid endpoint leaves that feature unavailable.
    }
    return null;
  }

  String transcribe(byte[] pcm) {
    try (var stream = openRecognition()) {
      for (int offset = 0; offset < pcm.length; offset += 3200) {
        stream.send(
          ByteBuffer.wrap(pcm, offset, Math.min(3200, pcm.length - offset))
        );
        if (offset + 3200 < pcm.length) Thread.sleep(100);
      }
      return stream.finish();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failure("语音识别已中断");
    }
  }

  RecognitionStream openRecognition() {
    var taskId = UUID.randomUUID().toString().replace("-", "");
    var listener = new Recognition(taskId);
    var connecting = http
      .newWebSocketBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .header("Authorization", "Bearer " + key)
      .buildAsync(asrUrl, listener);
    WebSocket socket = awaitRecognition(connecting, 6);
    try {
      awaitRecognition(
        socket.sendText(
          command(
            "run-task",
            taskId,
            Map.of(
              "task_group",
              "audio",
              "task",
              "asr",
              "function",
              "recognition",
              "model",
              ASR_MODEL,
              "parameters",
              Map.of("format", "pcm", "sample_rate", 16000),
              "input",
              Map.of()
            )
          ),
          true
        ),
        5
      );
      awaitRecognition(listener.started, 8);
      return new RecognitionStream(socket, listener);
    } catch (RuntimeException e) {
      socket.abort();
      throw e;
    }
  }

  final class RecognitionStream implements AutoCloseable {

    private final WebSocket socket;
    private final Recognition listener;

    RecognitionStream(WebSocket socket, Recognition listener) {
      this.socket = socket;
      this.listener = listener;
    }

    void send(ByteBuffer pcm) {
      if (listener.result.isDone()) {
        awaitRecognition(listener.result, 1);
        return;
      }
      awaitRecognition(socket.sendBinary(pcm, true), 5);
    }

    String finish() {
      if (!listener.result.isDone()) awaitRecognition(
        socket.sendText(
          command("finish-task", listener.taskId, Map.of("input", Map.of())),
          true
        ),
        5
      );
      return awaitRecognition(listener.result, 10).strip();
    }

    @Override
    public void close() {
      socket.abort();
    }
  }

  private static <T> T awaitRecognition(
    CompletableFuture<T> future,
    int seconds
  ) {
    try {
      return future.get(seconds, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Failure failure) throw failure;
      if (e.getCause() instanceof WebSocketHandshakeException handshake) {
        throw providerFailure(handshake.getResponse().statusCode(), "");
      }
      throw new Failure("语音识别服务暂时不可用");
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new Failure("语音识别超时，请重试或直接输入文字");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failure("语音识别已中断");
    }
  }

  private String command(
    String action,
    String taskId,
    Map<String, Object> payload
  ) {
    return json.writeValueAsString(
      Map.of(
        "header",
        Map.of("action", action, "task_id", taskId, "streaming", "duplex"),
        "payload",
        payload
      )
    );
  }

  private final class Recognition implements WebSocket.Listener {

    private final String taskId;
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final StringBuilder frame = new StringBuilder();
    private final Map<Long, String> sentences = new TreeMap<>();

    Recognition(String taskId) {
      this.taskId = taskId;
    }

    @Override
    public CompletionStage<?> onText(
      WebSocket socket,
      CharSequence data,
      boolean last
    ) {
      try {
        if (
          frame.length() + data.length() > 65536
        ) throw new IllegalArgumentException();
        frame.append(data);
        if (last) {
          var event = json.readTree(frame.toString());
          frame.setLength(0);
          var header = event.path("header");
          String type = header.path("event").asText("");
          if (
            !taskId.equals(header.path("task_id").asText("")) &&
            !"task-failed".equals(type)
          ) {
            throw new IllegalArgumentException();
          }
          switch (type) {
            case "task-started" -> started.complete(null);
            case "result-generated" -> {
              var sentence = event
                .path("payload")
                .path("output")
                .path("sentence");
              if (sentence.path("sentence_end").asBoolean(false)) {
                long id = sentence.path("sentence_id").asLong(sentences.size());
                sentences.put(id, sentence.path("text").asText(""));
              }
            }
            case "task-finished" -> result.complete(
              String.join("", sentences.values())
            );
            case "task-failed" -> fail(
              providerFailure(0, header.path("error_code").asText(""))
            );
            default -> {
              /* The provider may send additional events. */
            }
          }
        }
        socket.request(1);
      } catch (RuntimeException e) {
        fail(new Failure("语音识别结果格式异常"));
        socket.abort();
      }
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
      fail(new Failure("语音识别连接中断"));
    }

    @Override
    public CompletionStage<?> onClose(
      WebSocket socket,
      int status,
      String reason
    ) {
      fail(new Failure("语音识别连接已关闭"));
      return null;
    }

    private void fail(Failure error) {
      started.completeExceptionally(error);
      result.completeExceptionally(error);
    }
  }

  byte[] synthesize(String text) {
    try {
      var response = request(speechRequest(text, "wav", false), 25);
      if (response.body().length > 65536) throw new Failure(
        "语音合成结果格式异常"
      );
      var result = json.readTree(response.body());
      if (response.statusCode() != 200) throw providerFailure(
        response.statusCode(),
        result.path("code").asText("")
      );
      URI audioUrl = audioUri(
        result.path("output").path("audio").path("url").asText("")
      );
      var download = request(
        HttpRequest.newBuilder(audioUrl).GET().build(),
        15
      );
      byte[] audio = download.body();
      if (
        download.statusCode() != 200 ||
        audio.length < 44 ||
        audio.length > MAX_AUDIO_BYTES ||
        audio[0] != 'R' ||
        audio[1] != 'I' ||
        audio[2] != 'F' ||
        audio[3] != 'F' ||
        audio[8] != 'W' ||
        audio[9] != 'A' ||
        audio[10] != 'V' ||
        audio[11] != 'E'
      ) {
        throw new Failure("朗读音频暂时无法加载");
      }
      return audio;
    } catch (Failure e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failure("语音合成已中断");
    } catch (TimeoutException e) {
      throw new Failure("语音合成超时，请重试");
    } catch (Exception e) {
      throw new Failure("语音合成服务暂时不可用");
    }
  }

  private HttpRequest speechRequest(
    String text,
    String format,
    boolean streaming
  ) {
    var body = Map.of(
      "model",
      TTS_MODEL,
      "input",
      Map.of(
        "text",
        text,
        "voice",
        voice,
        "format",
        format,
        "sample_rate",
        16000
      )
    );
    var request = HttpRequest.newBuilder(ttsUrl)
      .header("Authorization", "Bearer " + key)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    if (streaming) request.header("X-DashScope-SSE", "enable");
    return request.build();
  }

  void streamSpeech(String text, Consumer<String> audio) {
    var pending = http.sendAsync(
      speechRequest(text, "pcm", true),
      HttpResponse.BodyHandlers.ofInputStream()
    );
    try {
      var response = pending.get(15, TimeUnit.SECONDS);
      try (var input = response.body()) {
        long startedAt = System.nanoTime();
        var lastAudioAt = new AtomicLong(startedAt);
        var timedOut = new AtomicBoolean();
        var timeout = timeouts.scheduleAtFixedRate(
          () -> {
            long now = System.nanoTime();
            if (
              now - lastAudioAt.get() < SPEECH_IDLE_NANOS &&
              now - startedAt < SPEECH_TOTAL_NANOS
            ) return;
            timedOut.set(true);
            try {
              input.close();
            } catch (IOException ignored) {}
          },
          1,
          1,
          TimeUnit.SECONDS
        );
        try {
          if (
            response.statusCode() != 200 ||
            !response
              .headers()
              .firstValue("Content-Type")
              .orElse("")
              .contains("text/event-stream")
          ) {
            String code = "";
            try {
              code = json
                .readTree(input.readNBytes(65536))
                .path("code")
                .asText("");
            } catch (RuntimeException ignored) {
              /* Keep the HTTP error classification. */
            }
            throw providerFailure(response.statusCode(), code);
          }
          readSpeech(input, chunk -> {
            lastAudioAt.set(System.nanoTime());
            audio.accept(chunk);
          });
        } catch (IOException | Failure e) {
          if (timedOut.get()) throw new Failure("语音合成超时，请重试");
          throw e;
        } finally {
          timeout.cancel(false);
        }
      }
    } catch (Failure | UncheckedIOException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failure("语音合成已中断");
    } catch (TimeoutException e) {
      throw new Failure("语音合成超时，请重试");
    } catch (IOException | ExecutionException e) {
      throw new Failure("语音合成连接中断，请重试");
    } finally {
      if (!pending.isDone()) pending.cancel(true);
    }
  }

  private void readSpeech(InputStream input, Consumer<String> audio)
    throws IOException {
    var lines = new BufferedReader(
      new InputStreamReader(input, StandardCharsets.UTF_8)
    );
    var event = new StringBuilder();
    int bytes = 0;
    for (String line; (line = lines.readLine()) != null; ) {
      if (line.startsWith("data:")) {
        event.append(line.substring(5).stripLeading());
        if (event.length() > MAX_AUDIO_BYTES * 2) throw new Failure(
          "朗读音频过长"
        );
      } else if (line.isEmpty() && !event.isEmpty()) {
        tools.jackson.databind.JsonNode message;
        try {
          message = json.readTree(event.toString());
        } catch (RuntimeException e) {
          throw new Failure("语音模型返回了无效的合成结果");
        }
        event.setLength(0);
        String code = message.path("code").asText("");
        if (!code.isEmpty() && !"Success".equals(code)) throw providerFailure(
          0,
          code
        );
        var output = message.path("output");
        String chunk = output.path("audio").path("data").asText("");
        if (!chunk.isEmpty()) {
          try {
            bytes += Base64.getDecoder().decode(chunk).length;
          } catch (IllegalArgumentException e) {
            throw new Failure("语音模型返回了无效的音频数据");
          }
          if (bytes > MAX_AUDIO_BYTES) throw new Failure("朗读音频过长");
          audio.accept(chunk);
        }
        if ("stop".equals(output.path("finish_reason").asText(""))) {
          if (bytes == 0) throw new Failure("语音模型没有返回音频");
          return;
        }
      }
    }
    throw new Failure("语音合成连接提前结束，请重试");
  }

  @PreDestroy
  void stopTimeouts() {
    timeouts.shutdownNow();
  }

  private HttpResponse<byte[]> request(HttpRequest request, int seconds)
    throws InterruptedException, ExecutionException, TimeoutException {
    var pending = http.sendAsync(
      request,
      HttpResponse.BodyHandlers.ofByteArray()
    );
    try {
      return pending.get(seconds, TimeUnit.SECONDS);
    } finally {
      if (!pending.isDone()) pending.cancel(true);
    }
  }

  private URI audioUri(String value) {
    URI uri = URI.create(value);
    if (
      uri.getHost() != null &&
      uri.getUserInfo() == null &&
      uri.getFragment() == null
    ) {
      if (
        loopback(ttsUrl) &&
        "http".equals(uri.getScheme()) &&
        ttsUrl.getAuthority().equals(uri.getAuthority())
      ) return uri;
      if (
        uri.getPort() == -1 &&
        uri
          .getHost()
          .matches(
            "dashscope-result[-a-z0-9]*\\.oss-[a-z0-9-]+\\.aliyuncs\\.com"
          )
      ) {
        if ("https".equals(uri.getScheme())) return uri;
        if ("http".equals(uri.getScheme())) return URI.create(
          "https" + value.substring(4)
        );
      }
    }
    throw new Failure("语音合成返回了无效的音频地址");
  }

  private static Failure providerFailure(int status, String code) {
    if (
      code.equals("AllocationQuota.FreeTierOnly") ||
      code.contains("QuotaExhausted")
    ) {
      return new Failure("语音模型的免费额度已用完或到期");
    }
    if (status == 429 || code.startsWith("Throttling")) return new Failure(
      "语音服务调用过于频繁，请稍后重试"
    );
    if (
      status == 401 ||
      status == 403 ||
      code.equals("AccessDenied") ||
      code.equals("InvalidApiKey")
    ) {
      return new Failure(
        "当前业务空间无法调用语音模型，请检查模型权限及剩余额度"
      );
    }
    return new Failure("语音服务暂时不可用，请稍后重试");
  }

  static class Failure extends RuntimeException {

    Failure(String message) {
      super(message);
    }
  }
}

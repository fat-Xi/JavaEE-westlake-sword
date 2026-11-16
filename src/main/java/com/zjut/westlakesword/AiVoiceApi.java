package com.zjut.westlakesword;

import jakarta.servlet.http.*;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/ai/voice")
public class AiVoiceApi {

  private static final int AUDIO_FRAME_CHARS = 4096;

  private final Auth auth;
  private final AiApi ai;
  private final AiVoiceClient voice;
  private final Set<Long> active = ConcurrentHashMap.newKeySet();
  private final Semaphore slots = new Semaphore(4);
  private final JsonMapper json = JsonMapper.builder().build();

  public AiVoiceApi(Auth auth, AiApi ai, AiVoiceClient voice) {
    this.auth = auth;
    this.ai = ai;
    this.voice = voice;
  }

  @GetMapping("/status")
  public Map<String, Boolean> status(HttpSession session) {
    auth.member(session);
    return Map.of(
      "transcribe",
      voice.canTranscribe(),
      "synthesize",
      voice.canSynthesize()
    );
  }

  @GetMapping("/live")
  public void live(
    HttpServletRequest request,
    HttpServletResponse response,
    HttpSession session
  ) throws IOException, DeploymentException {
    auth.member(session);
    if (!sameOrigin(request)) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "语音连接须来自本站"
    );
    if (!voice.canTranscribe()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "语音输入暂不可用"
    );
    var container = request
      .getServletContext()
      .getAttribute(ServerContainer.class.getName());
    if (!(container instanceof ServerContainer websocket)) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "实时语音连接暂不可用"
      );
    }
    var endpoint = new LiveRecognition(session);
    var config = ServerEndpointConfig.Builder.create(
      Endpoint.class,
      "/api/ai/voice/live"
    )
      .configurator(
        new ServerEndpointConfig.Configurator() {
          @Override
          public <T> T getEndpointInstance(Class<T> type) {
            return type.cast(endpoint);
          }
        }
      )
      .build();
    websocket.upgradeHttpToWebSocket(request, response, config, Map.of());
  }

  private static boolean sameOrigin(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    if (origin == null) return false;
    try {
      URI uri = URI.create(origin);
      return (
        ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) &&
        uri.getHost() != null &&
        uri.getUserInfo() == null &&
        uri.getRawAuthority().equalsIgnoreCase(request.getHeader("Host"))
      );
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private final class LiveRecognition extends Endpoint {

    private final HttpSession session;
    private Runnable release;
    private AiVoiceClient.RecognitionStream upstream;
    private int received;
    private long deadline;

    LiveRecognition(HttpSession session) {
      this.session = session;
    }

    @Override
    public synchronized void onOpen(Session socket, EndpointConfig config) {
      try {
        auth.member(session);
        release = reserveVoiceSlot(session);
        upstream = voice.openRecognition();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        socket.setMaxIdleTimeout(12000);
        socket.setMaxBinaryMessageBufferSize(3200);
        socket.setMaxTextMessageBufferSize(32);
        socket.addMessageHandler(ByteBuffer.class, frame ->
          audio(socket, frame)
        );
        socket.addMessageHandler(String.class, message ->
          finish(socket, message)
        );
        send(socket, Map.of("ready", true));
      } catch (Exception e) {
        fail(socket, e);
      }
    }

    private synchronized void audio(Session socket, ByteBuffer frame) {
      if (upstream == null) return;
      try {
        int size = frame.remaining();
        received += size;
        if (
          size == 0 ||
          size > 3200 ||
          size % 2 != 0 ||
          received > AiVoiceClient.MAX_RECORDING_BYTES ||
          System.nanoTime() > deadline
        ) {
          throw new AiVoiceClient.Failure(
            "录音已超过 30 秒或格式无效，请重新录制"
          );
        }
        upstream.send(frame);
      } catch (RuntimeException e) {
        fail(socket, e);
      }
    }

    private synchronized void finish(Session socket, String message) {
      if (upstream == null) return;
      try {
        if (
          !"finish".equals(message) || received == 0
        ) throw new AiVoiceClient.Failure("没有录到声音，请重新录制");
        auth.member(session);
        String text = upstream.finish();
        auth.member(session);
        if (text.isBlank()) throw new AiVoiceClient.Failure(
          "没有识别到语音，请重新录制"
        );
        send(socket, Map.of("text", text));
        close(socket);
      } catch (Exception e) {
        fail(socket, e);
      }
    }

    private void send(Session socket, Map<String, ?> message)
      throws IOException {
      socket.getBasicRemote().sendText(json.writeValueAsString(message));
    }

    private void fail(Session socket, Exception error) {
      try {
        send(socket, Map.of("error", voiceError(error)));
      } catch (IOException | RuntimeException ignored) {
        /* Client may have disconnected. */
      } finally {
        close(socket);
      }
    }

    private void close(Session socket) {
      cleanup();
      try {
        if (socket.isOpen()) socket.close();
      } catch (IOException ignored) {
        /* Connection is already ending. */
      }
    }

    private synchronized void cleanup() {
      if (upstream != null) {
        upstream.close();
        upstream = null;
      }
      if (release != null) {
        release.run();
        release = null;
      }
    }

    @Override
    public void onClose(Session socket, CloseReason reason) {
      cleanup();
    }

    @Override
    public synchronized void onError(Session socket, Throwable error) {
      close(socket);
    }
  }

  @PostMapping(
    value = "/transcribe",
    consumes = MediaType.MULTIPART_FORM_DATA_VALUE
  )
  public Map<String, String> transcribe(
    @RequestParam("audio") MultipartFile audio,
    HttpSession session
  ) throws IOException {
    auth.member(session);
    if (
      audio.isEmpty() ||
      audio.getSize() % 2 != 0 ||
      !"application/octet-stream".equals(audio.getContentType())
    ) {
      throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "请重新录制有效的语音"
      );
    }
    if (audio.getSize() > AiVoiceClient.MAX_RECORDING_BYTES) {
      throw new ResponseStatusException(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "每次录音最多 30 秒"
      );
    }
    if (!voice.canTranscribe()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "语音输入暂不可用"
    );
    byte[] pcm = audio.getBytes();
    return withVoiceSlot(session, () -> {
      String text = voice.transcribe(pcm);
      auth.member(session);
      if (text.isBlank()) throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "没有识别到语音，请重新录制"
      );
      return Map.of("text", text);
    });
  }

  record Synthesis(String replyId) {}

  @PostMapping("/synthesize")
  public ResponseEntity<byte[]> synthesize(
    @RequestBody Synthesis request,
    HttpSession session
  ) {
    auth.member(session);
    String replyId = Store.word(request.replyId(), 36, "回答编号");
    String answer = ai.voiceAnswer(replyId, session);
    if (!voice.canSynthesize()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "朗读暂不可用"
    );
    return withVoiceSlot(session, () -> {
      byte[] audio = voice.synthesize(answer);
      ai.voiceAnswer(replyId, session);
      return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("audio/wav"))
        .cacheControl(CacheControl.noStore())
        .body(audio);
    });
  }

  @PostMapping("/synthesize/stream")
  public ResponseEntity<StreamingResponseBody> synthesizeStream(
    @RequestBody Synthesis request,
    HttpSession session
  ) {
    auth.member(session);
    String replyId = Store.word(request.replyId(), 36, "回答编号");
    ai.voiceAnswer(replyId, session);
    if (!voice.canSynthesize()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "朗读暂不可用"
    );
    StreamingResponseBody body = output -> {
      Runnable release = null;
      try {
        String answer = ai.voiceAnswer(replyId, session);
        release = reserveVoiceSlot(session);
        long[] nextCheck = { 0 };
        voice.streamSpeech(answer, chunk -> {
          long now = System.nanoTime();
          if (now >= nextCheck[0]) {
            ai.voiceAnswer(replyId, session);
            nextCheck[0] = now + TimeUnit.SECONDS.toNanos(1);
          }
          // Complete, small NDJSON lines can be consumed before the rest of the provider chunk.
          for (int start = 0; start < chunk.length(); start += AUDIO_FRAME_CHARS) {
            int end = Math.min(start + AUDIO_FRAME_CHARS, chunk.length());
            writeFrame(output, Map.of("audio", chunk.substring(start, end)));
          }
        });
        ai.voiceAnswer(replyId, session);
        writeFrame(output, Map.of("done", true));
      } catch (UncheckedIOException e) {
        throw e.getCause();
      } catch (AiVoiceClient.Failure | ResponseStatusException e) {
        writeFrame(output, Map.of("error", voiceError(e)));
      } finally {
        if (release != null) release.run();
      }
    };
    return ResponseEntity.ok()
      .contentType(MediaType.parseMediaType("application/x-ndjson"))
      .cacheControl(CacheControl.noStore())
      .header("X-Accel-Buffering", "no")
      .body(body);
  }

  private void writeFrame(OutputStream output, Map<String, ?> frame) {
    try {
      output.write(
        (json.writeValueAsString(frame) + "\n").getBytes(StandardCharsets.UTF_8)
      );
      output.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String voiceError(Exception error) {
    if (
      error instanceof AiVoiceClient.Failure failure
    ) return failure.getMessage();
    if (
      error instanceof ResponseStatusException response &&
      response.getReason() != null
    ) return response.getReason();
    return "实时语音连接中断，请重试";
  }

  private <T> T withVoiceSlot(HttpSession session, Supplier<T> action) {
    Runnable release = reserveVoiceSlot(session);
    try {
      return action.get();
    } catch (AiVoiceClient.Failure e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    } finally {
      release.run();
    }
  }

  private Runnable reserveVoiceSlot(HttpSession session) {
    long user = auth.id(session);
    if (!active.add(user)) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "已有语音请求正在处理"
    );
    if (!slots.tryAcquire()) {
      active.remove(user);
      throw new ResponseStatusException(
        HttpStatus.TOO_MANY_REQUESTS,
        "语音服务繁忙，请稍后重试"
      );
    }
    return () -> {
      slots.release();
      active.remove(user);
    };
  }
}

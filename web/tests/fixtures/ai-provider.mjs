// Local deterministic provider for integration tests. Never uses a real API key.
import http from "node:http";
const port = Number(process.env.AI_MOCK_PORT || 18765);
let control = { mode: "ok", delay: 0 },
  calls = 0,
  last = null;
const server = http.createServer(async (req, res) => {
  const send = (status, body) => {
    if (res.destroyed || res.writableEnded) return;
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body));
  };
  try {
    let raw = "";
    for await (const chunk of req) {
      raw += chunk;
      if (raw.length > 1000000) {
        send(413, {});
        return;
      }
    }
    if (req.url === "/control" && req.method === "POST") {
      control = { mode: "ok", delay: 0, ...JSON.parse(raw || "{}") };
      if (control.reset) {
        calls = 0;
        last = null;
      }
      send(200, { ready: true });
      return;
    }
    if (req.url === "/state") {
      send(200, { calls, last });
      return;
    }
    if (req.url !== "/v1/chat/completions") {
      send(404, {});
      return;
    }
    const payload = JSON.parse(raw),
      current = { ...control };
    if (
      payload.model !== "mock-meeting-model" ||
      req.headers.authorization !== "Bearer test-ai-key"
    ) {
      send(401, { error: "Test credentials required" });
      return;
    }
    calls++;
    last = { model: payload.model, messages: payload.messages };
    const user = JSON.parse(payload.messages[1].content),
      first = user.materials[0];
    if (current.delay) await new Promise((r) => setTimeout(r, current.delay));
    if (current.mode === "http_error") {
      send(503, { error: "Synthetic provider failure" });
      return;
    }
    const answer =
      current.mode === "html"
        ? "<img src=x onerror=alert(1)>只作为文字展示"
        : "根据已公开资料：" + first.text.slice(0, 180);
    const content =
      current.mode === "malformed"
        ? "not a JSON answer"
        : JSON.stringify({
            answerable: current.mode !== "refuse",
            answer,
            source_ids:
              current.mode === "unknown_citation" ? ["INVENTED"] : [first.id],
          });
    send(200, { choices: [{ message: { content } }] });
  } catch {
    send(400, { error: "Invalid test request" });
  }
});
server.listen(port, "127.0.0.1", () =>
  process.stdout.write(`AI test provider ready on ${port}\n`),
);

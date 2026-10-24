package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class FieldApi {

  private final Store store;
  private final Auth auth;
  private final Path root;

  public FieldApi(
    Store store,
    Auth auth,
    @Value("${app.upload}") String upload
  ) {
    this.store = store;
    this.auth = auth;
    this.root = Path.of(upload).toAbsolutePath().normalize();
  }

  record Scan(long eventId, String token) {}

  @GetMapping("/events/{id}/ticket")
  @Transactional
  public Map<String, Object> ticket(@PathVariable long id, HttpSession s) {
    // 票据创建与取消报名共用活动行锁；令牌不包含可猜测的用户ID。
    store.one("SELECT id FROM events WHERE id=? FOR UPDATE", id);
    auth.approved(s, id);
    long joinId = Store.num(
      store.one(
        "SELECT id FROM joins WHERE user_id=? AND event_id=?",
        auth.id(s),
        id
      ),
      "id"
    );
    store.db.update(
      "INSERT IGNORE INTO tickets(join_id,token) VALUES(?,?)",
      joinId,
      UUID.randomUUID().toString()
    );
    return store.one(
      "SELECT token,checked FROM tickets WHERE join_id=?",
      joinId
    );
  }

  @PostMapping("/checkin")
  @Transactional
  public Map<String, Object> scan(@RequestBody Scan scan, HttpSession s) {
    if (!"STAFF".equals(auth.role(s))) Auth.denied("仅工作人员执行签到");
    auth.need(s, "checkin");
    Api.check(false, "旧签到接口已停用，请选择管理员发布的议程签到场次");
    return Map.of();
  }

  @GetMapping("/checkins")
  public List<Map<String, Object>> checkins(
    @RequestParam long eventId,
    HttpSession s
  ) {
    auth.need(s, "checkin");
    return store.db.queryForList(
      "SELECT u.name,t.checked,j.event_id FROM tickets t JOIN joins j ON j.id=t.join_id JOIN users u ON u.id=j.user_id WHERE j.event_id=? AND t.checked IS NOT NULL ORDER BY t.checked DESC",
      eventId
    );
  }

  @GetMapping("/events/{id}/files")
  public List<Map<String, Object>> files(@PathVariable long id, HttpSession s) {
    if (!"ADMIN".equals(auth.role(s))) auth.approved(s, id);
    store.one("SELECT id FROM events WHERE id=? AND status='OPEN'", id);
    return store.db.queryForList(
      "SELECT id,name,size,created FROM files WHERE event_id=? ORDER BY id DESC",
      id
    );
  }

  @PostMapping("/events/{id}/files")
  public void upload(
    @PathVariable long id,
    @RequestParam MultipartFile file,
    HttpSession s
  ) throws Exception {
    auth.need(s, "content");
    store.one("SELECT id FROM events WHERE id=?", id);
    String name = file.getOriginalFilename();
    Api.check(
      name != null &&
        name.length() <= 180 &&
        !name.contains("/") &&
        !name.contains("\\") &&
        !name.contains("\r") &&
        !name.contains("\n"),
      "文件名不合法"
    );
    Api.check(
      !file.isEmpty() && file.getSize() <= 20 * 1024 * 1024,
      "文件大小必须在1字节至20MB之间"
    );
    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    Api.check(
      Set.of("pdf", "ppt", "pptx", "txt", "docx").contains(ext),
      "仅支持PDF/PPT/PPTX/TXT/DOCX资料"
    );
    Files.createDirectories(root);
    String key = UUID.randomUUID() + "." + ext;
    Path path = root.resolve(key);
    file.transferTo(path);
    try {
      store.db.update(
        "INSERT INTO files(event_id,user_id,name,path,size,data) VALUES(?,?,?,?,?,?)",
        id,
        auth.id(s),
        name,
        key,
        file.getSize(),
        Files.readAllBytes(path)
      );
    } catch (RuntimeException e) {
      Files.deleteIfExists(path);
      throw e;
    }
    store.log(auth.id(s), "上传资料", name);
  }

  @GetMapping("/files/{id}/download")
  public ResponseEntity<?> download(@PathVariable long id, HttpSession s) {
    var file = store.one("SELECT * FROM files WHERE id=?", id);
    if (!"ADMIN".equals(auth.role(s))) auth.approved(
      s,
      Store.num(file, "event_id")
    );
    if (file.get("data") instanceof byte[] data) return ResponseEntity.ok()
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
          .filename((String) file.get("name"), StandardCharsets.UTF_8)
          .build()
          .toString()
      )
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .body(data);
    Path path = root.resolve((String) file.get("path")).normalize();
    Api.check(path.startsWith(root) && Files.isRegularFile(path), "文件不存在");
    return ResponseEntity.ok()
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
          .filename((String) file.get("name"), StandardCharsets.UTF_8)
          .build()
          .toString()
      )
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .body(new FileSystemResource(path));
  }
}

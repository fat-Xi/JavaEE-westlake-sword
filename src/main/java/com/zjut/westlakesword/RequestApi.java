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

/** 展商保存草稿、补齐附件并送审；只有管理员能审核及创建正式活动。 */
@RestController
@RequestMapping("/api/requests")
public class RequestApi {

  private final Store store;
  private final Auth auth;
  private final Api events;
  private final Path root;

  public RequestApi(
    Store store,
    Auth auth,
    Api events,
    @Value("${app.upload}") String upload
  ) {
    this.store = store;
    this.auth = auth;
    this.events = events;
    this.root = Path.of(upload).toAbsolutePath().normalize();
  }

  record Form(
    String title,
    String place,
    LocalDateTime starts,
    LocalDateTime ends,
    int capacity,
    String contact,
    String content,
    String news,
    String result,
    String exhibit,
    String image
  ) {}

  record Review(boolean pass, String reason) {}

  void valid(Form f) {
    Store.word(f.title(), 120, "会议名称");
    Store.word(f.place(), 120, "拟定地点");
    Store.word(f.contact(), 100, "联系人及联系方式");
    Store.word(f.content(), 20000, "办会说明");
    Api.check(
      f.starts() != null && f.ends() != null && f.ends().isAfter(f.starts()),
      "结束时间必须晚于开始时间"
    );
    Api.check(f.capacity() > 0, "预计人数需大于0");
    for (String text : List.of(
      text(f.news()),
      text(f.result()),
      text(f.exhibit())
    ))
      Api.check(text.length() <= 10000, "每类资料最多10000字");
    Store.image(f.image());
  }

  private String text(String value) {
    return value == null ? "" : value.trim();
  }

  private void materials(long id, Form f) {
    store.db.update(
      "UPDATE requests SET news=?,result=?,exhibit=?,image=? WHERE id=?",
      text(f.news()),
      text(f.result()),
      text(f.exhibit()),
      Store.image(f.image()),
      id
    );
  }

  Map<String, Object> readable(long id, HttpSession s, boolean lock) {
    var r = store.one(
      "SELECT * FROM requests WHERE id=?" + (lock ? " FOR UPDATE" : ""),
      id
    );
    if (!"ADMIN".equals(auth.role(s))) {
      auth.need(s, "request");
      if (Store.num(r, "user_id") != auth.id(s)) Auth.denied(
        "只能查看自己的办会申请"
      );
    }
    return r;
  }

  Map<String, Object> editable(long id, HttpSession s) {
    auth.need(s, "request");
    var r = readable(id, s, true);
    if (Store.num(r, "user_id") != auth.id(s)) Auth.denied(
      "只能编辑自己的办会申请"
    );
    Api.check(
      Set.of("DRAFT", "REJECTED").contains(r.get("status")),
      "仅草稿或驳回申请可修改，待审核及已通过申请不能变更资料"
    );
    return r;
  }

  @GetMapping
  public List<Map<String, Object>> list(HttpSession s) {
    if ("ADMIN".equals(auth.role(s))) return store.db.queryForList(
      "SELECT r.*,u.name FROM requests r JOIN users u ON u.id=r.user_id ORDER BY r.id DESC"
    );
    auth.need(s, "request");
    return store.db.queryForList(
      "SELECT r.*,u.name FROM requests r JOIN users u ON u.id=r.user_id WHERE r.user_id=? ORDER BY r.id DESC",
      auth.id(s)
    );
  }

  @PostMapping
  @Transactional
  public Map<String, Long> add(@RequestBody Form f, HttpSession s) {
    auth.need(s, "request");
    Api.check("EXHIBITOR".equals(auth.role(s)), "请使用展商账号提交办会申请");
    valid(f);
    store.db.update(
      "INSERT INTO requests(user_id,title,place,starts,ends,capacity,contact,content) VALUES(?,?,?,?,?,?,?,?)",
      auth.id(s),
      f.title(),
      f.place(),
      f.starts(),
      f.ends(),
      f.capacity(),
      f.contact(),
      f.content()
    );
    long id = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    materials(id, f);
    history(id, s, "创建办会草稿");
    return Map.of("id", id);
  }

  @PutMapping("/{id}")
  @Transactional
  public void edit(@PathVariable long id, @RequestBody Form f, HttpSession s) {
    editable(id, s);
    valid(f);
    materials(id, f);
    history(id, s, "修改办会申请");
    store.db.update(
      "UPDATE requests SET title=?,place=?,starts=?,ends=?,capacity=?,contact=?,content=?,status='DRAFT',reason='' WHERE id=?",
      f.title(),
      f.place(),
      f.starts(),
      f.ends(),
      f.capacity(),
      f.contact(),
      f.content(),
      id
    );
  }

  @PostMapping("/{id}/submit")
  @Transactional
  public void submit(@PathVariable long id, HttpSession s) {
    var r = editable(id, s);
    Api.check(
      ((LocalDateTime) r.get("starts")).isAfter(LocalDateTime.now()),
      "拟定开始时间已过，请调整后提交"
    );
    store.db.update(
      "UPDATE requests SET status='PENDING',reason='' WHERE id=?",
      id
    );
    history(id, s, "提交办会申请");
  }

  @PostMapping("/{id}/withdraw")
  @Transactional
  public void withdraw(@PathVariable long id, HttpSession s) {
    var r = readable(id, s, true);
    Api.check(Store.num(r, "user_id") == auth.id(s), "只能撤回本人的申请");
    Api.check("PENDING".equals(r.get("status")), "只有未审核的申请可以撤回");
    store.db.update(
      "UPDATE requests SET status='DRAFT',reason='' WHERE id=?",
      id
    );
    history(id, s, "撤回待审申请，恢复草稿");
  }

  void history(long id, HttpSession s, String action) {
    store.db.update(
      "INSERT INTO request_history(request_id,user_id,action) VALUES(?,?,?)",
      id,
      auth.id(s),
      action
    );
    store.log(auth.id(s), action, id);
  }

  @GetMapping("/{id}/history")
  public List<Map<String, Object>> history(
    @PathVariable long id,
    HttpSession s
  ) {
    readable(id, s, false);
    return store.db.queryForList(
      "SELECT h.*,u.name FROM request_history h JOIN users u ON u.id=h.user_id WHERE request_id=? ORDER BY h.id DESC",
      id
    );
  }

  @PostMapping("/{id}/review")
  @Transactional
  public void review(
    @PathVariable long id,
    @RequestBody Review f,
    HttpSession s
  ) {
    auth.admin(s);
    var r = readable(id, s, true);
    Api.check("PENDING".equals(r.get("status")), "申请不在待审核状态");
    if (!f.pass()) Store.word(f.reason(), 200, "驳回原因");
    store.db.update(
      "UPDATE requests SET status=?,reason=? WHERE id=?",
      f.pass() ? "APPROVED" : "REJECTED",
      f.pass() ? "" : f.reason(),
      id
    );
    store.note(
      Store.num(r, "user_id"),
      "办会申请「" +
        r.get("title") +
        "」" +
        (f.pass() ? "审核通过，等待管理员创建活动" : "已驳回：" + f.reason())
    );
    history(id, s, f.pass() ? "办会资料通过" : "驳回：" + f.reason());
  }

  @PostMapping("/{id}/create")
  @Transactional
  public Map<String, Long> create(
    @PathVariable long id,
    @RequestBody Api.Event f,
    HttpSession s
  ) {
    auth.admin(s);
    var r = readable(id, s, true);
    Api.check(
      "APPROVED".equals(r.get("status")) && r.get("event_id") == null,
      "仅已通过且尚未建会的申请可创建活动"
    );
    events.validate(f);
    Api.check(
      f.starts().isAfter(LocalDateTime.now()),
      "请确认新的会议开始时间"
    );
    store.db.update(
      "INSERT INTO events(title,place,starts,ends,capacity,content,status) VALUES(?,?,?,?,?,?,?)",
      f.title(),
      f.place(),
      f.starts(),
      f.ends(),
      f.capacity(),
      f.content(),
      f.status()
    );
    long eventId = store.db.queryForObject(
      "SELECT LAST_INSERT_ID()",
      Long.class
    );
    events.media(eventId, f);
    store.db.update(
      "UPDATE requests SET status='CREATED',event_id=? WHERE id=?",
      eventId,
      id
    );
    // 审核通过的附件成为正式活动资料，复用随机存储名，不暴露磁盘路径。
    store.db.update(
      "INSERT INTO files(event_id,user_id,name,path,size,data) SELECT ?,?,name,path,size,data FROM request_files WHERE request_id=?",
      eventId,
      auth.id(s),
      id
    );
    // 展商是资料作者；整份申请审核通过后，资料随建会一并发布。
    for (String kind : List.of("NEWS", "RESULT", "EXHIBIT")) {
      String body = text((String) r.get(kind.toLowerCase()));
      if (!body.isBlank()) store.db.update(
        "INSERT INTO posts(event_id,user_id,kind,title,body,image,status) VALUES(?,?,?,?,?,?,'APPROVED')",
        eventId,
        r.get("user_id"),
        kind,
        f.title().substring(0, Math.min(105, f.title().length())) +
          " · " +
          (kind.equals("NEWS")
            ? "会议资讯"
            : kind.equals("RESULT")
              ? "成果介绍"
              : "展商资料"),
        body,
        r.get("image")
      );
    }
    store.note(Store.num(r, "user_id"), "你的办会申请已创建活动：" + f.title());
    store.log(auth.id(s), "依据申请创建活动", id + ":" + eventId);
    return Map.of("eventId", eventId);
  }

  @GetMapping("/{id}/files")
  public List<Map<String, Object>> files(@PathVariable long id, HttpSession s) {
    readable(id, s, false);
    return store.db.queryForList(
      "SELECT id,name,size FROM request_files WHERE request_id=? ORDER BY id",
      id
    );
  }

  @PostMapping("/{id}/files")
  @Transactional(rollbackFor = Exception.class)
  public void upload(
    @PathVariable long id,
    @RequestParam MultipartFile file,
    HttpSession s
  ) throws Exception {
    editable(id, s);
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
      "文件须为1字节至20MB"
    );
    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    Api.check(
      Set.of("pdf", "ppt", "pptx", "docx", "txt").contains(ext),
      "只支持PDF/PPT/PPTX/DOCX/TXT"
    );
    Files.createDirectories(root);
    String key = UUID.randomUUID() + "." + ext;
    Path path = root.resolve(key);
    file.transferTo(path);
    try {
      store.db.update(
        "INSERT INTO request_files(request_id,name,path,size,data) VALUES(?,?,?,?,?)",
        id,
        name,
        key,
        file.getSize(),
        Files.readAllBytes(path)
      );
    } catch (RuntimeException e) {
      Files.deleteIfExists(path);
      throw e;
    }
  }

  @DeleteMapping("/{id}/files/{fileId}")
  @Transactional
  public void remove(
    @PathVariable long id,
    @PathVariable long fileId,
    HttpSession s
  ) {
    editable(id, s);
    store.one(
      "SELECT id FROM request_files WHERE id=? AND request_id=?",
      fileId,
      id
    );
    // 只移除附件引用，文件随备份保留，避免事务回滚造成记录指向不存在的文件。
    store.db.update("DELETE FROM request_files WHERE id=?", fileId);
  }

  @GetMapping("/{id}/files/{fileId}/download")
  public ResponseEntity<?> download(
    @PathVariable long id,
    @PathVariable long fileId,
    HttpSession s
  ) {
    readable(id, s, false);
    var f = store.one(
      "SELECT * FROM request_files WHERE id=? AND request_id=?",
      fileId,
      id
    );
    if (f.get("data") instanceof byte[] data) return ResponseEntity.ok()
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
          .filename((String) f.get("name"), StandardCharsets.UTF_8)
          .build()
          .toString()
      )
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .body(data);
    Path path = root.resolve((String) f.get("path")).normalize();
    Api.check(path.startsWith(root) && Files.isRegularFile(path), "文件不存在");
    return ResponseEntity.ok()
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
          .filename((String) f.get("name"), StandardCharsets.UTF_8)
          .build()
          .toString()
      )
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .body(new FileSystemResource(path));
  }
}

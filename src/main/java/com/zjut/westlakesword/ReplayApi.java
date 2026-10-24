package com.zjut.westlakesword;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 课程演示规模的本地 MP4/WebM 回放，二进制保存 MySQL，由登录会话保护读取。 */
@RestController
@RequestMapping("/api/talks")
public class ReplayApi {

  private final Store store;
  private final Auth auth;

  public ReplayApi(Store store, Auth auth) {
    this.store = store;
    this.auth = auth;
  }

  @PostMapping("/{id}/replay")
  @Transactional
  public void upload(
    @PathVariable long id,
    @RequestParam MultipartFile file,
    HttpSession s
  ) throws Exception {
    auth.admin(s);
    store.one("SELECT id FROM talks WHERE id=? FOR UPDATE", id);
    String name = file.getOriginalFilename();
    Api.check(
      name != null &&
        name.length() <= 180 &&
        (name.toLowerCase().endsWith(".mp4") ||
          name.toLowerCase().endsWith(".webm")),
      "请选择MP4或WebM文件"
    );
    Api.check(
      file.getSize() > 12 && file.getSize() <= 20 * 1024 * 1024,
      "回放须为20MB以内的视频文件"
    );
    byte[] data = file.getBytes();
    boolean mp4 = name.toLowerCase().endsWith(".mp4");
    Api.check(
      mp4
        ? data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p'
        : data[0] == 0x1a &&
            (data[1] & 255) == 0x45 &&
            (data[2] & 255) == 0xdf &&
            (data[3] & 255) == 0xa3,
      "无法识别视频格式"
    );
    store.db.update(
      "INSERT INTO replays(talk_id,name,type,data) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),type=VALUES(type),data=VALUES(data)",
      id,
      name,
      mp4 ? "video/mp4" : "video/webm",
      data
    );
    store.db.update(
      "UPDATE talks SET replay_url=?,had_live=TRUE WHERE id=?",
      "/api/talks/" + id + "/replay",
      id
    );
    store.log(auth.id(s), "上传议程回放", id);
  }

  @GetMapping("/{id}/replay")
  public ResponseEntity<ByteArrayResource> play(
    @PathVariable long id,
    HttpSession s
  ) {
    var talk = store.one(
      "SELECT t.*,e.status event_status FROM talks t JOIN events e ON e.id=t.event_id WHERE t.id=?",
      id
    );
    if (!"ADMIN".equals(auth.role(s))) {
      auth.approved(s, Store.num(talk, "event_id"));
      Api.check("OPEN".equals(talk.get("event_status")), "会议未开放");
      Api.check(
        !LocalDateTime.now().isBefore((LocalDateTime) talk.get("ends")),
        "议程结束后开放回放"
      );
    }
    var replay = store.one("SELECT data,type FROM replays WHERE talk_id=?", id);
    byte[] data = (byte[]) replay.get("data");
    return ResponseEntity.ok()
      .contentType(MediaType.valueOf((String) replay.get("type")))
      .cacheControl(CacheControl.noStore())
      .body(new ByteArrayResource(data));
  }
}

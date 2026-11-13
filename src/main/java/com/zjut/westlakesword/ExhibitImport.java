package com.zjut.westlakesword;

import java.util.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 一次性接入原有办会申请，原始资料不删除，后续统一在新审核入口处理。 */
@Component
@Order(60)
public class ExhibitImport implements CommandLineRunner {

  final ExhibitApi ex;

  public ExhibitImport(ExhibitApi ex) {
    this.ex = ex;
  }

  @org.springframework.transaction.annotation.Transactional
  public void run(String... args) {
    for (var r : ex
      .store()
      .db.queryForList(
        "SELECT * FROM requests WHERE status IN ('PENDING','APPROVED','CREATED','REJECTED')"
      )) {
      String marker = "exhibit-import-" + r.get("id");
      if (
        ex.store().count("SELECT COUNT(*) FROM app_meta WHERE name=?", marker) >
        0
      ) continue;
      var d = new HashMap<String, Object>();
      for (String k : List.of(
        "title",
        "place",
        "starts",
        "ends",
        "capacity",
        "content"
      ))
        d.put(k, r.get(k));
      d.put("starts", r.get("starts").toString());
      d.put("ends", r.get("ends").toString());
      d.put("cover", Objects.toString(r.get("image"), ""));
      d.put("poster", Objects.toString(r.get("image"), ""));
      d.put(
        "features",
        List.of(
          Map.of(
            "title",
            "聚焦前沿",
            "body",
            "数据安全、AI 治理与云端可信实践。"
          ),
          Map.of(
            "title",
            "连接同行",
            "body",
            "与开发者及行业专家交流真实案例。"
          ),
          Map.of("title", "落地实践", "body", "议程分享、展商成果和互动体验。")
        )
      );
      d.put(
        "talks",
        r.get("event_id") == null
          ? List.of()
          : ex
              .store()
              .db.queryForList(
                "SELECT * FROM talks WHERE event_id=? ORDER BY starts",
                r.get("event_id")
              )
      );
      var materials = new ArrayList<Map<String, Object>>();
      for (String k : List.of("exhibit", "news", "result")) {
        String body = Objects.toString(r.get(k), "");
        if (body.isBlank()) continue;
        String type = k.equals("news")
          ? "会议资讯"
          : k.equals("result")
            ? "成果展示"
            : "展商介绍";
        var m = new HashMap<String, Object>();
        m.put("type", type);
        m.put("title", r.get("title") + " · " + type);
        m.put("body", body);
        m.put("product", "");
        m.put("image", Objects.toString(r.get("image"), ""));
        m.put("files", new ArrayList<Map<String, Object>>());
        materials.add(m);
      }
      var files = ex
        .store()
        .db.queryForList(
          "SELECT * FROM request_files WHERE request_id=?",
          r.get("id")
        );
      if (!files.isEmpty() && materials.isEmpty()) {
        var m = new HashMap<String, Object>();
        m.put("type", "展商介绍");
        m.put("title", r.get("title"));
        m.put("body", r.get("content"));
        m.put("product", "");
        m.put("image", "");
        m.put("files", new ArrayList<Map<String, Object>>());
        materials.add(m);
      }
      for (var f : files)
        if (f.get("data") instanceof byte[] bytes) ex.rows(
          materials.get(0),
          "files"
        ).add(
          new HashMap<>(
            Map.of(
              "name",
              f.get("name"),
              "size",
              bytes.length + " B",
              "data",
              "data:application/octet-stream;base64," +
                Base64.getEncoder().encodeToString(bytes)
            )
          )
        );
      d.put("materials", materials);
      String status = "CREATED".equals(r.get("status"))
        ? "APPROVED"
        : "REJECTED".equals(r.get("status"))
          ? "REJECTED"
          : "PENDING";
      ex.store().db.update(
        "INSERT INTO exhibit_meetings(user_id,event_id,status,payload,reason) VALUES(?,?,?,?,?)",
        r.get("user_id"),
        r.get("event_id"),
        status,
        ex.mapper().writeValueAsString(d),
        r.get("reason")
      );
      ex.store().db.update("INSERT INTO app_meta(name) VALUES(?)", marker);
    }
  }
}

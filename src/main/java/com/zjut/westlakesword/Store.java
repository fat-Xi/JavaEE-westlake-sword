package com.zjut.westlakesword;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 简单数据库工具：用户输入始终通过 SQL 参数传递。 */
@Component
public class Store {

  public final JdbcTemplate db;

  public Store(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "记录不存在"
    );
    return rows.get(0);
  }

  public long count(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  public static long num(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  public static String word(String value, int max, String label) {
    Api.check(
      value != null && !value.isBlank() && value.length() <= max,
      label + "不能为空且最多" + max + "字"
    );
    return value.trim();
  }

  public static String url(String value) {
    if (value == null || value.isBlank()) return "";
    Api.check(value.length() <= 500, "链接太长");
    var uri = java.net.URI.create(value);
    Api.check(
      Set.of("http", "https").contains(
        uri.getScheme() == null ? "" : uri.getScheme()
      ) &&
        uri.getHost() != null,
      "链接仅支持http或https"
    );
    return value;
  }

  /** 只接受有限大小的 PNG/JPEG 位图内容；历史 URL 由调用方单独保留。 */
  public static String image(String value) {
    if (value == null || value.isBlank()) return "";
    Api.check(
      value.length() <= 8000000 &&
        (value.startsWith("data:image/png;base64,") ||
          value.startsWith("data:image/jpeg;base64,")),
      "请从本地选择 PNG/JPEG 图片"
    );
    try {
      byte[] bytes = Base64.getDecoder().decode(
        value.substring(value.indexOf(',') + 1)
      );
      var input = javax.imageio.ImageIO.createImageInputStream(
        new java.io.ByteArrayInputStream(bytes)
      );
      try (input) {
        var readers = javax.imageio.ImageIO.getImageReaders(input);
        Api.check(readers.hasNext(), "图片格式无法识别");
        var reader = readers.next();
        try {
          reader.setInput(input);
          Api.check(
            reader.getWidth(0) <= 1600 && reader.getHeight(0) <= 1600,
            "图片尺寸过大"
          );
          Api.check(reader.read(0) != null, "图片内容无效");
        } finally {
          reader.dispose();
        }
      }
    } catch (java.io.IOException | IllegalArgumentException e) {
      Api.check(false, "图片内容无效");
    }
    return value;
  }

  public void log(long user, String action, Object detail) {
    db.update(
      "INSERT INTO logs(user_id,action,detail) VALUES(?,?,?)",
      user,
      action,
      String.valueOf(detail)
    );
  }

  public void note(long user, String body) {
    // 保留有效通知，长评论/提问避免超过数据库字段长度导致审核回滚。
    if (body.codePointCount(0, body.length()) > 500) body =
      body.substring(0, body.offsetByCodePoints(0, 499)) + "…";
    db.update("INSERT INTO notes(user_id,body) VALUES(?,?)", user, body);
  }
}

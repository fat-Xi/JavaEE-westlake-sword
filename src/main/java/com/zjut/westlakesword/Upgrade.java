package com.zjut.westlakesword;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 增量升级保留原有数据；每个字段检查后再添加，支持重复启动。 */
@Component
@Order(-10)
public class Upgrade implements CommandLineRunner {

  private final Store store;

  public Upgrade(Store store) {
    this.store = store;
  }

  void column(String table, String name, String type) {
    if (
      store.count(
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
        table,
        name
      ) == 0
    ) {
      store.db.execute(
        "ALTER TABLE " + table + " ADD COLUMN " + name + " " + type
      );
    }
  }

  public void run(String... args) {
    column("polls", "heading", "VARCHAR(120) NOT NULL DEFAULT ''");
    column("events", "cover", "MEDIUMTEXT");
    column("events", "poster", "MEDIUMTEXT");
    column("events", "posters", "LONGTEXT");
    column("events", "cover_title", "VARCHAR(120) NOT NULL DEFAULT ''");
    column("events", "meeting_type", "VARCHAR(30) NOT NULL DEFAULT ''");
    column("events", "features", "TEXT");
    column("requests", "poll_title", "VARCHAR(120) NOT NULL DEFAULT ''");
    column("questions", "talk_id", "BIGINT NULL");
    column("questions", "featured", "BOOLEAN NOT NULL DEFAULT FALSE");
    column("questions", "closed", "BOOLEAN NOT NULL DEFAULT FALSE");
    if (
      store.count(
        "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='questions' AND constraint_name='fk_questions_talk'"
      ) == 0
    ) {
      // 早期版本允许移除已有提问的议程，保留这些提问为全会提问。
      store.db.update(
        "UPDATE questions q LEFT JOIN talks t ON t.id=q.talk_id SET q.talk_id=NULL WHERE q.talk_id IS NOT NULL AND t.id IS NULL"
      );
      store.db.execute(
        "ALTER TABLE questions ADD CONSTRAINT fk_questions_talk FOREIGN KEY(talk_id) REFERENCES talks(id)"
      );
    }
    column("posts", "anonymous", "BOOLEAN NOT NULL DEFAULT FALSE");
    column("posts", "font", "VARCHAR(20) NOT NULL DEFAULT 'sans'");
    column("posts", "reply_to_id", "BIGINT NULL");
    if (
      store.count(
        "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='posts' AND constraint_name='fk_posts_reply'"
      ) == 0
    ) store.db.execute(
      "ALTER TABLE posts ADD CONSTRAINT fk_posts_reply FOREIGN KEY(reply_to_id) REFERENCES posts(id) ON DELETE SET NULL"
    );
    column("users", "status", "VARCHAR(20) NOT NULL DEFAULT 'APPROVED'");
    column("users", "reason", "VARCHAR(200) NOT NULL DEFAULT ''");
    column("profiles", "real_name", "VARCHAR(40) NOT NULL DEFAULT ''");
    column("profiles", "gender", "VARCHAR(10) NOT NULL DEFAULT '未设置'");
    column("profiles", "email", "VARCHAR(120) NOT NULL DEFAULT ''");
    column("profiles", "avatar", "MEDIUMTEXT");
    column("talks", "live_url", "VARCHAR(500) NOT NULL DEFAULT ''");
    column("talks", "replay_url", "VARCHAR(500) NOT NULL DEFAULT ''");
    column("talks", "live_on", "BOOLEAN NOT NULL DEFAULT FALSE");
    column("talks", "had_live", "BOOLEAN NOT NULL DEFAULT FALSE");
    column("files", "data", "LONGBLOB");
    column("request_files", "data", "LONGBLOB");
    column("requests", "news", "TEXT");
    column("requests", "result", "TEXT");
    column("requests", "exhibit", "TEXT");
    column("requests", "image", "MEDIUMTEXT");
    if (
      store.count(
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='posts' AND column_name='image' AND data_type='mediumtext'"
      ) == 0
    ) store.db.execute("ALTER TABLE posts MODIFY image MEDIUMTEXT");
  }
}

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

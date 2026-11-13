-- 手动导入演示申请；重复执行不重复插入，不重置已处理状态。
START TRANSACTION;
SET @uid=(SELECT id FROM users WHERE name='demo_user');
SET @eid=(SELECT id FROM events WHERE title='西湖论剑 · 数字安全未来峰会');
SET @mid=(SELECT id FROM exhibit_meetings WHERE event_id=@eid LIMIT 1);
INSERT INTO questions(event_id,user_id,body,status) SELECT @eid,@uid,'【审批演示】论坛结束后在哪里查看资料与回放？','PENDING' WHERE NOT EXISTS(SELECT 1 FROM questions WHERE event_id=@eid AND body='【审批演示】论坛结束后在哪里查看资料与回放？');
INSERT INTO posts(event_id,user_id,kind,title,body,status) SELECT @eid,@uid,'COMMENT','审批演示评论','【审批演示】期待与大家交流数据安全实践，欢迎分享案例。','PENDING' WHERE NOT EXISTS(SELECT 1 FROM posts WHERE event_id=@eid AND title='审批演示评论');
INSERT INTO posts(event_id,user_id,kind,title,body,status) SELECT @eid,@uid,'WALL','审批演示签名','【审批演示】相聚西湖，让安全连接每一种可能。','PENDING' WHERE NOT EXISTS(SELECT 1 FROM posts WHERE event_id=@eid AND title='审批演示签名');
INSERT INTO exhibit_reviews(meeting_id,kind,payload,status) SELECT @mid,'POLL',JSON_OBJECT('title','【审批演示】你最关注的安全方向','question','下一场交流你想了解哪个主题？','options',JSON_ARRAY('数据安全','云原生安全','AI安全'),'starts',DATE_FORMAT(NOW(),'%Y-%m-%dT%H:%i:%s'),'ends',DATE_FORMAT(DATE_ADD(NOW(),INTERVAL 30 DAY),'%Y-%m-%dT%H:%i:%s')),'PENDING' WHERE @mid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM exhibit_reviews WHERE meeting_id=@mid AND kind='POLL' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.title'))='【审批演示】你最关注的安全方向');
INSERT INTO joins(event_id,user_id,status) SELECT @eid,u.id,'PENDING' FROM users u WHERE u.name='demo_pending' AND NOT EXISTS(SELECT 1 FROM joins j WHERE j.event_id=@eid AND j.user_id=u.id);
COMMIT;

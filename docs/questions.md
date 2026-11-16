# 议题提问箱

参会者在会议详情的「会议提问」页提交全会或指定议程的问题。审核前和驳回后仅作者本人可见。审核通过后，报名通过者可以查看、赞同或点踩。对应会议的主办方提交答复，管理员审核后公开，提问者收到站内通知。

## 数据升级

`questions` 使用 `talk_id BIGINT NULL`、`featured BOOLEAN NOT NULL DEFAULT FALSE`、`closed BOOLEAN NOT NULL DEFAULT FALSE`，以及 `talk_id` 到 `talks.id` 的外键 `fk_questions_talk`。原有提问保留，关联议程已删除的提问转为全会提问。`schema.sql` 用于新库，`Upgrade` 检查字段及约束后增量添加，支持重复启动。

问题赞同复用 `votes` 的用户/问题唯一键；独立单选投票继续使用 `polls/poll_options/ballots`，运营看板的投票票数仍只统计 ballots。

## 权限与状态

- 提问、查看问题、赞同和撤销均校验参会者身份、所属会议报名及会议发布状态。
- 关联议程必须属于同一会议，已有关联提问的议程不能删除。
- 会议结束后停止新提问、新增赞同和点踩，已公开内容可继续查看。
- 管理员可审核、下架、精选、关闭和重开问题。主办方答复走展商互动审核流程。下架时清除精选标记。
- 关闭保留问题、票数及答复，停止新增赞同或点踩，已有投票仍可撤销。
- 重复赞同/撤销为幂等操作，事务与问题行锁保证赞同、审核和关闭并发时不会重复计数或绕过关闭状态。
- 状态更新只修改请求中指定的字段，避免同时精选与关闭时覆盖另一项变更。

## 接口

| 接口                                    | 用途                                                                      |
| --------------------------------------- | ------------------------------------------------------------------------- |
| `GET /api/events/{id}/questions`        | 公开问题和本人未公开记录；包含议程、作者、精选/关闭、赞同数及本人是否赞同 |
| `POST /api/questions`                   | `eventId`、可选 `talkId`、`body`，返回新问题 ID；省略议程兼容旧客户端     |
| `POST /api/questions/{id}/support`      | 新增赞同，重复请求不重复计票                                              |
| `DELETE /api/questions/{id}/support`    | 撤销本人赞同                                                              |
| `POST /api/questions/{id}/dislike`      | 点踩并取消本人的赞同                                                      |
| `DELETE /api/questions/{id}/dislike`    | 撤销本人点踩                                                              |
| `GET /api/review/questions`             | 管理员问题列表，按精选及赞同数排序                                        |
| `POST /api/review/questions/{id}`       | `pass/reason` 审核或下架                                                  |
| `POST /api/exhibitor/meetings/{id}/interactions/ANSWER` | 对应会议的主办方提交答复审核 |
| `PUT /api/review/questions/{id}/state`  | 可选 `featured` 和/或 `closed`                                            |

旧 `POST /questions/{id}/vote` 继续拒绝，避免与单选投票混淆；旧撤票路径保留为经过权限校验的撤销别名。

## 页面与测试

参会者可按议程、待回答/已回答/精选/关闭筛选，选择热度或最新排序，查看本人未公开记录与驳回原因。提交成功清空输入，失败保留输入，支持刷新及加载失败重试。

管理员在「问答审批」选择会议，可按议程、审核及处理状态筛选提问，也可审核主办方提交的答复。

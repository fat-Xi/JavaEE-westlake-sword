# 本机运行说明

项目目录：`E:\实验\Java课设\JavaEE-westlake-sword`。

在此目录打开 PowerShell，执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\local.ps1 start
```

浏览器访问 <http://127.0.0.1:5173>。后端地址为 `http://127.0.0.1:8080`。
启动脚本会在后台运行数据库、后端和 Vite；终端关闭后仍可访问。

| 账号 | 角色 |
| --- | --- |
| demo_user | 参会者，已通过测试会议报名 |
| demo_admin | 管理员 |
| demo_exhibit | 展商 |
| demo_staff | 工作人员 |
| demo_pending | 会议报名待审核 |
| demo_wait | 账号身份待审核 |

以上初始化演示账号的密码均为 `Demo12345!`。

## 日常开发

前端修改保存后由 Vite 自动更新页面。

修改 Java 代码后，重新编译并启动后端：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\local.ps1 restart-backend
```

查看进程状态：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\local.ps1 status
```

停止本项目的前端、后端和数据库，保留数据库文件：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\local.ps1 stop
```

## 本机环境

AI 模型按 `.env.local` 配置。按仓库所有者要求，`.env.example` 已包含共享模型的真实配置；队友可复制为 `.env.local`，并修改本机软件路径和数据库账号密码。已有本地配置时，只合并四个 `AI_` 配置项。密钥由后端读取，前端不读取或保存；修改 `AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY` 或 `AI_TIMEOUT_SECONDS` 后，执行 `local.ps1 restart-backend`。模型无法调用时，AI 页面会提供资料检索降级结果。详细说明见 [AI 问答](docs/ai.md)。

- Java：`E:\Program Files\Java\jdk-25`。
- Node.js：`E:\Program Files\nodejs`。
- MySQL：使用本机已安装的 MySQL 8.0.29 可执行文件，独立实例只监听 `127.0.0.1:3307`。
- 开发库：`westlake_sword`；自动化测试库：`westlake_v6_test`。
- 数据库文件、Maven 下载缓存及日志：`E:\实验\Java课设\tmp\westlake-runtime`。
- 数据库连接账号和本机路径放在 `.env.local`，该文件已被 Git 忽略。项目原有 `application.properties` 保留不变。
- 启动辅助文件与共享模型配置已提交；本机 `.env.local`、数据库、日志和上传文件仍保留在本机。

日志位于运行目录的 `logs` 文件夹，分别为 `backend.log`、`backend.error.log`、`frontend.log`、`frontend.error.log`、`mysql.log` 和 `mysql.error.log`。

此脚本使用已经初始化的本机环境。其他电脑需要自己的 `.env.local`、JDK、Node.js、MySQL、前端依赖及 Maven，并初始化独立数据库和账号。

## 已完成的本机验证

2026-11-05 完成前端 `npm ci` 和生产构建、后端打包及 Spring Boot 上下文测试 1 项。
在独立测试库运行 `web/tests/v5.mjs`，通过 75 次接口检查、5 类账号的浏览器页面检查和 390px 移动端布局检查。
开发环境通过前端代理登录 `demo_user`，并成功读取用户角色与活动列表。

以上为启动验证的范围；AI 模块的后续验证见 [AI 问答说明](docs/ai.md)。摄像头实机扫码和外部直播源仍需要分别验收。

# 项目进度

> 这份文件记录"做到了哪、哪些已验证、哪些还欠"。更新时**只写能验证的事实**，不写期望。
> 最后更新：2026-11-08（master `ee90d6f` 之后，桌面端已更新到 2.4.6）

## 一、当前版本（master）

| 端 | 版本 | 备注 |
| --- | --- | --- |
| `server/` | 2.4.7 | 含 v2_44 / v2_45 / v2_46 三个迁移 |
| `connector/` + `web-next/` | 2.4.5 | |
| `desktop-workbench/` + `android/` | 2.4.6 | android `versionCode 33` |
| `dsh-bridge-next/` | 见其 package.json | 独立版本线，不与主版本比大小 |

## 二、已交付并合入 master 的能力

- **统一 Plan（3C）**：server 计划 API + 持久化、`connector/connector/planning/` 应用宿主（domain/application/infrastructure）、三 runtime provider 绑定、能力覆写、四端计划卡片与 `/plan` 命令、计划审阅交互。
- **计划事件链**（connector → server ingest → server 事件 → 四端）：已补齐并**两侧都有测试**。
- **Pi/ACP runtime（3B）**：protocol 1.1 + runtime-workflows 契约、server durable workflows、connector WORKFLOW_RPC 派发、四端 UI。
- **流式附件（3D-1/2/3）**：server 流式落盘、connector 失败通知、客户端拒绝原因分离。
- **Android 设备页 Agent 配置（3D-4）**：3D-4a/4b/4c 三层 + 6 项 wiring 缺口已补齐。
- **CI 与质量**：`PORTING_CHECKLIST.md`（11 节，接入 AGENTS.md）、三类永久护栏测试（能力投影一致 / host 协议 wrapper / 功能接线在位）。

## 三、验证状态

| 项 | 状态 |
| --- | --- |
| Server 全量 | ✅ 1047 passed / 0 failed（含 planning 传输链 6 个测试） |
| Connector 全量 | ✅ 1185 passed，失败集与 Windows-only 基线逐条一致 |
| Web / Desktop | ✅ 467 / 459+135，typecheck 0 |
| Android | ✅ 270 / 270 |
| CI（master push） | ✅ 7/7 job |
| iOS 运行期 | ⏳ 未验证（无本机 Xcode，见 `MAC_VERIFICATION.md`） |
| 设备级联调（计划状态从 runtime 流到四端） | ⏳ 未验证（需设备 + 已认证 runtime） |
| `DSH plugin` 集成测试在 CI | ⏳ 需下载 ~175MB codex-cli，runner 偶发失败；master push 已改为只跑编译/构建 |

## 四、云端发布状态（重要，容易被忘）

线上服务器 `https://smartcare.aifacex.top`（公网 `124.221.30.114`，root + `~/.ssh/xuetu_deploy` 可登）：

| 项 | 状态 |
| --- | --- |
| `/desktop-installer/latest.exe` | ✅ **已更新为 2.4.6**（142,322,944 字节，与本机构建 SHA256 一致；旧包备份为 `latest.exe.bak-2.4.0-20261108-134414`） |
| `/apk/latest.apk` | ❌ 仍是 2.4.0（33,058,816 字节，文件名仍为 `agents-anywhere-2.4.0.apk`）；**缺 release 签名 keystore**，本机与服务器均无 |
| server 自身 | ❌ 仍是 2.4.0（`/api/v2/health` 如实报）。构建该镜像的 Dockerfile 不在此服务器上 |
| nginx | 宿主 nginx，`/apk/`→`/opt/aa/apk/`、`/desktop-installer/`→`/opt/aa/desktop/`（静态 alias） |
| 容器 | `agents-anywhere-server-next-1`（镜像 `agents-anywhere-server:postgres`，`:5174`）、`agents-anywhere-redis-next-1`、`agents-anywhere-postgres-next-1`；`/data` 为 docker volume |

**2026-11-10：宿主 nginx 为手机端 DSH 控制台做了两处改动**（`/etc/nginx/conf.d/agents-anywhere.conf`，改前备份 `.bak-dshui4` = gzip 前、`.bak-dshui5` = 代理前）：

1. 开 gzip（`text/css`、`text/javascript`、`application/json`、`image/svg+xml`、`application/wasm` 等）：DSH 插件包 10.5MB → 3.0MB（-71%）。
2. 新增 `location ~ ^/dsh-` 通配代理（含 `Upgrade`/`Connection` 映射以支持 WebSocket）。原因：DSH 插件用**绝对路径**引用自己的静态资源（`/dsh-claude-style/assets/*.js`、`/dsh-whale/widget.js`、`*.woff2`），此前只有 `/dsh/` 和 `/plugins/` 被代理，这些请求落到站点首页返回 **200 HTML**，浏览器 parse 即 `SyntaxError: Unexpected token '<'`（插件报 chunk "ran without handing back a factory"）。

> 同文件里的免登录入口 `location = /__dsh-setup/<token>` 含令牌，**不要**把这份配置原样提交进仓库。

**桌面端"有无更新"的判定依据是 server `/health` 的版本号**（`desktop-workbench/electron/desktop-updates.ts`），且与打包时记录的 `serverVersion` 比较；所以 server 不升级，桌面端永远收不到正确的更新提示。


### 待办（发布链）

1. server 升到 2.4.7（需 Dockerfile/构建流程 + 跑 v2_44/45/46 迁移 + 重启窗口）。
2. 新版 APK 上线（需 release keystore）。
3. 终端用户的桌面端要能自动更新，需 1 与 2 至少完成 1。

## 五、环境备忘（本机）

- **`ELECTRON_RUN_AS_NODE=1` 会让所有 Electron 应用秒退**（干净退出码 0、无日志）。跑 electron 前先 `env -u ELECTRON_RUN_AS_NODE`。
- 本机安装的应用在 `J:\ap\AgentPilot\`（构建产物覆盖安装，无 git）。
- local-up 起的 server 默认监听 8000；当前未启动。
- connector 的运行时 venv 在 `J:\xiangm_transfer\dshmobil\connector\.venv`（应用当前用的是这份；随包那份无预建 venv）。

## 六、维护这份文件

- 每批合并后更新"当前版本"与"已交付能力"两节。
- 发布到线上后更新第四节，并**实测 URL 再写结论**（size / Content-Length / Last-Modified / version.json）。
- 验证状态变了（某个 ⏳ 变 ✅）要及时改，不要在文档里留下"已验证"的谎。
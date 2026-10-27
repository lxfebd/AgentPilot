# AgentPilot — Agent 工作指南

本文件写给在本仓库中工作的编码 Agent（Codex、Claude Code 等）。本文件只保留 Agent 做出正确修改所需的内容。

## 项目是什么

AgentPilot 是一个跨设备的 Agent 工作台。工作设备上的 **Connector** 连接并运行 Codex、Claude Code、DSH 等 Runtime；**Server** 负责账号、会话、Timeline 和转发；**Desktop / Web / Android / iOS** 客户端通过 Server 查看会话、回复审批、管理文件与终端。当前产品版本为 2.x，API namespace 为 `/api/v2`。

## 目录地图

| 目录 | 内容 | 技术栈 |
| --- | --- | --- |
| `server/` | API、实时通道、存储；分层规则见 [server/README.md](server/README.md) | Python 3.12、FastAPI、PostgreSQL、Redis、Alembic、uv |
| `connector/` | 工作设备侧进程，连接 Server 并驱动 Runtime | Python、uv |
| `web-next/` | Web 客户端（另有自己的 [AGENTS.md](web-next/AGENTS.md)） | Next.js、TypeScript、Yarn |
| `desktop-workbench/` | **当前**桌面客户端，内含受管理的本机 Connector | Electron、TypeScript、Yarn |
| `android/` | Android 客户端，见 `android/ARCHITECTURE.md` | Kotlin、Gradle、JDK 17 |
| `ios/` | iOS / iPadOS 客户端，见 `ios/ARCHITECTURE.md` | Swift、Xcode |
| `dsh-bridge-next/` | **当前** DSH 插件（npm 包，也随 DSH Desktop 分发） | TypeScript、Yarn |
| `contracts/` | 跨端契约与 fixtures，按 `<name>/<version>/` 组织 | JSON Schema / 文档 |
| `docker/` | 自托管部署与本地数据库 compose | Docker Compose |

不在当前主线上开发的目录：

- `desktop-next/`、`dsh-bridge/`：已分别被 `desktop-workbench/`、`dsh-bridge-next/` 取代，除非任务明确要求，否则不要修改。
- `_deprecated/`、`_reference/`，以及各子项目内同名目录：历史代码或参考实现，不参与构建，不要从中导入。

## 开发与验证

- 仓库根目录**没有**统一的构建命令，每个子项目单独安装依赖、单独检查。
- **不提交 lockfile**（`yarn.lock`、`uv.lock`、`package-lock.json` 等已被 `.gitignore` 忽略），不要把它们加回来。
- 只运行和修改有关的 headless 检查：
  - Server：`cd server && uv run ruff check . --exclude .venv && uv run pytest -q`
  - Connector：`cd connector && uv run ruff check connector tests && uv run pytest -q`
  - Web：`cd web-next && yarn test && yarn typecheck && yarn protocol:check`
  - Desktop：`cd desktop-workbench && yarn test:main && yarn renderer:typecheck`
- 本地联调用 `./local-up.sh`（Server + Web，可加 `--with-connector`）或 `./desktop-local-up.sh`。**不要**使用 `--reset-data`，除非用户明确要求，因为它会删除本地数据库。
- Server 测试不能连接生产数据库，也不要把生产 URL 带进测试进程。
- Headless 检查通过，不等于真实安装、OAuth 回调或远程 Runtime 会话已经验证。汇报时要说明哪些验证没有做。
- CI（`.github/workflows/dsh-bridge-next.yml`）按改动路径触发，覆盖 DSH 插件、Connector、Server、Web、Desktop 与 Android 单测；iOS 与真实安装 / OAuth 回调 / 远程 Runtime 会话仍要靠本地检查。

### 上下文与长会话纪律

- 工具与构建命令只回结论、异常行和文件路径；完整日志先写工作区文件，不要全文贴进对话。
- 大文件先 grep 定位，再用 offset/limit 精读；不要整文件 read。
- 一个假设验证完（无论成败）立刻 compress 对应区间，不要等系统自动折叠；拆分会话的判据是该任务需要超过约 30 轮工具交互。
- 重复调用同一命令前先确认上一次的结果。

## 跨端修改的规则

- **契约优先**：修改 API 字段、实时事件或 Runtime 协议时，先更新 `contracts/`，再同步修改 Server、Connector 和所有受影响的客户端。Web / Desktop 的 `protocol:check` 用来发现契约偏差。
- **能力驱动**：各 Runtime 支持的功能不同，客户端应当根据有效能力（见 [contracts/dsh-bridge](contracts/dsh-bridge/README.md)）显示或隐藏功能，不要按 provider 名称写死判断。
- **Server 分层**：`core` 不能导入外层；`services` 不能导入 FastAPI、API 模块或具体的 `Store`。这些规则由 `server/tests/test_architecture_boundaries.py` 强制检查。
- **数据库**：schema 变化需要新增 Alembic 迁移 `server/migrations/versions/v2_N.py`，编号接着现有最大值递增，不要修改已发布的迁移。
- 多端功能（例如排队消息、计划审阅）通常要同时修改 Web、Desktop、Android、iOS。如果只修改了其中一部分，要在 PR 中写明哪些端还没有跟进。

## 版本号

`2.0.x` 是各端独立的小版本更新，`2.x.0` 是接口更新，`x.0.0` 是架构变更。只有 PATCH 允许各端不同；修改跨端接口时不能只升 PATCH，并且升 MINOR 或 MAJOR 时要更新**所有端**的版本号。

## 提交与 PR

- 从最新的 `main` 创建分支；Agent 分支通常使用 `codex/<topic>` 这样的前缀。
- 提交信息使用 Conventional Commits，并带上作用域，例如 `fix(dsh): ...`、`feat(android): ...`、`docs(ios): ...`。正文可以用中文或英文。
- 一个 PR 只做一件事。不要顺手提交与任务无关的本地改动、部署检查点或调试产物（`.local-dev/`、截图、日志）。
- 不要提交凭据、签名证书、`.env` 文件或生产地址中的密钥。签名、公证和发布流程见各子项目的 README。

## 文档

- 面向用户的文档以中文为主；README 有中英两个版本（`README.md` / `README.en.md` / `README.zh-CN.md`），修改其中一个时要同步其他版本。
- 功能或接口变化后，更新对应的 README，不要让文档描述已经不存在的行为。

# Mac 侧操作手册（命令级）

两台机器上剩下的三项验证都需要 Mac 或设备。本文给出可照抄的命令与每步的通过判据。所有命令在 macOS + Xcode 16+ 下执行。

---

## A. iOS：建测试 target 并跑 PlanTimeline 的纯函数测试

文件已交付：`C:/tmp/aa-prs/PlanTimelineTests.swift`（覆盖 `latestRevisions`，2 个用例）。

### A.1 用已有 scheme（仓库里已提交 `AgentPilot.xcscheme`，其 `BuildableName = "AgentPilot.app"`）

只需在 Xcode 里给工程加一个 **Unit Testing Bundle** target（命名 `AgentPilotTests`），或直接跑下面的命令验证现有 target 是否已被 scheme 包含：

```bash
xcodebuild test \
  -project ios/AgentPilot/AgentPilot.xcodeproj \
  -scheme AgentPilot \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  -only-testing:AgentPilotTests/PlanTimelineTests
```

> 如果报 "test target not found"，说明工程里还没有测试 target：Xcode → File → New → Target… → Unit Testing Bundle (iOS) → 命名 `AgentPilotTests` → Finish（让它加入 AgentPilot scheme）。

### A.2 放入测试文件

把 `PlanTimelineTests.swift` 拖入 `AgentPilotTests` target（勾选 target 成员）。模块名已确认为 **`AgentPilot`**（scheme 的 `BuildableName = "AgentPilot.app"`），所以 `@testable import AgentPilot` 可原样使用。

### A.3 通过判据

- 编译通过；`xcodebuild test` 输出 `Executed 2 tests, with 0 failures`。
- 若 `V2PlanDocument` 的 memberwise init 与文件里的字段不一致（我在无编译器前提下按源文件写的），按报错字段名修正——只有 5 个字段，改动局部。

### A.4 扩到 placement / isEcho（可选，文件末尾有两条路径）

优先用 **JSON 解码**造 `V2TimelineItem`（只需服务端真实 wire 字段），而不是手写 17 字段字面量。样例：

```swift
let json = """
{"id":"u1","sessionId":"s1","type":"message","status":"done","role":"user",
 "content":{"kind":"text","text":"..."},"orderSeq":1,"revision":1,
 "contentHash":"h","updatedSeq":1,"createdAt":"2026-01-01T10:00:00Z",
 "updatedAt":"2026-01-01T10:00:00Z"}
"""
let item = try JSONDecoder().decode(V2TimelineItem.self, from: Data(json.utf8))
let row = ChatTimelineRowModel(item)
let group = ChatTimelineGroup(kind: .single, rows: [row])
```

字段名以 `Domain/Session/V2TimelineItem.swift` 的 `CodingKeys` 为准。

---

## B. iOS：四端 UI 的联调验证（#72 评论里那份清单的命令版）

```bash
# 1) 编译（不发版）
xcodebuild build-for-testing \
  -project ios/AgentPilot/AgentPilot.xcodeproj \
  -scheme AgentPilot -destination 'platform=iOS Simulator,name=iPhone 16 Pro'

# 2) 起本地 server + connector（另一台或同一台）
./local-up.sh --with-connector
```

**逐场景通过判据**（App 在模拟器里跑，连本地 server）：

| 场景 | 操作 | 通过判据 |
|---|---|---|
| Pi 会话创建 | 新会话 → 选 Pi runtime → 提交 | 出现工作流进度（`RuntimeWorkflowView`），最终进入会话 |
| Plan 发起 | 会话里发 `/plan 迁移数据库` | 面板进入 plan 模式，出现流式草稿 |
| Plan 审阅 | 等一个 review（或用一个准备好的 fixture） | 出现 `SessionPlanReviewPanel`；点"同意并执行"后状态变为"已执行"类 |
| 计划时间线 | 滚动会话 | 计划版本落在对应回合处；系统回显不重复 |
| 中英切换 | 设置里切语言 | 上述界面新文案两种语言都正常（catalog 已含 1135 条） |

---

## C. #75 的设备级联调（计划状态从 runtime 流到四端）

```bash
./local-up.sh --with-connector
# 然后在客户端（模拟器/桌面/Web）登录同一账号，打开一个 DSH runtime 的会话
```

**通过判据**：
- connector 日志出现 planning 宿主装配（`bind_planning` 命中，或 capabilities 上报里出现 `session.plan_document`/`session.plan_review`）；
- 客户端时间线出现计划卡片，且状态随 DSH 侧变化刷新（`planning.state.updated`）。

**若三能力不上报**：说明桥接的 `getPlanning` case 仍缺（见 #75 评论的 A/B 决策）——此时计划的**呈现**（notice 驱动）应该仍然工作，只有 plan-mode 读/设不可用。

---

## D. 已知不会一次过的两项（省得你误判为回归）

1. **`DSH plugin` CI 检查**：要下载 `openai-codex-cli-bin`（约 175MB），runner 超时即失败；本地需先 `npm i -g` 对应版本才能复现。
2. **`Connector tests` 偶尔 `Errno 98`**：`test_full_copy_once_isolation_and_flush` 的端口占用 flaky，重跑即过（已实测）。

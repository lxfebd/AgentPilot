# AgentPilotTests

首个 iOS 单元测试文件所在目录。**当前没有任何 Xcode target 引用它**——本仓库此前没有 iOS 测试 target，这两个文件是日后搭建的落点，不影响现有构建。

- `PlanTimelineTests.swift`：`PlanTimeline` 的纯逻辑覆盖（`latestRevisions` 的全部用例）。使用 `import Testing`（Xcode 16+ 的 Swift Testing），需要 `@testable import AgentPilot`。
- 搭建与其余用例的完成路径见仓库根目录的 [MAC_VERIFICATION.md](../../../MAC_VERIFICATION.md)。

注意：该 Swift 文件是在没有本机 Swift 工具链的环境下按源文件 API 写成的，首次编译若字段名不符，按报错修正（`V2PlanDocument` 只有 5 个字段，改动局部）。

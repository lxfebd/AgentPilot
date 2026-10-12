# R0 结论：依赖升级的实测边界

> 完成日期：2026-10-10
> 结论全部来自本机编译实测，不是文档推断。

## 最终依赖配置

| 项 | 原值 | 新值 | 状态 |
| --- | --- | --- | --- |
| Gradle wrapper | 8.10.2 | **8.11.1** | 必需（AGP 8.9.2 要求 ≥8.11.1） |
| Android Gradle Plugin | 8.7.3 | **8.9.2** | 必需（compose BOM 2025.09 要求） |
| compose BOM | 2024.12.01 | **2025.09.00** | 已升级 |
| `androidx.compose.material3` | BOM 派生 1.3.1 | **1.4.0**（显式声明） | 已升级 |
| `androidx.compose.ui:ui-graphics` | BOM 派生 | 显式声明 | 已升级 |

## 被排除的依赖（实测不可用）

| 依赖 | 排除原因 |
| --- | --- |
| `material3:1.5.0-alpha29`（RikkaHub 用的版本） | 构建报错：`requires compileSdk 37` + `requires AGP 9.1.0 or higher`。本机 AGP 8.9.2 / compileSdk 36，升到 AGP 9 属于独立的大版本迁移，风险不与本次 UI 重做混在一起。 |
| `material3-adaptive:1.3.0` | 同上，`adaptive-layout-android:1.3.0` 强制 `compileSdk 37`。 |
| `material3-adaptive:1.1.0` | 能编译，但**没有可用的分栏 API**（见下）。 |

## 关键实测发现：Expressive API 在当前版本不可用

用探针文件 `ui/probe/ExpressiveProbe.kt` 逐一编译验证，结果：

| API | 状态 | 实测信息 |
| --- | --- | --- |
| `MaterialExpressiveTheme` | ❌ internal | 类存在于 AAR（`MaterialThemeKt$MaterialExpressiveTheme$1.class`），但函数标记 `internal` |
| `ExperimentalMaterial3ExpressiveApi` | ❌ internal | 注解类同样 internal，无法 `@OptIn` |
| `MotionScheme` / `StandardMotionScheme` | ❌ internal | 同上 |
| `Typography.titleMediumEmphasized` | ❌ internal | `Cannot access 'val titleMediumEmphasized: TextStyle': it is internal in 'androidx.compose.material3.Typography'` |
| `Typography.labelLargeEmphasized` | ❌ internal | 同上 |
| `ButtonGroup` / `ToggleButton` | ❌ 不存在 | 在独立包 `material3-expressive`，核心 material3 里没有 |
| `ThreePaneScaffold`（adaptive 1.1.0） | ❌ internal | 函数体需要 `PaneScaffoldDirective` 参数，该类型在 1.1.0 不存在 |
| `ListDetailPaneScaffold`（adaptive 1.1.0） | ❌ internal | 同上 |

**可用（已编译通过）**：

| API | 用途 |
| --- | --- |
| `SingleChoiceSegmentedButtonRow` + `SegmentedButtonDefaults.itemShape(index, count)` | 官方连续分段按钮，与 RikkaHub 的分段视觉一致 |
| `Typography.titleLarge / titleMedium / bodyMedium` | 标准排版 |
| `androidx.compose.material3.window.size`（已有） | 窗口尺寸分类 |

## 对方案的影响

1. **大屏分栏必须手写**，不能用官方 adaptive 包。用现有的 `AAWindowSize`（`ui/designsystem/AAWindowSize.kt`，`>=600 Medium` / `>=840 Expanded`）+ `Row` + 折叠状态实现。
2. **Expressive 视觉语言拿不到**，需在 M3 1.4.0 基础上手工模拟 RikkaHub 观感：大圆角、连续分段、emphasized 文字（用 `FontWeight.SemiBold` + 稍紧字距模拟 `titleSmallEmphasized`）。
3. 若后续要真正的 Expressive，需要单独一轮：升 AGP 9.x + compileSdk 37 + Gradle 9.x，并全面回归。

## 复现命令

```powershell
cd J:\qwen3.8\projects\AgentPilot-main\android
.\gradlew.bat :app:compileDebugKotlin --console=plain
```

探针文件 `app/src/main/java/com/agentpilot/app/ui/probe/ExpressiveProbe.kt` 记录了本轮结论，R1 完成后删除。
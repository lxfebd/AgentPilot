# R1 结论：主题层重建（形状 / 排版 / tonal 面）

> 完成日期：2026-10-10
> 全部改动基于 RikkaHub 源码实测值，不是推测。

## RikkaHub 实测事实（本轮证据）

| 项 | RikkaHub 实测值 | 来源 |
| --- | --- | --- |
| CardGroup 外沿圆角 | **20.dp** | `CardGroup.kt:50` `private val CardGroupCorner = 20.dp` |
| CardGroup 内项圆角 | **4.dp** | `CardGroup.kt:52` `private val CardGroupInnerCorner = 4.dp` |
| 分组内项形状 | `cardGroupItemShape(index, count)`：首尾 20，中间 4 | `CardGroup.kt:166-170` |
| 列表项容器色 | `colorScheme.surfaceBright` | `Color.kt:190` `listItemColors` |
| 卡片容器色 | `colorScheme.surfaceContainer` | `Color.kt:184` `cardColors` |
| 顶栏（浅色） | `colorScheme.surfaceContainer` | `Color.kt:178` |
| Typography | **默认 `Typography()`，无自定义** | `Type.kt:14` |
| 主题 | `MaterialExpressiveTheme` + `MotionScheme.expressive()` | `Theme.kt:107-112` |

## R1 改动（4 项）

### 1. 形状体系对齐 RikkaHub（`AAShapes.kt`）

| 项 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- |
| `AAShapes.medium` | 18.dp | **20.dp** | RikkaHub `CardGroupCorner = 20.dp` |
| `AAShapes.groupInner` | （无） | **4.dp** | RikkaHub `CardGroupInnerCorner = 4.dp` |
| `aaGroupItemShape` 中间项 | `RoundedCornerShape(0.dp)` | **`groupInnerShape`（4.dp）** | RikkaHub `cardGroupItemShape` 内项 4.dp |

影响范围：`medium` 是引用最多的形状常量（卡片/按钮/列表项），全局 2dp 上调，符合 AGENTS.md「favor larger corner radii」。

### 2. 排版强调化（`Theme.kt`）

RikkaHub 的 `Typography()` 虽是默认值，但其 `MaterialExpressiveTheme` 内置排版带 emphasized 变体（weight 600 + 更紧字距）。1.4.0 下这些字段是 internal，无法直接取。模拟方案：

```kotlin
titleLarge  -> SemiBold
titleMedium -> SemiBold
titleSmall  -> SemiBold
labelLarge  -> SemiBold
```

`displayLarge / headlineLarge / bodyLarge / bodyMedium` 保持原值（RikkaHub 也是默认）。

### 3. 中性面跟随 M3 tonal 体系（`withDynamicScheme`）

RikkaHub 的 `CustomColors` 把列表项/卡片/顶栏统一挂在 `surfaceContainer / surfaceBright / outlineVariant` 上，这是"看起来像 RikkaHub"的关键——**容器色跟着壁纸走**。

| 语义色 | 改前 | 改后（动态模式） |
| --- | --- | --- |
| `raisedSurface` | `Color.White` | `scheme.surfaceContainer` |
| `dialogSurface` | `Color.White` | `scheme.surfaceContainerHigh` |
| `border` | `Color(0xFFE7E5E0)` | `scheme.outlineVariant` |
| `subtle` | `Color(0xFFF1F0ED)` | `scheme.surfaceContainerLow` |

经典模式（`themeTone = Classic`）保持不变，不受影响。

### 4. Profile 页真正变成连续分段卡片

这是上一轮遗留的**最大视觉缺陷**：`AACardGroup` 注释说"组内不画分隔线"，但 Profile 页 9 处 `ProfileDivider()` 调用把组内又切回"卡片 + 分隔线"，等于什么都没变。

- `ProfileSettingsDrawer.kt`：删 3 处 `ProfileDivider()`
- `ProfileSettingsDetails.kt`：删 6 处 `ProfileDivider(start = 12.dp, end = 12.dp)`
- `ProfileSettingsComponents.kt`：删除已无引用的 `ProfileDivider` 函数与 `profileDividerColor` 私有函数

## 验证

```
:app:compileDebugKotlin  BUILD SUCCESSFUL
:app:assembleDebug      BUILD SUCCESSFUL（D8 重试后）
安装 → Success: streamed 49536953 bytes
启动 → PID 44172 存活 → 无 FATAL EXCEPTION
```

## 诚实说明

- 这些是**模拟**，不是真 Expressive。`MaterialExpressiveTheme` / `MotionScheme.expressive()` / `Typography.*Emphasized` 在 material3 1.4.0 全部 internal，R0 已实测确认。
- 圆角 18→20dp 的 2dp 差距肉眼几乎不可见，**真正能看出变化的是第 4 项（分隔线删除）**。
- 本模型无视觉能力，无法读截图。建议你在 MuMu 上打开 Profile 页确认连续分段效果。
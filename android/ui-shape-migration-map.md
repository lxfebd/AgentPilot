# 圆角归并映射表

> 状态：执行中（P4 阶段）
> 基线：P1 建立 `AAShapes` 之前，代码中有 153 处硬编码圆角、14 种数值。

## 映射规则

按语义层级就近归并，**不改变视觉尺寸所在量级**：

| 原值 | 目标 | 理由 |
| --- | --- | --- |
| 6 dp | `AAShapes.tiny` (6) | 值相同，直接对应 |
| 8 dp | `AAShapes.tiny` (6) | 代码块内联片段，等价量级 |
| 10 dp | `AAShapes.extraSmall` (10) | 值相同 |
| 12 dp | `AAShapes.extraSmall` (10) | chip / badge / 小方块 |
| 13 dp | `AAShapes.small` (14) | 就近 |
| 14 dp | `AAShapes.small` (14) | 值相同 |
| 15 dp | `AAShapes.small` (14) | 就近 |
| 16 dp | `AAShapes.small` (14) | 控件级 |
| 17 dp | `AAShapes.medium` (18) | 就近 |
| 18 dp | `AAShapes.medium` (18) | 值相同 |
| 20 dp | `AAShapes.large` (22) | 卡片级 |
| 22 dp | `AAShapes.large` (22) | 值相同 |
| 24 dp | `AAShapes.extraLarge` (26) | 容器级 |
| 26 dp | `AAShapes.extraLarge` (26) | 值相同 |

## 保留规则（不归并）

以下情形**必须保留原值**，因为它们承担独立语义：

- `CircleShape` / `CircleBorderShape`：圆形头像、状态点、图标按钮
- `RoundedCornerShape(50)` / 百分比：胶囊形（tag、chip）
- 显式四角不对称：`RoundedCornerShape(topStart = x, ...)`：分组卡片首尾项
- 极小值 0–5 dp：代码高亮背景、进度条等非容器元素

## 验证要求

每个批次完成后必须：

1. `./gradlew :app:compileDebugKotlin` 通过
2. 未使用 import 清理（`RoundedCornerShape` 若不再使用则移除 import）
3. 记录改动文件数与替换处数
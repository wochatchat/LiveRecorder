# Phase 2：悬浮球 FloatingOpPanel（MVP）

## 目标

实现三个快速添加入口（分享接收 + 剪贴板感知）和统一的悬浮确认卡片，使任何 App 内发现的直播链接都能一键加入监控。

## 入口

### 入口 A：分享接收（Share Intent）

- MainActivity 在 manifest 注册 `ACTION_SEND` / `text/plain` intent-filter
- 用户在其他 App 分享 → 选择 LiveRecorder → MainActivity 接收 `EXTRA_TEXT`
- `UrlExtractor.extractSupported(text)` 提取第一个受支持的直播 URL
- 写入 `ShareIntentRouter.pendingUrls` 队列（支持多次分享排队）

### 入口 B：剪贴板感知（Clipboard）

- MainActivity `onResume` 时检查剪贴板内容
- 仅当 Activity 从后台回到前台时（`startedFromBackground` 标记）才检查，避免每次导航回 Monitor 就弹
- 提取 URL → 去重（同上次已处理内容不重复弹）→ `ShareIntentRouter.pendingUrls` 追加
- 默认开启，后续可加设置开关

## 确认卡片 FloatingOpPanel

**UI 规范**：
- 卡片固定在屏幕底部（`Box(alignment = Alignment.BottomCenter)`），位于 FAB 上方，`padding(bottom = 80.dp)` 留 FAB 空间
- Surface，elevation 8，shape RoundedCornerShape(16.dp)，background MaterialTheme.colorScheme.secondaryContainer
- 左：平台徽标 + URL 文字（单行，ellipsize）
- 右：「忽略」TextButton + 「添加监控」Button
- 「添加监控」点击 → `viewModel.add(url)` + 清除 pending + 顶部 Snackbar 提示
- 「忽略」点击 → 清除 pending（本次会话不再出现）
- 若 URL 已在监控列表 → 自动清除，不弹卡片

**状态**：
- `pendingUrls: Queue<String>` — 一次可弹多张卡片（队首出列），支持同一会话多次分享
- 队首消费后自动显示下一张，直到队列空

## 文件变更

| 文件 | 操作 |
|---|---|
| `platform/UrlExtractor.kt` | 新建；纯函数，URL 提取逻辑 |
| `ui/navigation/ShareIntentRouter.kt` | 新建；pendingUrls 队列 |
| `ui/components/FloatingOpPanel.kt` | 新建；悬浮确认卡片 Composable |
| `MainActivity.kt` | 修改；handleSendIntent + onResume 剪贴板 |
| `MonitorScreen.kt` | 修改；消费 pendingUrls，显示 FloatingOpPanel |
| `AndroidManifest.xml` | 修改；ACTION_SEND intent-filter |
| `res/values/strings.xml` | 新增相关文案 |
| `platform/UrlExtractorTest.kt` | 新建；纯单元测试 |

## 依赖

- `PlatformRouter.isSupported`（纯 Kotlin，regex）
- `PlatformBadge`（已有）
- `MonitorViewModel.add`（已有）

## 验收标准

1. 从浏览器分享直播链接 → LiveRecorder 打开 → 显示确认卡片（平台徽标 + URL）→ 点「添加监控」→ Snackbar + 卡片消失
2. App 后台切回前台 → 剪贴板含直播 URL → 显示确认卡片（同上流程）
3. 已监控的 URL 不重复弹（队列自动跳过）
4. 多次分享排队依次展示（队首消费后自动显示下一张）
5. `UrlExtractorTest` 全场景覆盖（分享文本含标题+URL、多 URL、不支持平台、空文本、中文标点）
6. compile-check 全绿
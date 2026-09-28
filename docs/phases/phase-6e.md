# Phase 6e：录制记录页（R18 / R19 / R20）

目标：U12 成果管理。录制结束落库 → 记录页列表（播放/分享/删除）→ 存储用量入口。

## R18 — RecordHistoryStore（数据层）
- `data/RecordHistoryStore.kt`：独立 DataStore "history"，JSON 数组单键（org.json 转义兜底），
  结束时间倒序保留最近 500 条（cap）。
- 记录字段：url / platform / anchorName / title / savePath / endTimeMs / durationMs / bytes / completed。
- 写入时机：RecordController 新增 `onFinished` 构造钩子，setState 里对
  **确有落盘文件**（savePath 非空 && bytes>0）的 Finished 触发；Failed（无路径信息）不落库。
- RecordState.Finished 追加 anchorName/title/platform 字段（默认空串，向后兼容旧测试）；
  runRecord 用 curAnchor/curTitle/curPlatform 跟踪（取消分支也能取到）。
- RecorderApp 接线 onFinished → historyStore.add。
- 单测：data/RecordHistoryStoreTest（JSON 往返/转义/垃圾输入/500 条裁剪）。

## R19 — RecordsScreen（列表页）
- `ui/RecordsViewModel.kt`：allEntries + RecordFilter(todayOnly/platformKey) + filtered +
  stats（今日条数/合计字节）+ delete（文件/分段目录递归删除 + 记录移除，一次性 Snackbar）。
- RecordsScreen 重写：筛选 chips（全部/今日/平台，平台 chips 来自全部记录避免跳变）→
  统计行「今日录制 X 条 · 合计 Y」→ LazyColumn RecordCard
  （平台徽标 + 主播名 + 标题 + 时长/大小/日期/文件名 + 完成/停止图标）。
- 操作：播放（FileProvider ACTION_VIEW，分段目录取最新视频文件）/ 分享（ACTION_SEND）/
  删除（确认 AlertDialog）。
- 空态：「还没有录制记录」（R23 再打磨）。

## R20 — 存储用量入口
- StorageManager 增 totalGb()；新增 StorageUsage(freeGb/totalGb, usedGb, usedFraction)。
- 设置页新增「存储管理」分组：用量进度条 + 已用/共/剩余 + 存储剩余告警阈值输入
  （复用 SaveOnFocusLostField，diskLimitGb 落 MonitorStore，首次 UI 接线）。
- 记录页顶部存储条（进度 + 剩余/共）。两个页面用量均 30s 轮询刷新。
- 单测：StorageManagerTest 追加 StorageUsage 3 例。

## 红线遵守
- RecordController 仅追加：构造参数 onFinished、Finished 三字段、cur* 跟踪变量、setState 挂钩。
  录制主流程零改动；MonitorLoop/MonitorService 未动。
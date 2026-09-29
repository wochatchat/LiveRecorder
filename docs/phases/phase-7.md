# Phase 7：平台广度与推送补齐

> 依据 docs/porting-completeness.md 待做清单优先级。真机验收（清单 A~F）按用户指示后置。
> 红线不变：RecordController / MonitorLoop / MonitorService 录制服务接线不动。

## 7a — R37 自定义流地址直录 ✅（commit 461dbeb）

对照上游 main.py:1026-1038「自定义录制直播」：URL 含 .m3u8/.flv 且非任何已知平台域名 → 跳过房间解析直录。

- [x] `PlatformRouter.isDirectStreamUrl()`：非已知平台（含抖音/iesdouyin）且含 .m3u8/.flv；已知平台域名优先（与上游 if/elif 链语义一致，douyu.com/123.flv 仍走斗鱼）
- [x] `fetchDirectStream()`：is_live 恒 true，record_url = URL 本身（.flv → flvUrl，否则 m3u8Url）
- [x] **移动端增强**：anchor_name 用 URL 哈希稳定 8 位（`自定义录制直播_xxxxxxxx`）——上游每轮 uuid4[:8] 随机导致文件名每轮都变，安卓端固定后同名主播文件可归组
- [x] `isSupported` 收入 → 添加对话框预校验放行（R14 通道）
- [x] `MonitorCard.platformKeyForUrl` + PlatformBadge：`custom` 徽标「自定义」（灰蓝色 0xFF607D8B；原 else 落抖音会错挂徽标）
- [x] 单测 +6：判定真/假（含已知平台含扩展名不抢路由）、isSupported、flv/m3u8 字段映射、anchorName 稳定性与格式

## 7b — 推送明细配置 ✅（commit 8a953f5 + afa4da3 + cb1a304）

对照上游 config.ini [推送配置] + msg_push.py。

- [x] 自定义推送标题/开播/关播文案（PushConfig title/liveMessage/offlineMessage，占位符 [直播间名称]/[时间]，空 → 默认；buildTitle/buildContent；R38，8a953f5）
- [x] bark 铃声/中断级别（barkLevel/barkSound，空 → active/不定制；R38，8a953f5）
- [x] `\n` 转义替换移入 buildContent（单测直调 buildContent 覆盖；afa4da3）
- [x] ntfy tags/priority 定制（ntfyTags 逗号分隔、ntfyPriority 1-5，空/0 → 默认 partying_face/3；parseTags/coercePriority；ntfy 分支 tags 输入框 + priority ChipRow min~max；R39，cb1a304）
- [x] MonitorStore 持久化 10 键 + setPushConfig 全参默认值兼容（PushGroup 本地 savePush 携带全量当前值防重置）；单测累计 +12
- [ ] （评估）推送检测独立频率（上游 1800s，现复用循环时间）——暂缓：移动端顺序轮询下无收益

## 7c — mkv/mp4 直存格式 ✅（commit 4cf8364 + 98b4e57，CI 全绿 run 36433552723）

对照上游 config.ini save_type 全集（ts/mkv/flv/mp4/mp3/m4a），安卓端对齐 ts/mkv/mp4：

- [x] `FfmpegRecorder.recordDirect()`（7c）：mkv → `-f matroska -c copy`；mp4 → `-f mp4 -c copy -bsf:a aac_adtstoasc`；不经 segment muxer，单文件直写；提取 `commonArgs()` 供分段/直存共用
- [x] `RecordController` 直存分支（saveFormat 钩子）：mkv/mp4 路由到 `recordDirect()`，每轮重连新建时间戳文件（同上游语义）；ts 走现有 `record()` + `convertSegmentsAsync` 路径不变
- [x] `AppSettings.saveFormat`（ts/mkv/mp4，默认 ts）+ `AppSettingsStore` 持久化（`save_format` 键）
- [x] `RecorderApp` 接线 `saveFormat = { appSettings.settings.first().saveFormat }`
- [x] 设置页录制分组：保存格式 ChipRow（ts/mkv/mp4）；mkv/mp4 时自动隐藏分段开关/分段时间/TS→MP4 转换开关（仅 ts 有意义）
- [x] 单测 +4：`FfmpegRecorderDirectTest`（mkv/mp4 参数验证 + 公共参数验证）+ `RecordControllerSaveFormatTest`（mkv/mp4/ts/空 4 路径覆盖）
- 踩坑 2 个：① 并行 file_edit AppSettings 导致 `set()` 写入重复块，`commonArgs` 抽取后 brace 负数暴露；② 同理数据类 `saveFormat` 字段丢失，补回后全绿

## 验收
- CI compile-check 全绿 + 单测不回退（266+）
- 真机：添加一条 .m3u8/.flv 直链 → 卡片显示「自定义」徽标 → 录制落盘（并入走查清单 A 组）

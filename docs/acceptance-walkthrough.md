# 真机截图走查清单（Phase 5e/5f + 6b~6f + 7/8 平台与功能 攒批验收）

> 用途：Phase 6f-4 收口前的统一真机走查。测试包：GitHub Actions run 36725061192 Artifact `liverecorder-apk`（versionName 0.1.12/code 112，2026-09-30 出包，含 Phase 9 Batch A~G 全部 46 个平台；旧包 run 36527449985 已过期）。
> 规则：每项 **深色/浅色各一张**，命名 `<轮次>-<要点>-<light|dark>.png`，归档 `docs/screenshots/`，拍完在本文档勾选。
> 操作路径：系统设置切换深色模式 → 回 App 截图（App 跟随系统，无内置开关）。

## A. Phase 5e/5f 遗留（功能验收，截图 + 文字记录）

> **注意**：Phase 9 Batch A~G 的 27 个新平台（9a~9g，46 平台合计）均未经过真机录制验证。走查时优先用这些平台实测——抖音/斗鱼/快手/虎牙 等 Phase 1-8 平台已在历史版本验证过，新平台是本次重点。

- [ ] 长录制稳定性：单路连续录制 ≥30min，卡片时长/大小走秒正常，无中断
- [ ] 后台存活：锁屏 + 挂后台 30min，常驻通知在，回前台状态一致
- [ ] 分段文件可播放性：分段落盘后用系统播放器逐段打开
- [ ] （对照）同房间与上游 CLI 各录 60s，文件均可播放

## B. Phase 6b 导航与主题

- [ ] 3 Tab 底部导航：监控 / 记录 / 设置 三个页面可切换
- [ ] 深浅色整体观感：主题色跟随系统取色（Android 12+ 动态取色）

## C. Phase 6c 监控页

- [ ] 监控卡片：平台徽标 + 主播名大字 + URL 小字（非 URL 当标题）
- [ ] 录制中卡片：大字时长 + 进度条；断流重连/解析中的状态行
- [ ] 空闲卡片轮次摘要：上次检查 X 前 / 下轮倒计时
- [ ] 添加对话框：多行/逗号批量粘贴 → 逐条添加；不支持的 URL 出预校验提示
- [ ] Snackbar：添加成功（可撤销）、移除（可撤销）、设置已保存
- [ ] 总开关「监控中 / 已暂停」文字按钮状态切换
- [ ] 删除确认对话框（含录制中删除的后果明示）

## D. Phase 6d 设置页

- [ ] 设置页 6 分组全屏滚动，底部项目可达可存（对照原 U1 超屏问题）
- [ ] 数值输入（循环时间）清空/非法输入 → 错误提示，非静默丢弃
- [ ] Cookie 管理页：平台列表、已配置掩码（前3字符+***）、编辑、多选批量清除
- [ ] 推送「发送测试」按钮 → Snackbar 成功/失败反馈 + 真收到推送

## E. Phase 6e 记录页

- [ ] 记录页列表：筛选 chips（全部/今日/平台）+ 顶部统计行 + 存储用量条
- [ ] 录制一段 → 记录出现；RecordCard 播放（系统播放器）/ 分享 / 删除确认
- [ ] 设置页「存储管理」分组用量显示

## F. Phase 6f 引导与打磨

- [ ] 首启 Onboarding 3 页（欢迎/权限/开始使用），完成后不再出现
- [ ] 监控页空态（示例链接预填）/ 记录页空态
- [ ] 常驻通知：暂停/恢复 Action 生效且 UI 同步
- [ ] 开播通知点击 → 直达对应卡片 + 4s 高亮
- [ ] 列表增删动画（添加/删除监控、删除记录时 item 动画）
- [ ] 系统大字体（1.3x）下无截断（可选）

## G. Phase 7 功能（7a 直录 / 7b 推送明细 / 7c 直存）

- [ ] 自定义流地址直录：添加 .m3u8/.flv 直链 → 「自定义」徽标 → 直录落盘；anchor 名为 URL 哈希稳定 8 位
- [ ] 推送明细：自定义标题/开播/关播文案（占位符 [直播间名称]/[时间] 替换生效）；bark 级别/铃声；ntfy tags/priority 生效
- [ ] 保存格式 mkv/mp4：设置页 ChipRow 切换 → mkv/mp4 直存落盘且可播放；切回 ts 分段行为不变

## H. Phase 7d~8c 新平台真机

- [ ] 国内：网易CC / 知乎 / 百度 / 微博 / 京东（7d）+ 小红书（8b，xhslink 短链或 user/profile 链接）
- [ ] 签名类：来秀 / 淘宝（cookie 需含 _m_h5_tk）/ 嗨秀 / 乐嗨 / LiveMe（8a）
- [ ] 海外：TikTok（8c，需配置代理；区域封锁页回落未开播不崩）
- [ ] 各新平台徽标显示正确、主播名/标题解析正确、录制落盘可播放

## I. Phase 9 新平台真机（27 个新平台）

> 本批次全部 27 个新平台均需真机验证，重点检查：①徽标是否正确显示；②主播名/标题解析是否正常；③录制是否落盘且可播放。可选择各批次代表性平台重点测试。

### 9a 简单 API 类
- [ ] Twitch（twitch.tv）：徽标、m3u8 录制
- [ ] YouTube（youtube.com）：徽标、HLS 录制
- [ ] Shopee（shopee 直播，需 cookie 含 _m_h5_tk）
- [ ] Acfun（acfun.cn）：visitor login 流程
- [ ] CHZZK（chzzk.naver.com）：韩国平台，m3u8 录制

### 9b 中文小众平台
- [ ] 花椒（huajiao.com）：双路径录制
- [ ] 流星（7u66.com）：简单 JSON
- [ ] 映客（inke.cn）：简单 JSON
- [ ] 音播（ybw1666.com）：简单 JSON

### 9c 韩国平台（需登录）
- [ ] SOOP（sooplive.co.kr）：登录 cookie
- [ ] PandaTV（pandalive.co.kr）：简单 JSON
- [ ] WinkTV（winktv.co.kr）：双 API
- [ ] FlexTV（flextv.co.kr）：登录流程
- [ ] PopkonTV（popkontv.com）：登录流程

### 9d 小众中文平台
- [ ] 猫耳FM（missevan.com）：二次请求
- [ ] 酷狗（fanxing2.kugou.com）：双 API
- [ ] 畅聊（tlclw.com）：简单 JSON
- [ ] VV星球（vvxqiu.com）：简单 JSON

### 9e 小众中文平台（续）
- [ ] 17Live（17.live）：REST API
- [ ] 浪Live（lang.live）：简单 API
- [ ] 漂漂（weimipopo.com）+ 花猫（catshow168.com）：同 spider 双域名

### 9f 小众中文平台（续）
- [ ] 六间房（6.cn）：HTML + POST API
- [ ] 连接（lailianjie.com）：webrtc→https 协议
- [ ] 千度热播（qiandurebo.com）：HTML 提取
- [ ] ShowRoom（showroom-live.com）：双 API，HLS

### 9g 需账号平台
- [ ] Blued（blued.cn）：decodeURIComponent JSON，需 cookie
- [ ] TwitCasting（twitcasting.tv）：双域名验证（.tv/.jp/.net），visitor token 流程

## 收口

- [ ] 截图归档 `docs/screenshots/`（命名规范：`<轮次>-<要点>-<light|dark>.png`）
- [ ] 功能验收项（长录制/后台存活/分段可播放性）文字记录
- [ ] Phase 9 所有平台均验证至少 1 个可录制落盘
- [ ] 本清单勾完 → 更新 docs/porting-completeness.md 取消「真机验收」待办 → Phase 9 正式收口

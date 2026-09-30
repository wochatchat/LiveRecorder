# LiveRecorder

安卓原生直播监控录制 App（Kotlin + Jetpack Compose），移植自 [ihmily/DouyinLiveRecorder](https://github.com/ihmily/DouyinLiveRecorder)（Python，v4.0.7）。

无人值守后台监控 + 录制直播流：主播开播自动落盘，断流自动重连，分段录制，TS→MP4 转封装，开播/关播推送。

## 功能

### 监控与录制
- **多房间监控**：前台 Service 常驻轮询（默认 300s，可调），开播即录，关播自动结束
- **46 个平台解析**：抖音（web/app/HTML 三路径，a_bogus 签名 Kotlin 重写）、斗鱼（动态 JS 签名，QuickJS）、快手（web/api2 双路径）、虎牙（web anti-code / app 双路径 + 画质分流）、B站、YY、Bigo、网易CC、知乎、百度、微博、京东、来秀、淘宝、嗨秀、乐嗨、LiveMe、Twitch、YouTube、Shopee、Acfun、CHZZK、花椒、流星、映客、音播、SOOP、PandaTV、WinkTV、FlexTV、PopkonTV、猫耳FM、酷狗、畅聊、VV星球、17Live、浪Live、漂漂、花猫、六间房、连接、千度热播、ShowRoom、Blued、TwitCasting、小红书、TikTok；详见 [docs/phases/phase-9.md](docs/phases/phase-9.md)
- **录制**：FLV 直下（OkHttp 流式）+ ffmpeg 分段（`-c copy -f segment`，HLS/FLV 通用）；断流重连（指数退避 2s→60s 封顶）；关播/断流语义对齐上游
- **转封装**：TS→MP4 remux、m4a 提取（`-c copy` 容器级），可开关、可删原片
- **文件命名**：平台/主播/日期/标题目录层级 + 文件名含标题 + emoji 清理，逐行对齐上游 main.py 命名规则
- **画质选择**：原画/蓝光/超清/高清/标清/流畅，各平台分流语义对齐上游（虎牙 OD/BD/UHD 走 app 路径等）

### 配置（对齐上游 config.ini）
- 常用：画质 / 循环时间 / 推送 / Cookie 入口 / 代理
- 高级：分段开关与时长 / 强制 https / 转码删原片 / 只推送不录制 / 文件命名 5 项 / 去表情
- 持久化 DataStore，默认值逐项对照上游

### 推送与通知
- **本地通知**：开播 / 关播 / 存储不足（上游 CLI 无此概念，移动端增强）
- **HTTP 推送**：ntfy / bark（多地址，文案对齐上游 push_message）；微信/钉钉/TG/邮箱等渠道砍掉
- 开播 / 关播推送独立开关，可只推送不录制

### 平台基础设施
- **代理**：per-platform http/socks5，关键词白名单对齐上游 resolve_proxy
- **Cookie / 账密**：50 平台 cookie 录入 + 4 需登录平台账密（SOOP/FlexTV/PopkonTV/TwitCasting）
- **健康徽标**：连续失败 3 轮置灰「失效」，不阻塞其他条目
- **日志**：streamget.log / playurl.log 双文件（对齐上游 logger.py），300KB 轮转，UI 查看/清空/导出分享
- **存储阈值**：低于阈值暂停全部录制并通知，恢复自动继续
- **录制统计**：时长 / 大小 / 平均码率走秒显示

## 平台支持

✅ 抖音 · 斗鱼 · 快手 · 虎牙 · B站 · YY · Bigo · 网易CC · 知乎 · 百度 · 微博 · 京东 · 来秀 · 淘宝 · 嗨秀 · 乐嗨 · LiveMe · 小红书 · TikTok · Twitch · YouTube · Shopee · Acfun · CHZZK · 花椒 · 流星 · 映客 · 音播 · SOOP · PandaTV · WinkTV · FlexTV · PopkonTV · 猫耳FM · 酷狗 · 畅聊 · VV星球 · 17Live · 浪Live · 漂漂 · 花猫 · 六间房 · 连接 · 千度热播 · ShowRoom · Blued · TwitCasting（46/51）

❌ 咪咕（miguvideo.com，WASM 依赖，暂缓）。所有未接入平台均支持 7a 自定义流直链（输入 .m3u8/.flv URL 直接录制）。详细进度见 [docs/phases/phase-9.md](docs/phases/phase-9.md)。

## 架构

```
ui/            Compose 界面（监控列表 / 设置 / 统计 / 日志）
service/       MonitorService（前台服务 dataSync）+ EventNotifier
monitor/       MonitorLoop 轮询调度（健康检查 / 错误窗口 / 低存储暂停）
recorder/      RecordController 状态机 · RecordSource（选源/命名/画质）
               StreamDownloader（OkHttp 直下）· FfmpegRecorder（分段/remux）
platform/      PlatformRouter + 48 个平台 Spider（douyin/douyu/kuaishou/huya/bilibili/yy/bigo/netease/zhihu/baidu/weibo/jd/laixiu/taobao/haixiu/liveme/xhs/tiktok/twitch/youtube/shopee/acfun/chzzk/huajiao/liuxing/inke/yinbo/soop/pandatv/winktv/flextv/popkontv/maoerfm/kugou/changliao/vvxqiu/live17/langlive/pplive/liujianfang/lianjie/qiandurebo/showroom/blued/twitcasting 等）
sign/          AbSign（RC4+SM3 a_bogus）· Sm3 · QuickJS（JNI，斗鱼动态签名）
data/          MonitorStore · AppSettings · AuthStore · ProxySettings · AppLog（DataStore）
net/           OkHttp 封装（HTTP/2、UA 伪装、per-platform header、自签兜底）
push/          HttpPusher（ntfy/bark HTTP 推送）
storage/       StorageManager（磁盘阈值）
```

C/NDK 组件：**QuickJS**（斗鱼 ub98484234 动态 JS 签名，上游 JS 原样执行）+ **ffmpeg**（CI NDK 最小构建，仅 demuxer/muxer/protocol，libffmpeg.so 随包分发）。签名算法（SM3/RC4/a_bogus）为纯 Kotlin，无 JS 依赖。

## 构建与 CI

不本地打包，一律 GitHub Actions：

| Workflow | 职责 |
|---|---|
| `build.yml` | push main：版本自增 + 签名 APK 归档 `apk/` + GitHub Release |
| `build-test.yml` | 手动：测试包 Artifact（不 bump 不发布），真机验收用 |
| `compile-check.yml` | feature/fix 分支 + PR 编译 + 266 单测 |
| `cleanup-artifacts.yml` | 每日清理 Artifact（保留最新 10 个） |

版本号唯一真源：根目录 `version.properties`（build.sh 自动管理，不手动编辑）。

## 测试

266 单元测试全绿（CI 强制）：正则解析 fixture 对拍真实页面、a_bogus 与上游 Python 逐字节对照、SM3 国标向量、录制状态机/重连/统计、命名与配置语义、推送组包。上游无单测，对齐验证靠行为对照（同 URL 双侧跑比对）。

## 界面

底部 3 Tab：**监控**（直播间卡片、状态行、Snackbar 反馈、总开关）/ **记录**（录制历史、播放/分享/删除、存储用量）/ **设置**（分组全屏页：录制/命名/推送/Cookie/代理/维护）。深浅色跟随系统，Android 12+ 动态取色；首启有 3 页引导，常驻通知支持暂停/恢复与开播直达。

> 截图待真机走查后补（清单：[docs/acceptance-walkthrough.md](docs/acceptance-walkthrough.md)，归档 `docs/screenshots/`）。

| 页面 | 浅色 | 深色 |
|---|---|---|
| 监控 | <!-- TODO: screenshots/6c-monitor-light.png --> | <!-- TODO: screenshots/6c-monitor-dark.png --> |
| 记录 | <!-- TODO: screenshots/6e-records-light.png --> | <!-- TODO: screenshots/6e-records-dark.png --> |
| 设置 | <!-- TODO: screenshots/6d-settings-light.png --> | <!-- TODO: screenshots/6d-settings-dark.png --> |
| 引导 | <!-- TODO: screenshots/6f-onboarding-light.png --> | <!-- TODO: screenshots/6f-onboarding-dark.png --> |

## 文档

- 移植完成度审计（逐模块对照上游 v4.0.7）：[docs/porting-completeness.md](docs/porting-completeness.md)
- 真机走查验收清单（5e/5f + 6b~6f）：[docs/acceptance-walkthrough.md](docs/acceptance-walkthrough.md)
- 移植评估与进度（活文档）：`PORTING.md`
- 详细设计 / 阶段记录：shared 仓库 `douyin-recorder-android/`（docs + phases + CHANGELOG）

## License

MIT（同上游）

# 移植完成度审计：DouyinLiveRecorder → LiveRecorder (Android)

> 审计基准：上游 ihmily/DouyinLiveRecorder v4.0.7（commit add187f，main.py 2154 行 + spider.py 3394 行 + msg_push.py 295 行）
> 安卓端：wochatchat/LiveRecorder @ 70bfdd6（分支 feature/phase1-1a-http-client），266 单测全绿，CI 全绿
> 审计日期：2026-09-25 · 方法：逐模块对照上游 main.py / spider.py / config.ini / msg_push.py 与安卓端源码

## 一、总评

**核心录制链路对齐度约 90%，平台覆盖 7/52（13%）。**

一条直播从 URL 到落盘的全链路（解析 → 画质分流 → 下载/ffmpeg 分段 → 断流重连 → TS→MP4 remux → 统计/日志/推送）已与上游语义对齐并有移动端增强。未完成部分集中在：长尾平台移植（增量工作，不影响已交付平台）、音频格式（mp3）、周边推送明细配置。

| 模块 | 完成度 | 说明 |
|---|---|---|
| 网络层（HTTP/2/代理/UA/超时） | 100% | OkHttp，对齐 httpx 行为 |
| 签名（SM3/RC4/a_bogus） | 100% | 纯 Kotlin，与上游 Python 逐字节一致 |
| 直播解析（已移植平台） | 100% | 每平台真值/fixture 验证 |
| 录制核心（下载/分段/重连/转封装） | ~90% | 缺 mp3/h264/自定义流地址 |
| 监控调度 | ~95% | 顺序轮询替代多线程（移动端语义等价） |
| 配置体系 | ~80% | 已对齐项语义精确；推送明细/自定义文案未做 |
| 消息推送 | ~40% | 7 渠道保留 2 + 本地通知增强 |
| 平台广度 | 13% | 7/52，增量交付中 |
| UI（CLI/Web 替代） | 100% | 统计面板/日志页/设置两级，均有上游外增强 |
| **综合（核心功能 vs 平台广度加权）** | **核心 ~90% / 全量 ~45%** | 平台是纯增量工作，每平台独立可交付 |

## 二、平台解析（spider.py 52 平台 → 已移植 7 个）

✅ 已完成（全部真值对拍或 fixture 验证）：

| 平台 | 安卓实现 | 关键对齐点 |
|---|---|---|
| 抖音 | DouyinWebSpider + DouyinAppSpider + DouyinHtmlSpider | a_bogus 签名 Kotlin 重写（SM3 国标向量 + 固定输入逐字节一致）；web/app/HTML 三路径；五档画质 ORIGIN 合并 |
| 斗鱼 | DouyuSpider + QuickJS | ub98484234 动态 JS 从房间页提取后 QuickJS 执行，签名与 PyExecJS 一致 |
| 快手 | KuaishouSpider | web `__INITIAL_STATE__` + api2 双路径；bitrate 无命中取最低档；pickReversed 末位沿用 |
| 虎牙 | HuyaSpider | OD/BD/UHD 仅 app 路径（TX CDN 优先 + ctype/fs 替换），HD/SD/LD 仅 web anti-code 重算；互不回退 |
| B站 | BilibiliSpider | room_init + Master/info + getH5InfoByRoom 三 API 链；qn 映射降序回退 |
| YY | YySpider | 房间页正则 + stream-manager v3 POST；avp_info_res 存在即开播 |
| Bigo | BigoSpider | getInternalStudioInfo + 短链解析 + 页面双正则兜底 |

未移植 45 个：TikTok/小红书/网易CC/百度/知乎/微博/京东/网易音乐人（第 1-2 批剩余）、JS 签名类 7 个（QuickJS 已就绪可低成本接入）、长尾/海外/需登录 20+（详见 docs/05-platforms.md，每平台带 spider.py 行号定位）。

上游已有但安卓缺失的解析入口：**自定义流地址直录**（上游 URL_config.ini 支持直接填 m3u8/flv 地址，PlatformRouter 目前未知域名回落抖音解析）——**待补，成本近零**。

## 三、录制核心（main.py）

已对齐：FLV 直下（OkHttp 16KB 分块，取消保留半截文件）；ffmpeg 分段（-c copy -f segment -segment_time）；TS→MP4 remux + m4a 提取（-c copy，成功且产物非空才删原文件）；断流重连（指数退避 2s×2ⁿ 封顶 60s，连续 5 败放弃；关播探测→Finished 保留内容）；文件命名/目录规则（main.py:1117-1146 逐行对齐，含上游怪癖）；画质分流（含虎牙 OD/BD/UHD 仅 app 路径互不回退的真实语义）；强制 https + shopee/migu 例外；磁盘阈值（默认 1.0GB，增强：空间恢复自动继续）；命名 clean_name/去表情 Unicode 区间逐字符对齐。

未做（按上游行为列出）：
- **MP3 音频录制**（仅音频）——内置 ffmpeg 无对应编码，评估放弃
- **MKV / MP4 直存**——现仅 TS 分段 + TS→MP4 remux（上游 save_type 全集 ts|mkv|flv|mp4|mp3|m4a）
- **h264 重编码**（libx264，上游默认关）——移动端 CPU/体积代价大，放弃
- **时间字幕文件**（srt/ass 每秒时间戳）——评估放弃（5a）
- **录制后自定义脚本**——Android 无 shell 生态，设计不移植
- **config 备份（backup_file_start，保留 6 份）**——DataStore 无 ini 损坏问题，不需要
- 上游 v4.0.7 的 **segment_video（转换后再分段）** 分支：安卓端 ffmpeg 直接 -f segment 一步到位（等价语义）

移动端改进（上游没有）：断流探测 60s readTimeout（上游 timeout=None 会永久挂死）、重连指数退避 + 单段 60s 清零防快循环、录制统计（时长/大小/码率走秒）。

## 四、监控调度

已对齐：循环间隔（默认 300s）、开播即录/关播即停、录制中条目暂停轮询、单条目互不阻塞、URL 列表动态增删（UI 替代手改 ini）、磁盘检查、开播/关播事件。
移动端差异（语义等价）：顺序轮询替代多线程（省电）；错误窗口阈值 20 + 额外延迟 60s（上游 threshold=5）；**健康徽标**——连续 3 轮失败置灰「失效」、偶发错误红色，上游无可视化。
未做：直播状态推送独立检测频率（上游 1800s，移动端复用循环时间）。

## 五、消息推送（msg_push.py）

- ✅ ntfy：topic 取地址末段、tags/priority/actions 对齐，多地址中英文逗号分隔
- ✅ bark：多地址、level active，失败仅记日志（同上游）
- ✅ 开播/关播文案对齐 push_message（「[名称] 正在直播中，时间：[时间]」「直播已结束！」）
- ✅ Android 本地通知：开播/关播/低存储，独立渠道，可点跳转（上游无对应，移动端增强）
- ❌ 砍掉：钉钉/企业微信/TG/邮箱/pushplus/息知（设计决策）
- ⬜ 未做：自定义推送标题/开播/关播文案、bark 铃声/中断级别、ntfy 邮箱、钉钉@/@全体、推送检测独立频率（上游 1800s）

## 六、Cookie / 账密（config.ini [Cookie] 等 3 节）

- ✅ AuthStore：50 平台 cookie 键（对齐 config.ini [Cookie]）+ 4 登录平台账密（sooplive/flextv/popkontv/twitcasting）+ 录入 UI（CookieDialog）
- ⚠️ 覆盖面：录入 UI 已备 50 平台，但 spider 只有 7 个平台消费 cookie——随平台移植逐个接通

## 七、日志（src/logger.py → AppLog）

- ✅ streamget.log（D/W/E）+ playurl.log（仅 INFO）双文件、格式逐行对齐、300KB 轮转保留 1 份
- ✅ 日志页：查看/刷新/清空/导出分享（FileProvider）——上游无 UI，移动端增强

## 八、其他上游功能

| 上游 | 安卓 | 说明 |
|---|---|---|
| 抖音 a_bogus 签名（ab_sign.py RC4+SM3） | ✅ | 固定输入逐字节一致；QuickJS 另用于斗鱼 |
| 代理检测（ProxyDetector） | ➖ | per-client 代理 + 平台白名单对齐；系统代理检测跳过（CLI 专属） |
| ffmpeg 存在性检查 | ✅ | nativeLibraryDir 探测 libffmpeg.so |
| ffmpeg 安装引导（ffmpeg_install.py） | 不需要 | so 随 APK 分发 |
| Web 面板（index.html） | 不移植 | 原生 Compose UI 替代 |
| display_info CLI 面板 | ✅ 替代 | 录制统计面板（时长/大小/码率走秒）+ 日志页 |
| 时间字幕（generate_subtitles srt/ass） | ❌ | 见第三节 |
| 抖音硬编码 ttwid Cookie | ✅ | AuthStore 可编辑（上游同策略） |

## 九、结论与剩余工作

**对齐良好**：核心录制链路、监控调度、配置默认值、命名规则、日志、bark/ntfy 推送、7 平台解析。
**明确放弃/不适用（12 项）**：CLI 专属 5 项、语言/保存路径系统托管、h264 重编码、mp3、时间字幕、config 备份。
**待做清单（按优先级）**：
1. 自定义流地址直录（上游支持直填流地址，现缺）
2. 推送明细配置（自定义标题/文案、bark 级别/铃声、多渠道）
3. 平台移植推进（第 2 批剩余：小红书/网易CC/百度/知乎/微博/京东/网易音乐人；第 3 批 JS 签名类 7 个——QuickJS 已就绪）
4. mkv/mp4 直存格式
5. 2i/3i/5f 真机验收收尾（长录制、后台存活、分段文件可播放性）

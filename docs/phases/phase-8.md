# Phase 8 — 8a：JS 签名类平台（第 3 批）

> 分支 feature/phase1-1a-http-client · 依据 docs/porting-completeness.md 待做清单第 3 项。
> 上游基准：ihmily/DouyinLiveRecorder v4.0.7（add187f）。

## 范围裁决（上游 src/javascript/ 6 个签名脚本 → 安卓侧逐个评估）

| 上游脚本 | 平台 | 安卓侧决策 |
|---|---|---|
| laixiu.js | 来秀 imkktv.com | **纯 Kotlin**（上游 Python 路径未用 JS，直接 MD5 签名）✅ |
| taobao-sign.js | 淘宝 tb.cn | QuickJS 直接执行（自包含，无依赖）✅ |
| haixiu.js + crypto-js | 嗨秀 haixiutv.com / 乐嗨 lehaitv.com | QuickJS + require/console 垫片 ✅ |
| liveme.js + crypto-js | LiveMe | QuickJS + 垫片（对象展开语法 QuickJS/Rhino 1.7.15 均支持）✅ |
| migu.js | 咪咕 | **暂缓**：ddCalcu 依赖 WebAssembly + fetch（QuickJS 无 WASM；上游也是 node 子进程专属）|
| x-bogus.js | TikTok/抖音 reflow | **不随本批**：抖音 reflow 安卓端已用 a_bogus(Kotlin) 覆盖；TikTok 当前上游为 SIGI_STATE HTML 解析（无 JS），归入下一批 |

## 交付（commit 9ea0825→2cc7166，CI 全绿 run 36517291724）

- **JsScripts.kt**（生成文件，勿手改）：crypto-js.min.js（3 块拼接防 JVM 常量池 64KB 上限）、taobao-sign.js、haixiu.js、liveme.js 嵌入为 Kotlin 常量；生成脚本按 `$ → ${'$'}` 转义。
- **JsScriptRunner**：execjs.call 桥——CommonJS 预置（module/exports）→ crypto-js 先载并快照 `__CryptoJS`（平台脚本随后覆写 module.exports，必须先快照再定义 require）→ console 静默垫片 → `JSON.stringify(sign(...))`。双引擎约定：QuickJS JNI 与 Rhino 均对字符串结果原样 ToString。
- **LaixiuSpider**（纯 Kotlin）：uuid 无- + ts + 固定盐 → MD5 requestId；playStatus==0 开播 → playUrl(flv)。
- **TaobaoSpider**：cookie 必须含 _m_h5_tk（缺失直接返回未开播，不发请求）；liveId 缺失时抓页 `var url='...'` 重定向；jsonp 解析（utils.jsonp_to_json 同正则）；liveUrlList 按 definition 降序（lld→ud）+ QUALITY_MAPPING 索引；recordUrl = m3u8（上游 url_type='all'）。**已知差异**：上游失败续 token 重签，安卓简化为同参重试。
- **HaixiuSpider**（嗨秀+乐嗨）：accessToken 按域名取固定值（URL 双重编码态，入参前双重解码）；haixiu.js 签名 → _ajaxData1（JSON 字符串解包一层引号，对齐 execjs 字符串语义）；live_status==1 → media_url_web(flv)。
- **LiveMeSpider**：无 index.html 时 og:url 换真实地址；liveme.js sign → lm_s_sign 入 lm-s-sign 头、tongdun_black_box/os 剥离为 query、其余 form POST；video_info.status=="0"（字符串）开播 → recordUrl = m3u8 ?: flv。
- **PlatformRouter**：+4 平台分流（haixiutv/lehaitv/imkktv/liveme/tb.cn）+ isSupported/isDirectStreamUrl 链更新 + fetchHaixiu 按域名分 cookie（haixiu/lehaitv 两键）。
- **UI**：platformKeyForUrl + PLATFORM_LABELS/COLORS 补 7d 缺失 5 平台（网易CC/百度/微博/京东/知乎）与本批 5 平台（嗨秀/乐嗨/来秀/LiveMe/淘宝）。
- **单测 +19**：LaixiuSpiderTest（签名确定性向量/解析/端到端）、TaobaoSpiderTest（**jsSignKnownVector**：taobao-sign.js 注释内正确值 05748e83…、画质选择、jsonp、端到端）、HaixiuSpiderTest（JS 烟囱/解码/解包/端到端）、LiveMeSpiderTest（JS 烟囱/解析/端到端）、PlatformRouterTest +6 路由判定。

## 踩坑

1. 生成 JsScripts.kt 的 python 脚本 docstring 里写了 `"""` 把自己提前终止（SyntaxError 定位到无关行）——生成器自食其果。
2. file_write 大参数截断一次（TaobaoSpider 初稿），改分段写。
3. 上游 execjs 对 JS 字符串返回原值，而桥约定统一 JSON.stringify——haixiu 的 _ajaxData1 需要 unwrapJson 解一层引号。

## 验收
- CI compile-check 全绿 + 单测不回退（325+）
- 真机：添加 tb.cn（需录 cookie 含 _m_h5_tk）/ haixiutv / imkktv / liveme 链接 → 徽标正确 → 录制落盘（并入走查清单，需海外代理平台：LiveMe）

---

# Phase 8 — 8b：小红书（第 1-2 批收口）

> 上游基准：spider.py:769 get_xhs_stream_url。原待做清单中的「网易音乐人」经核实为幽灵条目（上游 52 平台无此平台），本节以小红书收口第 1-2 批。

## 交付

- **XhsSpider**（纯 Kotlin）：ios UA + xy-common-params 头；xhslink.com 短链 GET 重定向取 finalUrl；user_id = /user/profile/ 路径段 ?: query host_id；房间页 `<script>window.__INITIAL_STATE__=` 正则 + undefined→null；liveStream.liveStatus=="success" 且标题非「回放」→ deeplink 解析 host_nickname/flvUrl（URL-decode）→ roomId = flvUrl 'live/' 段至首个 '.' → **固定 CDN 直链** `http://live-source-play.xhscdn.com/live/{roomId}.flv`（m3u8 同源替换）；未开播/回放/无 state → 个人主页 `<title>@xxx 的个人主页` 兜底主播名。
- **PlatformRouter**：isXhsUrl（xiaohongshu.com/xhslink.com）+ fetchXhs 分流（cookie 键 **xhs**，对齐 AuthStore）+ isSupported/isDirectStreamUrl 链更新。
- **UI**：platformKeyForUrl/PLATFORM_LABELS/COLORS 的小红书条目此前已备，无需改。
- **单测 +13**：XhsSpiderTest（参数解码/路径提取/INITIAL_STATE/固定直链/回放回落/短链重定向/host_id 兜底）+ PlatformRouterTest（isXhsUrl/isSupported/fetchXhs 开播与未开播分发）。

## 已知对齐点
- 上游 flvUrl 缺失或无 'live/' 段会抛 IndexError（trace_error 兜底）→ 安卓防御性返回未开播。
- 回放标题（含「回放」）即使 liveStatus==success 也视为未开播（上游同语义）。
- cookie 键用 AuthStore 既有键 `xhs`（非 MonitorCard 徽标键 `xiaohongshu`），UI 徽标映射不变。

## 验收
- CI compile-check 全绿 + 单测不回退（360+）
- 真机：添加 xhslink 分享短链或 xiaohongshu.com/user/profile/{id} → 徽标正确 → 录制落盘（并入走查清单）

---

# Phase 8 — 8c：TikTok（第 3 批续）

> 上游基准：spider.py:286 get_tiktok_stream_data + stream.py:82 get_tiktok_stream_url（SIGI_STATE HTML 解析，无 JS 签名）。
> commit c7014f3（2026-09-29）。

## 交付

- **TikTokSpider**（纯 Kotlin）：Chrome 141 桌面 UA + referer tiktok.com + cookie（用户 > 上游硬编码兜底）；GET 房间页 3 次重试（间隔 1s）——页面含「discontinued operating TikTok」→ 区域封锁即返回未开播、含 UNEXPECTED_EOF_WHILE_READING → 重试、否则提取 `<script id="SIGI_STATE">` JSON（失败 → 未开播，同上游 raise→trace_error 兜底）；user.status==2 开播 → streamData.pull_data.stream_data（JSON 字符串二次解析）→ data 各画质键 main.flv/hls + sdk_params（vbitrate/resolution/VCodec）→ URL 按 .flv/.m3u8 后缀拼 `?codec=` 或 `&codec=` → vbitrate≠0 且有 resolution 入列 → 码率降序+宽高降序 → 补齐 5 档 → 复用 DouyinQuality.resolveQualityIndex 取档 → m3u8?:flv HEAD 探测失败 ±1 档回退 → recordUrl = m3u8 ?: flv。
- **PlatformRouter**：isTiktokUrl（tiktok.com/）+ fetchTiktok 分流（cookie 键 **tiktok**，AuthStore 既有）+ isSupported/isDirectStreamUrl 链更新。
- **UI**：platformKeyForUrl/PLATFORM_LABELS/COLORS 的 tiktok 条目、RecordSource FLV 优先（douyin/tiktok）、ProxySettings 代理白名单均此前已备，零改动。
- **单测 +15**：TikTokSpiderTest（提取/URL 判定/画质排序/codec 两种拼接/零码率与缺分辨率过滤/补 5 档/未开播/默认 OD/HD 取档/探测回退/缺 stream_data/画质越界/端到端/cookie 兜底与覆盖/封锁/EOF 重试成功与放弃）+ PlatformRouterTest（isTiktokUrl/isSupported/fetch 开播与未开播）。

## 已知对齐点 / 差异
- 上游 get_quality_index 语义复用（QUALITY_MAPPING OD/BD→0, UHD→1, HD→2, SD→3, LD→4，数字越界抛 IndexError 兜未开播）。
- **差异**：上游 main.py:598 要求配置代理才发起 TikTok 请求（否则记错误日志不请求）；安卓端改为直接尝试——未配置代理时请求失败自然回落未开播，监控循环按代理白名单逐轮重试。
- 上游 http2=False/abroad=True 为 httpx 客户端参数，OkHttp 无对应概念，不移植。
- 录制层：RecordSource.selectSourceUrl 已对 tiktok 做 FLV 优先（codec=h265 回落 HLS），与上游 select_source_url 同语义。

## 验收
- CI compile-check 全绿 + 单测不回退（385+）
- 真机：添加 https://www.tiktok.com/@user/live → 徽标正确 → 需配置海外代理 → 录制落盘（并入走查清单，海外平台组）

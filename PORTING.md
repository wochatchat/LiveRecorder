# DouyinLiveRecorder → Android 原生移植 · 评估与进度文档

> 源仓库：https://github.com/ihmily/DouyinLiveRecorder （Python，2025-02 版，MIT）
> 目标：Kotlin + Jetpack Compose 原生 App，后台服务持续监控 + 录制直播流；C/NDK 仅用于必要组件
> 文档性质：**活文档**，随移植轮次更新状态（复选框 + 变更日志）
> 创建：2026-09-13 · 最近更新：2026-09-13

---

## 一、源码盘点（评估基础）

克隆至 `/tmp/DouyinLiveRecorder`（沙箱重置会丢，分析结论已沉淀于本文档）。

| 模块 | 规模 | 职责 | 移植难度 |
|---|---|---|---|
| `main.py` | 2154 行 | CLI 调度：监控 URL 列表、轮询循环、录制线程、ffmpeg 后处理、推送 | 中（改 Foreground Service） |
| `src/spider.py` | 3394 行 | **52 个平台**的房间信息/直播源 API 爬虫（逐平台独立函数） | 低-高（逐平台，可增量） |
| `src/stream.py` | 445 行 | 画质选择、JSON→直播流 URL 映射 | 低 |
| `src/ab_sign.py` | 454 行 | 纯 Python 算法：RC4 + SM3（国密哈希）→ 抖音 a_bogus 签名 | 低（可直接 Kotlin 重写） |
| `src/http_clients/` | 147 行 | httpx 同步/异步客户端，**HTTP/2** | 低（OkHttp 原生支持） |
| `src/room.py` | 150 行 | URL 类型分发、x-bogus.js 签名 | 低 |
| JS 签名文件 | 6 个 | `taobao-sign.js`、`liveme.js`、`haixiu.js`、`laixiu.js`、`migu.js`、`x-bogus.js` + 斗鱼 ub98484234 动态内联 JS | **中（需 JS 引擎）** |
| 录制路径 A | main.py | **纯 HTTP 流下载**（httpx stream → 写文件，FLV 平台） | 低（OkHttp 即可） |
| 录制路径 B | main.py | **subprocess ffmpeg**（ts/mkv/mp4/m4a、分段、转码、TS→MP4 remux） | **高（NDK）** |
| Web 界面 | index.html | 浏览器监控面板 | 可选（Android UI 替代） |
| `msg_push.py` | 295 行 | 微信/钉钉/TG/邮箱/bark/ntfy/pushplus 推送 | 低（Android 通知替代 + 保留 ntfy/bark） |

**重要事实**：
- 无 protobuf/grpc 依赖，所有平台 API 返回 JSON（部分 jsonp，需转换）。
- 抖音 a_bogus 签名 = 纯算法（RC4+SM3），不依赖 JS 引擎，直接 Kotlin 重写。
- 斗鱼签名特殊：从房间页 HTML 提取动态 JS（ub98484234）后 eval 执行 —— **必须有 JS 引擎**。
- 上游**无单测**，无类型标注严谨性可言 → 移植验证只能靠行为对照（同一 URL 两侧各跑一遍比对结果）。
- ffmpeg 使用面：分段录制（`-c copy -f segment`）、TS→MP4 remux、m4a 提取、h264 重编码（可选）——**全部是容器级操作，不需要解码重编码**（除可选 h264 转码）。

---

## 二、架构映射（Python → Android）

| Python 组件 | Android 替代 | 说明 |
|---|---|---|
| httpx（HTTP/2、proxy、stream） | **OkHttp 4** | 原生 HTTP/2、per-client Proxy、流式 body；verify=False 对应自定义 TrustManager（部分平台自签证书） |
| asyncio + threading 调度 | **Kotlin Coroutines + Foreground Service** | 监控循环 = Service 内 coroutine（`dataSync` 类型）；录制下载 = IO coroutine |
| PyExecJS + Node | **QuickJS（C，JNI 封装）** | 见第三节：唯一合理选型，上游 JS 文件原样复用 |
| subprocess ffmpeg | **自建 ffmpeg（C，NDK 编译）** | 见第三节 |
| pycryptodome（AES/RSA，looklive） | javax.crypto / java.security | 原生支持 |
| hashlib/md5 | java.security.MessageDigest | 原生支持 |
| loguru | Kermit / 纯 SLF4J 或自写 Logger | 落 app 私有目录日志文件 |
| configparser（config.ini） | **DataStore (Proto)** | 52 项配置对齐 |
| URL_config.ini（监控列表） | **DataStore / Room** | 支持增删改查 UI |
| print/log 输出 | Compose 界面实时日志 + 通知 | 用户可见 |
| 消息推送 | Android Notification（本机）+ 保留 ntfy/bark HTTP 推送 | 微信/钉钉/TG/邮箱砍掉或保留 |
| Web 面板 | 原生 Compose UI | 不移植 index.html |
| 文件保存 | app 私有目录 + SAF 用户自选 | 存储阈值检查用 StorageManager |
| 自定义脚本执行 | **不移植**（Android 无 shell 生态） | 预留 hook 接口 |

---

## 三、C/NDK 组件评估（"必要时加 C"的边界）

### 3.1 QuickJS —— **必须用 C，收益最大**
- 需求：斗鱼动态 JS（ub98484234，每房间从 HTML 提取后执行）、淘宝/咪咕/嗨秀/来秀/LiveMe 签名 JS、x-bogus.js。
- 为什么不重写：斗鱼 JS 是**运行时从页面提取的动态代码**，重写不可能；淘宝等 JS 上游经常更新，原样复用 `.js` 文件才能低成本跟上游同步。
- 方案：QuickJS（Bellard 纯 C，~10 文件，aarch64 无压力）+ 自写 ~200 行 JNI 封装（compile/call 两个接口即可，对齐 PyExecJS 的用法）。已有成熟先例（quickjs-android 等），可参考或直接 vendor。
- 体积代价：~200-400KB so。

### 3.2 ffmpeg —— **必须用 C，工作量最大**
- 官方 **ffmpeg-kit 已于 2025 年退役**（预编译二进制从仓库移除），不可依赖 → 自建。
- 用途全部是容器级操作（不需要解码）：
  1. 分段录制：`-c copy -f segment -segment_time 1800`
  2. TS→MP4 remux：`-c copy`
  3. m4a 提取
  4. （可选）h264 重编码 —— P3 之后再议
- 方案对比：
  - **A. ffmpeg 二进制 + ProcessBuilder**：最小 NDK 构建裁剪（禁用全部 encoder/decoder，只留 demuxer/muxer/protocol），打包成 `libffmpeg.so` 放 jniLibs，exec `nativeLibraryDir/libffmpeg.so`。无 root 可用，对齐上游 subprocess 用法，**推荐**。
  - B. libavformat 链接 so + JNI 调用：更可控，但需手写 remux/segment 逻辑，工作量大。
- 构建脚本放 CI（GitHub Actions 装 NDK 编译），产物缓存。
- 备选降级：仅支持 FLV 直接下载（路径 A）+ MediaMuxer 做 TS/MP4 处理的有限替代，零 NDK 依赖 —— 但 TS→MP4 remux MediaMuxer 做不了，功能会缩水。

### 3.3 SM3/RC4 —— **不需要 C**
- ab_sign.py 是纯算法（RC4 + SM3），输入只有 URL query + UA 字符串，量级极小（毫秒级）。
- 直接 Kotlin 重写（~300 行），可用已知向量（国标 GB/T 32905-2016 测试向量）验证。C 版留作性能问题出现后的选项。

### 3.4 结论
- C 必要组件 = **QuickJS + ffmpeg 二进制**，其余全部 Kotlin。JNI 手写面很小（QuickJS 封装一个）。

---

## 四、Android 特有风险与难点

| 风险 | 影响 | 对策 |
|---|---|---|
| 后台存活：Doze/厂商杀后台 | 300s 轮询 + 长录制中断 | Foreground Service（`dataSync`）+ 前台通知 + Partial WakeLock；文档注明各厂商白名单设置 |
| Android 14+ FGS 限制 | 启动崩溃 | `FOREGROUND_SERVICE_DATA_SYNC` 权限声明；录制中通知常驻 |
| 部分平台硬编码 Cookie（抖音 ttwid 等） | 抖音 web API 失效率高 | 与上游同步策略：Cookie 常量表做成可远程更新/可编辑；抖音优先 app API 路径 |
| 上游 API 频繁变动（反爬升级） | 长尾平台失效 | 逐平台独立模块 + 平台健康检查（失败标灰），不阻塞其他平台 |
| 需要 JS 的平台依赖动态代码 | 签名失败 | QuickJS + 上游 JS 文件随版本同步 |
| verify=False（自签证书平台） | TLS 拒绝 | per-platform 自定义 TrustManager（仅对指定域名关闭校验，不全局） |
| 代理支持 | 海外平台（TikTok/Twitch 等）直连不可达 | OkHttp per-client Proxy + 代理设置 UI；支持 socks5 |
| 手机存储 | 长录制占空间 | 录制前阈值检查（StorageManager），低于阈值暂停并通知 |
| 电池 | 长时间录制耗电 | 录制统计面板；监控轮询在录制中自动暂停对应条目 |
| 竖屏 UI 与 CLI 交互差异 | 配置项过多难摆放 | 配置分"常用/高级"两级；52 项配置渐进补齐 |

---

## 五、平台移植矩阵（52 平台，分四批）

> 每平台 = spider 函数移植 + stream 画质映射 + 真机行为对照验证。✅=完成 🚧=进行中 ⬜=未开始 ❌=评估后放弃

### 第一批（P0 · 主流国内，首批上线必须）
| 平台 | 状态 | 依赖 JS/特殊点 |
|---|---|---|
| 抖音直播 | ✅（P1 完成端到端对拍，监控/分段见 Phase 2/3） | a_bogus（Kotlin 重写）+ web/app 双路径 + 硬编码 Cookie 可更新 |
| TikTok 直播 | ⬜ | 需代理；按 vbitrate/resolution 排序选源 |
| 快手直播 | ⬜ | did 设备伪装；web/api2 双路径 |
| 虎牙直播 | ⬜ | app API URL 加密参数（ wildlife 解码） |
| 斗鱼直播 | ⬜ | **动态 JS（ub98484234）→ QuickJS 首个用户** |
| B站直播 | ⬜ | cookie 可选；qn 画质映射；web/h5 双路径 |

### 第二批（P1 · 国内次主流）
| 平台 | 状态 | 备注 |
|---|---|---|
| YY 直播 | ⬜ | |
| 小红书直播 | ⬜ | 需 cookie |
| Bigo 直播 | ⬜ | |
| 网易CC直播 | ⬜ | |
| 百度直播 | ⬜ | |
| 知乎直播 | ⬜ | |
| 微博直播 | ⬜ | |
| 京东直播 | ⬜ | |
| 网易（音乐人/look） | ⬜ | |

### 第三批（P2 · JS 签名类，依赖 QuickJS）
| 平台 | 状态 | JS 文件 |
|---|---|---|
| 淘宝直播 | ⬜ | taobao-sign.js |
| 咪咕直播 | ⬜ | migu.js（node 子进程→QuickJS） |
| 嗨秀/来秀直播 | ⬜ | haixiu.js / laixiu.js + crypto-js.min.js |
| LiveMe | ⬜ | liveme.js + crypto-js.min.js |
| Look直播 | ⬜ | AES/RSA（javax.crypto，无需 JS） |
| Acfun | ⬜ | sign 参数（Kotlin） |
| 酷狗直播 | ⬜ | |
| 猫耳FM | ⬜ | |

### 第四批（P3 · 长尾 + 海外 + 需登录）
> TwitCasting/FlexTV/SOOP/PopkonTV（需登录，做 cookie/账密输入 UI）；TwitchTV/CHZZK/ShowRoom/Picarto/faceit（需代理）；千度热播/畅聊/映客/花椒/音播/花猫/连接/VV星球/WinkTV/PandaTV/Blued/流星/六间房/浪Live/漂漂/shopee/17live/花播 等。
> **"自定义录制直播"**（直接填流地址）P0 就支持，成本近零。

---

## 六、分阶段计划（多轮推进，每轮可独立交付）

### Phase 0 · 项目脚手架 ⬜
- [ ] 新建 GitHub 仓库（wochatchat org，命名待定，如 `LiveRecorder`）
- [ ] CI 走 A 型归档 workflow（EdgeTunnelManager/TidyHome 同款，keystore 随仓库）
- [ ] Compose 骨架：主页（监控列表）+ 添加直播 URL + 设置页 + 日志页
- [ ] DataStore 配置层 + URL 监控列表存储
- 交付：可安装空壳，CI 绿

### Phase 1 · 抖音端到端（核心链路验证） ⬜
- [ ] OkHttp 客户端层（HTTP/2、UA 伪装、per-platform header）
- [ ] ab_sign Kotlin 重写（SM3+RC4，国标测试向量验证）
- [ ] 抖音 web + app 双路径爬虫 → 直播源解析 → 画质选择
- [x] **路径 A 直播流下载**（OkHttp 流式写文件，FLV 直下；m3u8/HLS 需 ffmpeg，延至 Phase 3）
- [ ] 行为对照验证：同一抖音房间 URL，Python 原版 vs App 解析结果一致
- 交付：App 可录制抖音直播（原始流落盘）

### Phase 2 · 后台监控 + 服务化 ⬜
- [ ] Foreground Service（dataSync）+ 监控轮询循环（对齐 config.ini 的循环时间/并发线程数语义）
- [ ] 开播/关播 Android 通知 + 可选 ntfy/bark HTTP 推送
- [ ] 监控列表 UI（增删改查、单条启停、状态徽标）
- [ ] 录制中断恢复（断流重连）、存储阈值检查
- 交付：无人值守后台录制抖音/快手

### Phase 3 · QuickJS + ffmpeg（C 组件落地） ⬜
- [ ] QuickJS vendor + JNI 封装（compile/call，对齐 PyExecJS 接口）
- [ ] 斗鱼移植（动态 JS 签名，QuickJS 首个真实用户）
- [ ] ffmpeg 最小 NDK 构建（CI 产出二进制，只留 demuxer/muxer/protocol）
- [ ] 分段录制 + TS→MP4 remux + m4a 提取（ProcessBuilder 调 libffmpeg.so）
- 交付：斗鱼可录；ts/mp4 分段与转封装齐备

### Phase 4 · 平台批量移植（P1→P2→P3） ⬜
- [ ] 按第五节矩阵逐批移植，每平台过行为对照
- [ ] 代理设置（per-platform socks5/http）
- [ ] 需登录平台的 cookie/账密输入 UI
- [ ] 平台健康徽标（失效标灰不阻塞）
- 交付：覆盖 30+ 平台

### Phase 5 · 配置对齐 + 打磨 ⬜
- [ ] config.ini 52 项配置全对齐（常用/高级两级 UI）
- [ ] 文件命名规则（作者/时间/标题区分、表情清理）
- [ ] 录制统计面板（时长/大小/码率）、日志导出
- [ ] 上游版本跟随机制（定期 diff spider.py 变更）
- 交付：功能对齐原版核心能力

---

## 七、验证策略（无上游单测，靠行为对照）

1. **行为对照**：同一房间 URL，沙箱跑 Python 原版（`/tmp/DouyinLiveRecorder`，可 `pip install` 装依赖）→ 与 App 解析结果逐字段比对（anchor_name/is_live/m3u8_url/flv_url/画质）。
2. **录制对照**：同一直播流两侧各录 60s，比对文件可播放性与体积量级。
3. **签名向量**：SM3 用国标测试向量；RC4/ab_sign 用原版 Python 输出固定输入对比。
4. **QuickJS 对照**：斗鱼签名结果与原版 execjs 输出比对。
5. **浸泡**：多房间并发监控 24h（真机），核对无泄漏/无中断。

---

## 八、工程约定（沿用 wochatchat 规范）

- 构建/打包一律 GitHub Actions CI（A 型归档，APK 提交回仓库），本地不打包。
- 改代码：拓展式优先，保留可回滚路径；每轮交付附测试结论。
- CI bot 归档 commit 推回 main，push 前先 `git pull --rebase`。

---

## 九、变更日志

| 日期 | 轮次 | 内容 |
|---|---|---|
| 2026-09-13 | R0 | 完成源码盘点与可行性评估；架构映射、C 组件边界（QuickJS+ffmpeg 必要，SM3 不必要）、平台矩阵、六阶段计划定稿 |
| 2026-09-13 | R1 | **Phase 0 完成**：仓库 wochatchat/LiveRecorder；脚手架（Compose 主页+监控列表+添加对话框+DataStore）；4 workflow CI（build/build-test/compile-check/cleanup）；build.sh 版本自增；keystore 生成（keypass=storepass 教训）；v0.1.1 签名 APK 归档 + Release 产物齐；两次 CI 失败修复（缺 viewmodel-compose 依赖 / PKCS12 keypass） |
| 2026-09-21 | R8 | **Phase 1-1g：路径 A 流下载 + 录制 UI 最小版**。新增 `recorder/` 模块：RecordSource（select_source_url / get_record_headers / clean_name 移植，Kotlin JVM 输出与 Python 上游 11 组用例逐行一致）、StreamDownloader（direct_download_stream → OkHttp 流式 16KB 分块写盘，取消保留半截文件）、RecordController（url→状态机 StateFlow，文件命名 {downloads}/抖音直播/{主播}/{主播}_{ts}.flv）；RecorderApp 进程级单例；监控列表加录制/停止按钮+状态行。与上游差异：无监控轮询（Phase 2）、HLS 需 ffmpeg（Phase 3，h265-only 房间报不支持）。MockWebServer 5 测 + RecordSource 14 测 |
| 2026-09-21 | R9 | **Phase 1-1h 完成，Phase 1 全部完成 ✅**：开播房间沙箱端到端实时对拍通过（Python 原版 vs kotlinc 编译 Kotlin 真源码 JVM 全链路：房间数据/五档画质 URL/选档降级逐项一致，douyincdn 裸 HEAD 405 双方一致降档）。重要澄清：maven org.json keys() HashMap 乱序仅 JVM 测试伪影，真机 Android org.json（LinkedHashMap）保文档序与上游一致，不改代码。build-test.yml 出带签名测试包，真机 60s 录制验证交用户执行 |

# 咪咕（miguvideo.com）WASM 接入技术评估 — Phase 5-5.3 / Batch H

> 日期：2026-10-03 | 上游：ihmily/DouyinLiveRecorder main（spider.py:3203 + src/javascript/migu.js）
> 结论先行：**文档原方案「QuickJS 加载 WASM 字节码」技术上不成立**（Bellard QuickJS 无 WebAssembly 支持），
> 推荐改走 **方案 A：WebView 沙箱执行上游原版 migu.js**，工时 ~1 天。

---

## 一、上游链路分析

### 1.1 get_migu_stream_url（spider.py:3203）

```
headers: origin/referer/UA + appCode=miguvideo_default_www + appId=miguvideo + channel=H5 (+可选 Cookie)
web_id = URL 去参数后最后一段
① GET https://vms-sc.miguvideo.com/vms-match/v6/staticcache/basic/basic-data/{web_id}/miguvideo
   → body.title=主播名, title+detailPageTitle=直播标题, body.pId=房间号；无 pId → 未开播
② GET https://webapi.miguvideo.com/gateway/playurl/v3/play/playurl
   ?contId={pId}&rateType=3&clientId={uuid}&timestamp={ms}&flvEnable=true&xh265=false&chip=mgwww&channelId=
   → body.content.currentLive != '1' → 未开播
   → body.urlInfo.url = source_url
③ node 子进程跑 migu.js：getDdCalcu(source_url) → ddCalcu 值
④ real_url = source_url + &ddCalcu={值}&sv=10010
   含 .m3u8 → 跟随重定向取真实 m3u8；否则 FLV 直下
```

### 1.2 migu.js getDdCalcu（4.5KB，仅此一个函数）

```
① GET https://app-sc.miguvideo.com/common/v1/settings/H5_DetailPage → paramValue.playerVersion
② GET https://www.miguvideo.com/mgs/player/prd/{playerVersion}/dist/mgprtcl.wasm   ← 运行时动态下载，随版本变
③ WebAssembly.instantiate(wasm, { a: { a, b, c } })   ← 3 个 import 垫片（a=内存求和，b/c=no-op）
④ malloc + 写 UTF-8 字符串（userid/timestamp/ProgramID/Channel_ID/puData + 固定 key 'PBTxuWiTEbUPPFcpyxs0ww=='）
⑤ 调用序列：CallInterface6(建上下文) → 1(ProgramID) → 10(timestamp) → 9(userid)
   → 3(0,0) → 11(0,0) → 8(puData) → 2(Channel_ID) → 14(key,out,128) → 7(w) → 4(out,128)
⑥ 读 UTF-8 结果返回
```

依赖的浏览器 API：fetch、WebAssembly.instantiate、TextEncoder、URL —— **QuickJS 全都没有**。

---

## 二、可行性验证（2026-10-03 实测）

| 项 | 结果 |
|---|---|
| settings API playerVersion | ✅ 在线，返回 `v_20260923120131_14e8817a` |
| mgprtcl.wasm 下载 | ✅ 40865 字节，`\0asm` 魔数合法 |
| 脚本体积 | ✅ 4.5KB，单函数，无 node 专属 API（除 argv 壳） |

---

## 三、方案对比

| 方案 | 说明 | 工时 | 风险 | 维护 |
|---|---|---|---|---|
| **A. WebView 沙箱（推荐）** | 隐藏 WebView + evaluateJavascript 跑上游原版 migu.js，suspendCancellableCoroutine 包装成 suspend | ~1 天 | 低（WebView 全 ROM 内置，fetch/WASM/TextEncoder/URL 原生齐备） | 零（playerVersion/wasm 升级自动跟随） |
| B. wasm3 + JNI | vendor wasm3（MIT）进 cpp/，Kotlin 重写胶水（内存读写+调用序列+import 垫片） | 2-4 天 | 中（JNI marshalling、CMake/NDK 链变更） | 低 |
| C. 反编译 wasm 纯 Kotlin 重写 | 算法黑盒且随版本变 | 5 天+ | 高 | 差（版本一升级即失效） |
| D. QuickJS 垫 WebAssembly 全局 | 在 quickjs-jni.c 实现 WASM 对象 + 垫 TextEncoder/URL/fetch | 4-6 天 | 高 | 差 |

**不采用文档原表述的原因**：规划文档 5.3 写「QuickJS 加载 WASM 字节码 + JNI」——QuickJS 引擎本身不支持 WebAssembly
（既不能 instantiate 也不能解释 wasm 字节码）。「+JNI」实际可行的形态只有方案 B（wasm3），但相比方案 A 收益不抵风险：
胶水逻辑本身极薄，方案 A 直接复用上游脚本反而更稳。

### 方案 A 已知限制（可接受）
- WebView 网络请求不走 OkHttp 代理：签名计算与 IP 无关（待实测确认），playurl API 仍走 OkHttp 代理，影响极小
- 首次调用 ~1-3s（两次网络请求 + wasm 编译）：仅录制启动时调用，可后续按 playerVersion 缓存 wasm 编译产物优化
- 签名桥接需主线程创建 WebView：服务进程可用，协程切主线程一次性初始化

---

## 四、实施清单（Batch H 咪咕）

1. `sign/MiguDdCalcuSign.kt`：WebView 桥（单例懒加载、@MainThread 创建、suspend 调用、超时 15s、错误抛 IllegalStateException）
2. `platform/migu/MiguSpider.kt`：`fetchMiguStreamInfo`（①②③④ 全链路，m3u8 重定向跟随 / FLV 直下，映射 DouyinStreamInfo）
3. `PlatformRouter`：`isMiguUrl` + `fetchMigu` 路由 + cookies["migu"]（AuthStore 已有 migu 条目）
4. UI 徽标：`platformKeyForUrl` + `PLATFORM_LABELS["migu" to "咪咕"]` + `PLATFORM_COLORS`
5. 单测：isSupported / URL 解析（fixture，不依赖网络）；ddCalcu 桥接真机验证
6. forceHttps 例外：确认 migu 在 RecordSource/RecordController 例外列表（上游 shopee/migu 例外已有记载）

## 五、排期

评估 ✅ → 实施 + CI 全绿 ~1 天 → 真机验证（添加 miguvideo.com 监控 → 录制 → 播放）后收口 46→47/51。

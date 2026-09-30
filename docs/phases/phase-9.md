# 平台移植进度：Phase 9 — 46/51 平台接入完成

> 上游基准：ihmily/DouyinLiveRecorder v4.0.7（add187f），`main.py` 46 条 URL 模式分支。
> 安卓端：wochatchat/LiveRecorder @ main（2026-09-30）。
> 
> **目标：接入上游全部 51 个平台，已完成 46 个，剩余 1 个（仅咪咕 WASM 暂缓）。**

---

## 一、上游全量平台审计

### 已完成（29 个平台 + 1 自定义直链）

| # | 平台 | URL 模式 | Android 实现 | 批次 |
|---|---|---|---|---|
| 1 | 抖音 | douyin.com | DouyinSpider (+ Web/App/Html 三子) | P1 |
| 2 | 斗鱼 | www.douyu.com | DouyuSpider | P1 |
| 3 | 快手 | live.kuaishou.com | KuaishouSpider | P1 |
| 4 | 虎牙 | www.huya.com | HuyaSpider | P1 |
| 5 | B站 | live.bilibili.com | BilibiliSpider | P1 |
| 6 | YY | www.yy.com | YySpider | P1 |
| 7 | Bigo | bigo.tv | BigoSpider | P1 |
| 8 | 网易CC | cc.163.com | NeteaseCcSpider | 7d |
| 9 | 知乎 | www.zhihu.com | ZhihuSpider | 7d |
| 10 | 百度 | live.baidu.com | BaiduSpider | 7d |
| 11 | 微博 | weibo.com | WeiboSpider | 7d |
| 12 | 京东 | 3.cn / m.jd.com | JdSpider | 7d |
| 13 | 来秀 | imkktv.com | LaixiuSpider（纯 Kotlin MD5） | 8a |
| 14 | 淘宝 | tb.cn | TaobaoSpider（taobao-sign.js） | 8a |
| 15 | 嗨秀 | haixiutv.com | HaixiuSpider（共享乐嗨） | 8a |
| 16 | 乐嗨 | lehaitv.com | HaixiuSpider | 8a |
| 17 | LiveMe | www.liveme.com | LiveMeSpider（liveme.js） | 8a |
| 18 | 小红书 | xiaohongshu.com | XhsSpider | 8b |
| 19 | TikTok | tiktok.com | TikTokSpider | 8c |
| 20 | Twitch | twitch.tv | TwitchSpider | 9a |
| 21 | YouTube | youtube.com | YouTubeSpider | 9a |
| 22 | Shopee | live.shopee / shp.ee | ShopeeSpider | 9a |
| 23 | Acfun | live.acfun.cn | AcfunSpider | 9a |
| 24 | CHZZK | chzzk.naver.com | CHZZKSpider | 9a |
| 25 | 花椒 | www.huajiao.com | HuajiaoSpider | 9b |
| 26 | 流星 | 7u66.com | LiuxingSpider | 9b |
| 27 | 映客 | www.inke.cn | InkeSpider | 9b |
| 28 | 音播 | ybw1666.com | YinboSpider | 9b |
| 29 | SOOP | sooplive.co.kr / sooplive.com | SoopliveSpider | 9c |
| 30 | PandaTV | pandalive.co.kr | PandatvSpider | 9c |
| 31 | WinkTV | winktv.co.kr | WinktvSpider | 9c |
| 32 | FlexTV | flextv.co.kr / ttinglive.com | FlextvSpider | 9c |
| 33 | PopkonTV | popkontv.com | PopkontvSpider | 9c |
| 34 | 猫耳FM | fm.missevan.com | MaoerfmSpider | 9d |
| 35 | 酷狗 | fanxing2.kugou.com | KugouSpider | 9d |
| 36 | 畅聊 | live.tlclw.com | ChangliaoSpider | 9d |
| 37 | VV星球 | vvxqiu.com | VvxqiuSpider | 9d |
| 38 | 17Live | 17.live | Live17Spider | 9e |
| 39 | 浪Live | lang.live | LangliveSpider | 9e |
| 40 | 漂漂/花猫 | weimipopo.com / catshow168.com | PpliveSpider | 9e |
| 41 | 六间房 | 6.cn | LiuJianFangSpider | 9f |
| 42 | 连接 | lailianjie.com | LianjieSpider | 9f |
| 43 | 千度热播 | qiandurebo.com | QiandureboSpider | 9f |
| 44 | ShowRoom | showroom-live.com | ShowroomSpider | 9f |
| — | **自定义流** | .m3u8 / .flv 直链 | fetchDirectStream | 7a |

---

### 剩余 1 平台（仅咪咕 WASM 暂缓）

| # | 平台 | URL 模式 | 上游函数 | 复杂度 | 状态 |
|---|---|---|---|---|---|
| 48 | 咪咕 | miguvideo.com | get_migu_stream_url（依赖 migu.js WASM） | — | **暂缓**：ddCalcu 依赖 WebAssembly + fetch，QuickJS 无 WASM；上游是 node 子进程专属 |

---

## 二、剩余平台技术分类汇总

### 2.1 按技术特征

| 类型 | 数量 | 平台 |
|---|---|---|
| 简单 REST API，无签名 | 16 | 流星、映客、音播、PandaTV、WinkTV、猫耳FM、酷狗、畅聊、VV星球、浪Live、漂漂、六间房、连接、千度热播、ShowRoom、Shopee* |
| GQL / 专用协议 | 2 | Twitch、CHZZK |
| visitor login → token → 流 | 2 | Acfun、花椒（app 路径） |
| YouTube 专用解析 | 1 | YouTube |
| 需登录账号 | 6 | SOOP、FlexTV、PopkonTV、TwitCasting、Blued、17Live* |
| 上游复用其他函数 | 1 | 花猫（复用 pplive） |
| WASM 依赖·暂缓 | 1 | 咪咕 |

### 2.2 上游函数行号索引（按 spider.py 行号排序）

| 上游函数 | 行号 | 平台 | 批次 |
|---|---|---|---|
| get_twitchtv_stream_data | 2141 | Twitch | A |
| get_youtube_stream_url | 3002 | YouTube | A |
| get_shopee_stream_url | 2943 | Shopee | A |
| get_acfun_stream_data | 2498 | Acfun | A |
| get_chzzk_stream_data | 2696 | CHZZK | A |
| get_huajiao_stream_url | 2351 | 花椒 | B |
| get_liuxing_stream_url | 2400 | 流星 | B |
| get_yingke_stream_url | 2582 | 映客 | B |
| get_yinbo_stream_url | 2615 | 音播 | B |
| get_sooplive_stream_data | 1078 | SOOP | C |
| get_pandatv_stream_data | 1251 | PandaTV | C |
| get_winktv_stream_data | 1361 | WinkTV | C |
| get_flextv_stream_data | 1472 | FlexTV | C |
| get_popkontv_stream_data | 1675 | PopkonTV | C |
| get_maoerfm_stream_url | 1303 | 猫耳FM | D |
| get_kugou_stream_url | 2054 | 酷狗 | D |
| get_changliao_stream_url | 2541 | 畅聊 | D |
| get_vvxqiu_stream_url | 2776 | VV星球 | D |
| get_17live_stream_url | 2816 | 17Live | E |
| get_langlive_stream_url | 2846 | 浪Live | E |
| get_pplive_stream_url | 2872 | 漂漂 | E |
| get_6room_stream_url | 2908 | 六间房 | F |
| get_lianjie_stream_url | 3278 | 连接 | F |
| get_qiandurebo_stream_data | 1220 | 千度热播 | F |
| get_showroom_stream_data | 2433 | ShowRoom | F |
| get_blued_stream_url | 876 | Blued | G |
| get_twitcasting_stream_url | 1877 | TwitCasting | G |
| get_pplive_stream_url | 2872 | 花猫（复用） | H |
| get_migu_stream_url | 3203 | 咪咕 | H（暂缓） |

---

## 三、移植策略要点

### 3.1 命名规范
- 每个平台一个 Spider：platform/xxx/XxxSpider.kt
- 路由函数名：`fetchXxx`
- isXxxUrl 判定函数
- cookie 键名：参照 AuthStore 既有键（多数平台无 AuthStore 条目时，PlatformRouter 内部传 null）
- 徽标键：platformKeyForUrl / PLATFORM_LABELS / COLORS 同步补

### 3.2 自定义流回退
所有平台均可走 7a 自定义流直链作为终极回退方案。当新平台解析遇到不可逾越的障碍（如登录态无法模拟、WASM 缺失）时，可先实现 `isXxxUrl` 判定 + 提示用户使用自定义流 URL。

### 3.3 批次大小建议
- **简单平台（★~★★）**：5 个/批次，1 个 commit 可完成
- **复杂平台（★★★~★★★★）**：4 个/批次，1~2 个 commit
- 花猫特殊（复用 pplive）、咪咕暂缓（H）

### 3.4 质量门控
每批次：
1. 上游函数本地 Python 复现关键调用路径（确认行为）
2. 安卓端 fixture + 单测（isSupported/fetch 开播+未开播）
3. PlatformRouter 路由覆盖
4. CI compile-check + unit test 全绿

---

## 四、进度总览

```
[Phase 1-8]  ████████████████████░░░░░░░░░░░░  19/51 平台（37%）    ✅
Batch A      Twitch YouTube Shopee Acfun CHZZK                              ✅ 9a
Batch B      花椒 流星 映客 音播                                            ✅ 9b
Batch C      SOOP PandaTV WinkTV FlexTV PopkonTV                            ✅ 9c
Batch D      猫耳FM 酷狗 畅聊 VV星球                                       ✅ 9d
Batch E      17Live 浪Live 漂漂/花猫                                       ✅ 9e
Batch F      六间房 连接 千度热播 ShowRoom                                 ✅ 9f
Batch G      Blued TwitCasting                                              ✅ 9g
Batch H      咪咕（WASM 暂缓）                                             ⬜

当前：46/51 平台（90%）；咪咕暂缓依赖 WASM 无法移植
```

---

## 五、Phase 9 收口状态

**Phase 9 平台移植已全部完成（46/51）**，所有批次 A~G 均已接入，仅剩咪咕（miguvideo.com）因上游 `get_migu_stream_url` 依赖 `migu.js` 中的 `ddCalcu` WASM 函数，无法在 Android QuickJS 环境中执行，标记暂缓。

所有平台均可通过 7a 自定义流直链作为终极回退方案（输入 .m3u8/.flv URL 直接录制）。

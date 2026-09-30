# 平台移植进度：Phase 9 — 剩余 18 平台接入

> 上游基准：ihmily/DouyinLiveRecorder v4.0.7（add187f），`main.py` 46 条 URL 模式分支。
> 安卓端：wochatchat/LiveRecorder @ main（2026-09-30）。
> 
> **目标：接入上游全部 51 个平台，已完成 36 个，剩余 15 个。**

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
| — | **自定义流** | .m3u8 / .flv 直链 | fetchDirectStream | 7a |

---

### 剩余 15 平台（剩余 Batch F~H 3 批）

#### Batch A — 无登录·简单 API（5 个）✅ 已完成（9a）

| # | 平台 | URL 模式 | 上游函数 | 复杂度 | 备注 |
|---|---|---|---|---|---|
| 20 | Twitch | twitch.tv | get_twitchtv_stream_data | ★★ | GQL + usher m3u8，Client-ID 固定 |
| 21 | YouTube | youtube.com | get_youtube_stream_url | ★★ | ytInitialPlayerResponse 正则，需 cookie |
| 22 | Shopee | live.shopee / shp.ee | get_shopee_stream_url | ★★★ | mtop API + 签名，cookie 必须含 _m_h5_tk |
| 23 | Acfun | live.acfun.cn | get_acfun_stream_data | ★★★ | visitor login → userId → 快手流协议 |
| 24 | CHZZK | chzzk.naver.com | get_chzzk_stream_data | ★★ | 韩国 Naver 平台，REST API，m3u8 后处理 |

#### Batch B — 无登录·中等复杂度（4 个）✅ 已完成（9b）

| # | 平台 | URL 模式 | 上游函数 | 复杂度 | 备注 |
|---|---|---|---|---|---|
| 25 | 花椒 | www.huajiao.com | get_huajiao_stream_url + stream_url_app | ★★★ | 双路径：app sn+user_info → stream_url_app，web 兜底 |
| 26 | 流星 | 7u66.com | get_liuxing_stream_url | ★★ | 简单 JSON API |
| 27 | 映客 | www.inke.cn | get_yingke_stream_url | ★★ | busi.inke.cn API |
| 28 | 音播 | ybw1666.com | get_yinbo_stream_url | ★★ | wap.ybw1666.com API |

#### Batch C — 韩国平台·需登录（5 个）✅ 已完成（9c）

| # | 平台 | URL 模式 | 上游函数 | 复杂度 | 备注 |
|---|---|---|---|---|---|
| 29 | SOOP | sooplive.co.kr | get_sooplive_stream_data + login_sooplive | ★★★★ | 登录 → cookie → cdn_url + tk 双 API |
| 30 | PandaTV | pandalive.co.kr | get_pandatv_stream_data | ★★★ | 简单 JSON API |
| 31 | WinkTV | winktv.co.kr | get_winktv_stream_data + bj_info | ★★★ | 韩国平台，双 API |
| 32 | FlexTV | flextv.co.kr | get_flextv_stream_data + login_flextv | ★★★ | 登录 + stream_url + stream_data 三函数 |
| 33 | PopkonTV | popkontv.com | get_popkontv_stream_data + login_popkontv | ★★★★ | 登录流程复杂 |

#### Batch D — 小众中文平台·无登录（4 个）✅ 已完成（9d）

| # | 平台 | URL 模式 | 上游函数 | 复杂度 | 备注 |
|---|---|---|---|---|---|
| 34 | 猫耳FM | fm.missevan.com | get_maoerfm_stream_url | ★★ | 二次请求（missevan.com/flive/ + API） |
| 35 | 酷狗 | kugou.com | get_kugou_stream_url | ★★ | 简单 JSON API |
| 36 | 畅聊 | live.tlclw.com | get_changliao_stream_url | ★★ | 简单 API |
| 37 | VV星球 | vvxqiu.com | get_vvxqiu_stream_url | ★★ | 简单 API |

#### Batch E — 小众中文平台·无登录（续，3 个）✅ 已完成（9e）

| # | 平台 | URL 模式 | 上游函数 | 复杂度 | 备注 |
|---|---|---|---|---|---|
| 38 | 17Live | 17.live | get_17live_stream_url | ★★★ | REST API，cookie 可能必须 |
| 39 | 浪Live | www.lang.live | get_langlive_stream_url | ★★ | 简单 API |
| 40 | 漂漂 | m.pp.weimipopo.com | get_pplive_stream_url | ★★ | 简单 API（花猫复用同函数） |

#### Batch F — 小众中文平台·无登录（续，4 个）

| # | 平台 | URL 模式 | 上游函数 | 复杂度 | 备注 |
|---|---|---|---|---|---|
| 41 | 六间房 | .6.cn | get_6room_stream_url | ★★ | 简单 API |
| 42 | 连接 | show.lailianjie.com | get_lianjie_stream_url | ★★ | 简单 API |
| 43 | 千度热播 | qiandurebo.com | get_qiandurebo_stream_data | ★★ | 简单 API |
| 44 | ShowRoom | showroom-live.com | get_showroom_stream_data | ★★ | 简单 API |

#### Batch G — 需账号·中文平台（2 个）

| # | 平台 | URL 模式 | 上游函数 | 复杂度 | 备注 |
|---|---|---|---|---|---|
| 45 | Blued | app.blued.cn | get_blued_stream_url | ★★★ | cookie 必须 |
| 46 | TwitCasting | twitcasting.tv | get_twitcasting_stream_url + login_twitcasting | ★★★★ | 登录 → cookie → stream API，复杂 |

#### Batch H — 边缘/特殊（2 个）

| # | 平台 | URL 模式 | 上游函数 | 复杂度 | 决策 |
|---|---|---|---|---|---|
| 47 | 花猫 | h.catshow168.com | get_pplive_stream_url（复用！main.py 调用） | ★ | ✅ 9e 已随漂漂接入（PpliveSpider catshow 分支） |
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
[Phase 1-8]  ████████████████████░░░░░░░░░░░  19/51 平台（37%）  ✅
Batch A      Twitch YouTube Shopee Acfun CHZZK                              ✅ 9a
Batch B      花椒 流星 映客 音播                                            ✅ 9b
Batch C      SOOP PandaTV WinkTV FlexTV PopkonTV                            ✅ 9c
Batch D      猫耳FM 酷狗 畅聊 VV星球                                       ✅ 9d
Batch E      17Live 浪Live 漂漂                                            ✅ 9e
Batch F      六间房 连接 千度热播 ShowRoom                                 ⬜
Batch G      Blued TwitCasting                                              ⬜
Batch H      花猫（复用） 咪咕（暂缓）                                     ⬜

当前：33/51 平台（65%）
```

---

## 五、当前任务

**Batch A（5 个）：Twitch / YouTube / Shopee / Acfun / CHZZK**

入口文件：`app/src/main/java/com/wochatchat/liverecorder/platform/`

上游参考：`/tmp/DouyinLiveRecorder/src/spider.py` 对应函数行号见上表。

### Batch A 关键解析

#### Twitch（get_twitchtv_stream_data，spider.py:2141）
- 流程：GQL 获取 token+signature → usher.ttvnw.net m3u8 → get_play_url_list
- 关键：Client-ID `kimne78kx3ncx6brgo4mv6wki5h1ko` 固定；device-id 随机 16 位
- m3u8 URL 需 get_play_url_list 解析后重拼接
- 无 cookie → 可播但可能区域限制

#### YouTube（get_youtube_stream_url，spider.py:3002）
- 流程：正则提取 `ytInitialPlayerResponse` → videoDetails.isLive → streamingData.hlsManifestUrl
- 关键：`isLive` 需 cookie 才能获取（无 cookie 返回请登录提示）
- m3u8 → get_play_url_list 解析

#### Shopee（get_shopee_stream_url，spider.py:2943）
- 流程：cookie 校验 → mtop.mediaplatform.live.livedetail API → m3u8
- 关键：cookie 必须含 `_m_h5_tk`，否则直接返回未开播
- m3u8 → get_play_url_list

#### Acfun（get_acfun_stream_data，spider.py:2498）
- 流程：visitor login → userId → 快手协议（get_acfun_sign_params → get_play_url_list）
- 关键：无 cookie 时自动 visitor login 拿 userId；userId → userInfo API → liveId → 启动播放
- play_url_list 来自 kuaishou 协议（同快手），bitrate 降序

#### CHZZK（get_chzzk_stream_data，spider.py:2696）
- 流程：chzzk.naver.com/api REST → status=='OPEN' → livePlaybackJson → m3u8
- 关键：韩国 Naver 平台；m3u8 需 baseURL 重拼接（m3u8_list 元素为相对路径）
- chzzk.naver.com → isSupported 域名映射

/*
 * PlatformRouter — Phase 3e：平台分发（对照上游 main.py:583 start_record 分流）。
 *
 * 上游按 record_url 域名逐平台分流（douyin.com → 抖音、www.douyu.com → 斗鱼，…），
 * 每平台 = spider.get_xxx_info_data（房间信息）+ stream.get_xxx_stream_url（画质映射+流地址）。
 *
 * 斗鱼流程（上游 spider.get_douyu_info_data + stream.get_douyu_stream_url）：
 *   betard 判开播/拿 rid → get_douyu_stream_data（签名 → getH5Play）
 *   → flv_url = rtmp_url/rtmp_live 作为 flv_url/record_url（stream.py:303）。
 *   未开播直接透传 is_live=false（不请求流）。
 *
 * 统一返回 DouyinStreamInfo 作为录制层消费的通用模型（斗鱼字段映射进同构）：
 *   flvUrl = 斗鱼 flv（FLV 直下，HLS 需 ffmpeg 暂不作为 record 源）
 *   recordUrl = flvUrl ?: streamUrl（上游 select_source_url：非 FLV 优先平台走 record_url）
 *   quality = 斗鱼 rate 标签（蓝光/超清/高清/标清）
 *
 * 其余 URL（含 douyin.com）→ 抖音路径，保持 Phase 1 行为不变。
 */
package com.wochatchat.liverecorder.platform

import com.wochatchat.liverecorder.platform.baidu.BaiduSpider
import com.wochatchat.liverecorder.platform.bilibili.BilibiliSpider
import com.wochatchat.liverecorder.platform.bigo.BigoSpider
import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import com.wochatchat.liverecorder.platform.douyu.DouyuSpider
import com.wochatchat.liverecorder.platform.huya.HuyaSpider
import com.wochatchat.liverecorder.platform.haixiu.HaixiuSpider
import com.wochatchat.liverecorder.platform.laixiu.LaixiuSpider
import com.wochatchat.liverecorder.platform.liveme.LiveMeSpider
import com.wochatchat.liverecorder.platform.taobao.TaobaoSpider
import com.wochatchat.liverecorder.platform.jd.JdSpider
import com.wochatchat.liverecorder.platform.kuaishou.KuaishouSpider
import com.wochatchat.liverecorder.platform.netease.NeteaseCcSpider
import com.wochatchat.liverecorder.platform.weibo.WeiboSpider
import com.wochatchat.liverecorder.platform.tiktok.TikTokSpider
import com.wochatchat.liverecorder.platform.xhs.XhsSpider
import com.wochatchat.liverecorder.platform.yy.YySpider
import com.wochatchat.liverecorder.platform.zhihu.ZhihuSpider
import com.wochatchat.liverecorder.platform.twitch.TwitchSpider
import com.wochatchat.liverecorder.platform.chzzk.CHZZKSpider
import com.wochatchat.liverecorder.platform.youtube.YouTubeSpider
import com.wochatchat.liverecorder.platform.shopee.ShopeeSpider
import com.wochatchat.liverecorder.platform.acfun.AcfunSpider
import com.wochatchat.liverecorder.platform.huajiao.HuajiaoSpider
import com.wochatchat.liverecorder.platform.inke.InkeSpider
import com.wochatchat.liverecorder.platform.liuxing.LiuxingSpider
import com.wochatchat.liverecorder.platform.yinbo.YinboSpider
import com.wochatchat.liverecorder.platform.soop.SoopliveSpider
import com.wochatchat.liverecorder.platform.pandatv.PandatvSpider
import com.wochatchat.liverecorder.platform.winktv.WinktvSpider
import com.wochatchat.liverecorder.platform.flextv.FlextvSpider
import com.wochatchat.liverecorder.platform.popkontv.PopkontvSpider
import com.wochatchat.liverecorder.platform.maoerfm.MaoerfmSpider
import com.wochatchat.liverecorder.platform.kugou.KugouSpider
import com.wochatchat.liverecorder.platform.changliao.ChangliaoSpider
import com.wochatchat.liverecorder.platform.vvxqiu.VvxqiuSpider
import com.wochatchat.liverecorder.platform.live17.Live17Spider
import com.wochatchat.liverecorder.platform.langlive.LangliveSpider
import com.wochatchat.liverecorder.platform.pplive.PpliveSpider
import com.wochatchat.liverecorder.platform.liujian.LiuJianFangSpider
import com.wochatchat.liverecorder.platform.lianjie.LianjieSpider
import com.wochatchat.liverecorder.platform.qiandurebo.QiandureboSpider
import com.wochatchat.liverecorder.platform.showroom.ShowroomSpider

class PlatformRouter(
    private val douyinSpider: DouyinSpider = DouyinSpider(),
    private val douyuSpider: DouyuSpider = DouyuSpider(),
    private val kuaishouSpider: KuaishouSpider = KuaishouSpider(),
    private val huyaSpider: HuyaSpider = HuyaSpider(),
    private val bilibiliSpider: BilibiliSpider = BilibiliSpider(),
    private val bigoSpider: BigoSpider = BigoSpider(),
    private val yySpider: YySpider = YySpider(),
    private val neteaseCcSpider: NeteaseCcSpider = NeteaseCcSpider(),
    private val zhihuSpider: ZhihuSpider = ZhihuSpider(),
    private val baiduSpider: BaiduSpider = BaiduSpider(),
    private val weiboSpider: WeiboSpider = WeiboSpider(),
    private val jdSpider: JdSpider = JdSpider(),
    private val haixiuSpider: HaixiuSpider = HaixiuSpider(),
    private val laixiuSpider: LaixiuSpider = LaixiuSpider(),
    private val livemeSpider: LiveMeSpider = LiveMeSpider(),
    private val taobaoSpider: TaobaoSpider = TaobaoSpider(),
    private val xhsSpider: XhsSpider = XhsSpider(),
    private val tiktokSpider: TikTokSpider = TikTokSpider(),
    private val twitchSpider: TwitchSpider = TwitchSpider(),
    private val chzzkSpider: CHZZKSpider = CHZZKSpider(),
    private val youTubeSpider: YouTubeSpider = YouTubeSpider(),
    private val shopeeSpider: ShopeeSpider = ShopeeSpider(),
    private val acfunSpider: AcfunSpider = AcfunSpider(),
    private val huajiaoSpider: HuajiaoSpider = HuajiaoSpider(),
    private val liuxingSpider: LiuxingSpider = LiuxingSpider(),
    private val inkeSpider: InkeSpider = InkeSpider(),
    private val yinboSpider: YinboSpider = YinboSpider(),
    private val soopliveSpider: SoopliveSpider = SoopliveSpider(),
    private val pandatvSpider: PandatvSpider = PandatvSpider(),
    private val winktvSpider: WinktvSpider = WinktvSpider(),
    private val flextvSpider: FlextvSpider = FlextvSpider(),
    private val popkontvSpider: PopkontvSpider = PopkontvSpider(),
    private val maoerfmSpider: MaoerfmSpider = MaoerfmSpider(),
    private val kugouSpider: KugouSpider = KugouSpider(),
    private val changliaoSpider: ChangliaoSpider = ChangliaoSpider(),
    private val vvxqiuSpider: VvxqiuSpider = VvxqiuSpider(),
    private val live17Spider: Live17Spider = Live17Spider(),
    private val langliveSpider: LangliveSpider = LangliveSpider(),
    private val ppliveSpider: PpliveSpider = PpliveSpider(),
    private val liujianFangSpider: LiuJianFangSpider = LiuJianFangSpider(),
    private val lianjieSpider: LianjieSpider = LianjieSpider(),
    private val qiandureboSpider: QiandureboSpider = QiandureboSpider(),
    private val showroomSpider: ShowroomSpider = ShowroomSpider(),
) {
    companion object {
        /** 斗鱼画质码映射（上游 stream.py get_douyu_stream_url video_quality_options）。 */
        val DOUYU_QUALITY_OPTIONS = mapOf(
            "OD" to "0", "BD" to "0",
            "UHD" to "3", "HD" to "2",
            "SD" to "1", "LD" to "1",
        )

        /** 上游 get_quality_code 缺失时画质为 null → 斗鱼默认 '0'（options.get(code, '0')）。 */
        const val DOUYU_DEFAULT_RATE = "0"

        fun isDouyuUrl(url: String): Boolean = url.contains("douyu.com/")

        /** 上游 main.py:609：live.kuaishou.com → 快手直播链路。 */
        fun isKuaishouUrl(url: String): Boolean = url.contains("live.kuaishou.com/")

        /** 上游 main.py:618：www.huya.com → 虎牙直播链路。 */
        fun isHuyaUrl(url: String): Boolean = url.contains("huya.com/")

        /** 上游 main.py:651：live.bilibili.com → B 站直播链路。 */
        fun isBilibiliUrl(url: String): Boolean = url.contains("live.bilibili.com/")

        /** 上游 main.py:643：https://www.yy.com/ → YY 直播链路。 */
        fun isYyUrl(url: String): Boolean = url.contains("www.yy.com/")

        fun isBigoUrl(url: String): Boolean =
            url.contains("www.bigo.tv/") || url.contains("slink.bigovideo.tv/")

        /** 7d：cc.163.com/ → 网易CC直播链路。 */
        fun isNeteaseUrl(url: String): Boolean = url.contains("cc.163.com/")

        /** 7d：live.baidu.com/ → 百度直播链路。 */
        fun isBaiduUrl(url: String): Boolean = url.contains("live.baidu.com/")

        /** 7d：weibo.com/ → 微博直播链路。 */
        fun isWeiboUrl(url: String): Boolean = url.contains("weibo.com/")

        /** 7d：lives.jd.com/ → 京东直播链路。 */
        fun isJdUrl(url: String): Boolean = url.contains("lives.jd.com/")

        /** 7d：zhihu.com/ → 知乎直播链路。 */
        fun isZhihuUrl(url: String): Boolean = url.contains("zhihu.com/")

        /** 8a：haixiutv.com / lehaitv.com → 嗨秀/乐嗨直播链路（上游同一 spider）。 */
        fun isHaixiuUrl(url: String): Boolean = url.contains("haixiutv.com") || url.contains("lehaitv.com")

        /** 8a：imkktv.com → 来秀直播链路。 */
        fun isLaixiuUrl(url: String): Boolean = url.contains("imkktv.com")

        /** 8a：liveme.com → LiveMe 直播链路。 */
        fun isLiveMeUrl(url: String): Boolean = url.contains("liveme.com")

        /** 8a：tb.cn → 淘宝直播链路（上游 main.py:977）。 */
        fun isTaobaoUrl(url: String): Boolean = url.contains("tb.cn") || url.contains("taobao.com")

        /** 8b：xiaohongshu.com / xhslink.com → 小红书直播链路（上游 main.py:655）。 */
        fun isXhsUrl(url: String): Boolean =
            url.contains("xiaohongshu.com") || url.contains("xhslink.com")

        /** 8c：tiktok.com → TikTok 直播链路（上游 main.py:596）。 */
        fun isTiktokUrl(url: String): Boolean = url.contains("tiktok.com/")

        /** 9a：twitch.tv → Twitch 直播链路（spider.py:2141）。 */
        fun isTwitchUrl(url: String): Boolean =
            url.contains("twitch.tv/")

        /** 9a：chzzk.naver.com → CHZZK 直播链路（spider.py:2696）。 */
        fun isCHZZKUrl(url: String): Boolean = url.contains("chzzk.naver.com/")

        /** 9a：youtube.com / youtu.be → YouTube 直播链路（spider.py:3002）。 */
        fun isYouTubeUrl(url: String): Boolean =
            url.contains("youtube.com/") || url.contains("youtu.be/")

        /** 9a：live.shopee / shp.ee → Shopee 直播链路（main.py:960）。 */
        fun isShopeeUrl(url: String): Boolean =
            url.contains("live.shopee") || url.contains("shp.ee")

        /** 9a：live.acfun.cn / m.acfun.cn → AcFun 直播链路（spider.py:2498）。 */
        fun isAcfunUrl(url: String): Boolean =
            url.contains("acfun.cn/")

        /** 7a R36：自定义流地址直录（上游 main.py:1026-1038「自定义录制直播」——
         *  非任何已知平台域名，且 URL 含 .m3u8/.flv 扩展时直录，不做房间解析）。 */
        fun isHuajiaoUrl(url: String): Boolean = url.contains("huajiao.com")
        fun isLiuxingUrl(url: String): Boolean = url.contains("7u66.com")
        fun isInkeUrl(url: String): Boolean = url.contains("inke.cn")
        fun isYinboUrl(url: String): Boolean = url.contains("ybw1666.com")

        /** 9c：韩国平台（上游 main.py:678-800）。 */
        fun isSoopUrl(url: String): Boolean =
            url.contains("sooplive.co.kr/") || url.contains("sooplive.com/")
        fun isPandatvUrl(url: String): Boolean = url.contains("pandalive.co.kr/")
        fun isWinktvUrl(url: String): Boolean = url.contains("winktv.co.kr/")
        fun isFlextvUrl(url: String): Boolean =
            url.contains("flextv.co.kr/") || url.contains("ttinglive.com/")
        fun isPopkontvUrl(url: String): Boolean = url.contains("popkontv.com/")

        /** 9d：小众中文平台（上游 main.py:678-800）。 */
        fun isMaoerfmUrl(url: String): Boolean = url.contains("missevan.com")
        fun isKugouUrl(url: String): Boolean =
            url.contains("kugou.com/") || url.contains("fanxing.kugou.com") ||
                url.contains("fanxing2.kugou.com") || url.contains("mfanxing.kugou.com")
        fun isChangliaoUrl(url: String): Boolean = url.contains("tlclw.com/")
        fun isVvxqiuUrl(url: String): Boolean = url.contains("vvxqiu.com/")

        /** 9e：17.live → 17Live 直播链路（spider.py:2816）。 */
        fun is17LiveUrl(url: String): Boolean = url.contains("17.live/")

        /** 9e：www.lang.live → 浪Live 直播链路（spider.py:2846）。 */
        fun isLangliveUrl(url: String): Boolean = url.contains("lang.live/")

        /** 9e：m.pp.weimipopo.com / h.catshow168.com → 漂漂/花猫 直播链路（spider.py:2872）。 */
        fun isPpliveUrl(url: String): Boolean =
            url.contains("weimipopo.com/") || url.contains("catshow168.com/")

        /** 9f：6.cn → 六间房直播链路（spider.py:2908）。 */
        fun isLiuJianFangUrl(url: String): Boolean = url.contains(".6.cn/")

        /** 9f：lailianjie.com → 连接直播链路（spider.py:3278）。 */
        fun isLianjieUrl(url: String): Boolean = url.contains("lailianjie.com/")

        /** 9f：qiandurebo.com → 千度热播直播链路（spider.py:1220）。 */
        fun isQiandureboUrl(url: String): Boolean = url.contains("qiandurebo.com/")

        /** 9f：showroom-live.com → ShowRoom 直播链路（spider.py:2433）。 */
        fun isShowroomUrl(url: String): Boolean = url.contains("showroom-live.com/")

        fun isDirectStreamUrl(url: String): Boolean =
            !isDouyuUrl(url) && !isKuaishouUrl(url) && !isHuyaUrl(url) &&
                !isBilibiliUrl(url) && !isYyUrl(url) && !isBigoUrl(url) &&
                !isNeteaseUrl(url) && !isBaiduUrl(url) && !isWeiboUrl(url) &&
                !isJdUrl(url) && !isZhihuUrl(url) &&
                !isHaixiuUrl(url) && !isLaixiuUrl(url) && !isLiveMeUrl(url) && !isTaobaoUrl(url) &&
                !isXhsUrl(url) && !isTiktokUrl(url) &&
                !isTwitchUrl(url) && !isCHZZKUrl(url) &&
                !isYouTubeUrl(url) && !isShopeeUrl(url) && !isAcfunUrl(url) &&
                !isHuajiaoUrl(url) && !isLiuxingUrl(url) && !isInkeUrl(url) && !isYinboUrl(url) &&
                !isSoopUrl(url) && !isPandatvUrl(url) && !isWinktvUrl(url) &&
                !isFlextvUrl(url) && !isPopkontvUrl(url) &&
                !isMaoerfmUrl(url) && !isKugouUrl(url) &&
                !isChangliaoUrl(url) && !isVvxqiuUrl(url) &&
                !is17LiveUrl(url) && !isLangliveUrl(url) && !isPpliveUrl(url) &&
                !isLiuJianFangUrl(url) && !isLianjieUrl(url) &&
                !isQiandureboUrl(url) && !isShowroomUrl(url) &&
                !url.contains("douyin.com/") && !url.contains("iesdouyin.com/") &&
                (url.contains(".m3u8") || url.contains(".flv"))

        /** 6d R14：URL 是否属于已接入平台（域名判断，与 fetchStreamInfo 分流同源）。 */
        fun isSupported(url: String): Boolean = isDirectStreamUrl(url) || when {
            isDouyuUrl(url) || isKuaishouUrl(url) || isHuyaUrl(url) ||
                isBilibiliUrl(url) || isYyUrl(url) || isBigoUrl(url) ||
                isNeteaseUrl(url) || isBaiduUrl(url) || isWeiboUrl(url) ||
                isJdUrl(url) || isZhihuUrl(url) ||
                isHaixiuUrl(url) || isLaixiuUrl(url) || isLiveMeUrl(url) || isTaobaoUrl(url) ||
                isXhsUrl(url) || isTiktokUrl(url) ||
                isTwitchUrl(url) || isCHZZKUrl(url) ||
                isYouTubeUrl(url) || isShopeeUrl(url) || isAcfunUrl(url) ||
                isHuajiaoUrl(url) || isLiuxingUrl(url) || isInkeUrl(url) || isYinboUrl(url) ||
                isSoopUrl(url) || isPandatvUrl(url) || isWinktvUrl(url) ||
                isFlextvUrl(url) || isPopkontvUrl(url) ||
                isMaoerfmUrl(url) || isKugouUrl(url) ||
                isChangliaoUrl(url) || isVvxqiuUrl(url) ||
                is17LiveUrl(url) || isLangliveUrl(url) || isPpliveUrl(url) ||
                isLiuJianFangUrl(url) || isLianjieUrl(url) ||
                isQiandureboUrl(url) || isShowroomUrl(url) -> true
            else -> url.contains("douyin.com/") || url.contains("iesdouyin.com/")
        }
    }

    /** 源分发 + 画质映射，一步到位（MonitorLoop 轮询与 RecordController 录制共用）。 + 画质映射，一步到位（MonitorLoop 轮询与 RecordController 录制共用）。
     *  [cookies] 平台键 → cookie 串（4b AuthStore），快手等平台按需取用。 */
    suspend fun fetchStreamInfo(
        url: String,
        quality: String? = null,
        proxyAddr: String? = null,
        cookies: Map<String, String> = emptyMap(),
    ): DouyinStreamInfo = when {
        isDouyuUrl(url) -> fetchDouyu(url, quality, proxyAddr)
        isKuaishouUrl(url) -> fetchKuaishou(url, quality, proxyAddr, cookies["kuaishou"])
        isHuyaUrl(url) -> fetchHuya(url, quality, proxyAddr, cookies["huya"])
        isBilibiliUrl(url) -> fetchBilibili(url, quality, proxyAddr, cookies["bilibili"])
        isYyUrl(url) -> fetchYy(url, quality, proxyAddr, cookies["yy"])
        isBigoUrl(url) -> fetchBigo(url, quality, proxyAddr, cookies["bigo"])
        isNeteaseUrl(url) -> fetchNetease(url, quality, proxyAddr, cookies["netease"])
        isBaiduUrl(url) -> fetchBaidu(url, proxyAddr)
        isWeiboUrl(url) -> fetchWeibo(url, null, proxyAddr, cookies["weibo"])
        isJdUrl(url) -> fetchJd(url, null, proxyAddr, cookies["jd"])
        isZhihuUrl(url) -> fetchZhihu(url, null, proxyAddr, cookies["zhihu"])
        isHaixiuUrl(url) -> fetchHaixiu(url, proxyAddr, cookies["haixiu"], cookies["lehaitv"])
        isLaixiuUrl(url) -> fetchLaixiu(url, proxyAddr, cookies["laixiu"])
        isLiveMeUrl(url) -> fetchLiveMe(url, proxyAddr, cookies["liveme"])
        isTaobaoUrl(url) -> fetchTaobao(url, quality, proxyAddr, cookies["taobao"])
        isXhsUrl(url) -> fetchXhs(url, proxyAddr, cookies["xhs"])
        isTiktokUrl(url) -> fetchTiktok(url, quality, proxyAddr, cookies["tiktok"])
        isTwitchUrl(url) -> fetchTwitch(url, proxyAddr, cookies["twitch"])
        isCHZZKUrl(url) -> fetchCHZZK(url, proxyAddr, cookies["chzzk"])
        isYouTubeUrl(url) -> fetchYouTube(url, proxyAddr, cookies["youtube"])
        isShopeeUrl(url) -> fetchShopee(url, proxyAddr, cookies["shopee"])
        isAcfunUrl(url) -> fetchAcfun(url, proxyAddr, cookies["acfun"])
        isHuajiaoUrl(url) -> fetchHuajiao(url, proxyAddr, cookies["huajiao"])
        isLiuxingUrl(url) -> fetchLiuxing(url, proxyAddr, cookies["liuxing"])
        isInkeUrl(url) -> fetchInke(url, proxyAddr, cookies["yingke"])
        isYinboUrl(url) -> fetchYinbo(url, proxyAddr, cookies["yinbo"])
        isSoopUrl(url) -> fetchSoop(url, proxyAddr, cookies["sooplive"])
        isPandatvUrl(url) -> fetchPandatv(url, proxyAddr, cookies["pandatv"])
        isWinktvUrl(url) -> fetchWinktv(url, proxyAddr, cookies["winktv"])
        isFlextvUrl(url) -> fetchFlextv(url, proxyAddr, cookies["flextv"])
        isPopkontvUrl(url) -> fetchPopkontv(url, proxyAddr, cookies["popkontv"])
        isMaoerfmUrl(url) -> fetchMaoerfm(url, proxyAddr, cookies["maoer"])
        isKugouUrl(url) -> fetchKugou(url, proxyAddr, cookies["kugou"])
        isChangliaoUrl(url) -> fetchChangliao(url, proxyAddr, cookies["changliao"])
        isVvxqiuUrl(url) -> fetchVvxqiu(url, proxyAddr, cookies["vvxqiu"])
        is17LiveUrl(url) -> fetch17Live(url, proxyAddr, cookies["seventeen"])
        isLangliveUrl(url) -> fetchLanglive(url, proxyAddr, cookies["langlive"])
        isPpliveUrl(url) -> fetchPplive(url, proxyAddr,
            cookies["pplive"] ?: if (url.contains("catshow")) cookies["huamao"] else null)
        isLiuJianFangUrl(url) -> fetchLiuJianFang(url, proxyAddr, cookies["liujian"])
        isLianjieUrl(url) -> fetchLianjie(url, proxyAddr, cookies["lianjie"])
        isQiandureboUrl(url) -> fetchQiandurebo(url, proxyAddr, cookies["qiandurebo"])
        isShowroomUrl(url) -> fetchShowroom(url, proxyAddr, cookies["showroom"])
        isDirectStreamUrl(url) -> fetchDirectStream(url)
        else -> douyinSpider.fetchStreamInfo(url, quality, proxyAddr)
    }

    /**
     * 自定义流地址直录（上游 main.py:1026-1038）：URL 含 .m3u8/.flv 时跳过房间解析，
     * is_live 恒 true、record_url = URL 本身（.flv → flv_url，否则 m3u8_url）。
     * 移动端增强：上游 anchor_name 每轮 uuid4[:8] 随机（文件名每轮都变），
     * 安卓端改 URL 哈希稳定 8 位，同名文件可按主播名归组。
     */
    private fun fetchDirectStream(url: String): DouyinStreamInfo {
        val anchorName = "自定义录制直播_" + String.format("%08x", url.hashCode())
        return DouyinStreamInfo(
            anchorName = anchorName,
            isLive = true,
            flvUrl = if (url.contains(".flv")) url else "",
            m3u8Url = if (url.contains(".m3u8")) url else "",
            recordUrl = url,
        )
    }

    /**
     * 斗鱼 → 抖音同构映射（上游两步：get_douyu_info_data → get_douyu_stream_url）：
     * - 未开播：透传 anchor_name + is_live=false（不请求流地址）
     * - 开播：getH5Play → FLV（rtmp_url/rtmp_live）作为 flv/record 源；HLS 仅作 m3u8Url 参考
     */
    private suspend fun fetchDouyu(url: String, quality: String? = null, proxyAddr: String? = null): DouyinStreamInfo {
        val info = douyuSpider.getDouyuInfo(url, proxyAddr)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        val rid = info.roomId ?: DouyuSpider.parseRidFromUrl(url)
            ?: return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        val stream = douyuSpider.getDouyuStreamData(rid, rate = douyuRate(quality), proxyAddr = proxyAddr)
        val flv = stream.flvUrl
        val hls = stream.streamUrl
        return DouyinStreamInfo(
            anchorName = stream.anchorName,
            isLive = !flv.isNullOrEmpty() || !hls.isNullOrEmpty(),
            title = info.title ?: "",
            quality = stream.qualityLabel ?: "",
            m3u8Url = hls.orEmpty(),
            flvUrl = flv.orEmpty(),
            recordUrl = flv ?: hls.orEmpty(),
        )
    }

    /** 画质码 → 斗鱼 rate（上游 video_quality_options.get(code, '0') 语义）。 */
    private fun douyuRate(qualityCode: String?): String =
        qualityCode?.let { DOUYU_QUALITY_OPTIONS[it] } ?: DOUYU_DEFAULT_RATE

    /**
     * 快手 → 抖音同构映射（上游两步：get_kuaishou_stream_data → get_kuaishou_stream_url）：
     * web 页路径（主链路）+ /u/ 短链 api2 优先回落 web；recordUrl 恒为 FLV（HLS 仅参考）。
     */
    private suspend fun fetchKuaishou(
        url: String,
        quality: String?,
        proxyAddr: String?,
        cookie: String?,
    ): DouyinStreamInfo {
        val info = kuaishouSpider.getKuaishouInfo(url, proxyAddr, cookie)
        val play = kuaishouSpider.selectStream(info, quality)
            ?: return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(
            anchorName = info.anchorName,
            isLive = true,
            quality = play.quality,
            m3u8Url = play.m3u8Url,
            flvUrl = play.flvUrl,
            recordUrl = play.flvUrl.ifEmpty { play.m3u8Url },
        )
    }

    /**
     * 虎牙 → 抖音同构映射（main.py:618-630 + stream.py:210 get_huya_stream_url）：
     * OD/BD/UHD → app 路径（TX CDN 优先预计算 URL）；HD/SD/LD → web 路径 + anti-code 重算。
     * recordUrl 恒为 FLV（上游 record_url = flv_url or m3u8_url）。
     */
    private suspend fun fetchHuya(
        url: String,
        quality: String?,
        proxyAddr: String?,
        cookie: String?,
    ): DouyinStreamInfo {
        // 上游 main.py:618-630：OD/BD/UHD → app 路径（get_huya_app_stream_url），
        // HD/SD/LD → web 路径（get_huya_stream_data + get_huya_stream_url），按画质分流取房间信息
        val info = huyaSpider.getHuyaInfo(url, quality, proxyAddr, cookie)
        val play = huyaSpider.selectStream(info, quality)
            ?: return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(
            anchorName = play.anchorName,
            isLive = true,
            title = play.title,
            quality = play.quality,
            m3u8Url = play.m3u8Url,
            flvUrl = play.flvUrl,
            recordUrl = play.recordUrl,
        )
    }

    /** B 站 → 抖音同构映射（room_init/Master/info + playUrl 直链）。 */
    private suspend fun fetchBilibili(
        url: String,
        quality: String?,
        proxyAddr: String?,
        cookie: String?,
    ): DouyinStreamInfo {
        val info = bilibiliSpider.getRoomInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        val qn = biliQn(quality)
        val playUrl = bilibiliSpider.getStreamData(url, qn, proxyAddr, cookie)
        return DouyinStreamInfo(
            anchorName = info.anchorName,
            isLive = !playUrl.isNullOrEmpty(),
            title = info.title,
            quality = quality ?: "OD",
            recordUrl = playUrl.orEmpty(),
            flvUrl = playUrl.orEmpty(),
        )
    }

    /** YY → 抖音同构映射（上游 spider.get_yy_stream_data + stream.get_yy_stream_url）：
     * - 未开播（无 avp_info_res）：is_live=false，flvUrl 空
     * - 开播：flvUrl = stream_line_addr 第一个 CDN 的 url，quality 固定 OD
     * recordUrl = flvUrl（Bigo 用 m3u8，YY 用 FLV）。 */
    private suspend fun fetchYy(
        url: String,
        quality: String?,
        proxyAddr: String?,
        cookie: String?,
    ): DouyinStreamInfo {
        val info = yySpider.getYyStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName,
            isLive = true,
            title = info.title,
            quality = info.quality,
            flvUrl = info.flvUrl,
            recordUrl = info.flvUrl,
        )
    }

    /** Bigo → 抖音同构映射（上游 spider.get_bigo_stream_url）：
     * - 未开播：anchorName 填充，is_live=false，m3u8Url 空
     * - 开播：m3u8Url = data.hls_src，recordUrl = m3u8Url（m3u8 → ffmpeg 分段录制）*/
    private suspend fun fetchBigo(
        url: String,
        quality: String?,
        proxyAddr: String?,
        cookie: String?,
    ): DouyinStreamInfo {
        val info = bigoSpider.getBigoStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName,
            isLive = true,
            title = info.title,
            quality = "OD",
            m3u8Url = info.m3u8Url,
            recordUrl = info.recordUrl,
        )
    }

    /** 7d 网易CC → 抖音同构映射（spider.py:1189 + stream.py:382）：
     *  开播：m3u8 = sharefile，flv = quickplay 分档 CDN，recordUrl = flv ?: m3u8。 */
    private suspend fun fetchNetease(
        url: String, quality: String?, proxyAddr: String?, cookie: String?,
    ): DouyinStreamInfo {
        val info = neteaseCcSpider.getStreamInfo(url, proxyAddr, cookie, quality)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 7d 知乎 → 抖音同构映射（spider.py:2657）：recordUrl = hlsUrl。 */
    private suspend fun fetchZhihu(url: String, quality: String?, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = zhihuSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 7d 百度 → 抖音同构映射（spider.py:1947）：m3u8 列表首项。 */
    private suspend fun fetchBaidu(url: String, proxyAddr: String?): DouyinStreamInfo {
        val info = baiduSpider.getStreamInfo(url, proxyAddr)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl,
        )
    }

    /** 7d 微博 → 抖音同构映射（spider.py:2007）：pull 流 hls+flv，recordUrl = flv or m3u8。 */
    private suspend fun fetchWeibo(url: String, quality: String?, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = weiboSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 7d 京东 → 抖音同构映射（spider.py:3108）：recordUrl = m3u8（上游同语义）。 */
    private suspend fun fetchJd(url: String, quality: String?, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = jdSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 8a 嗨秀/乐嗨 → 抖音同构映射（spider.py:2727）：flv 直下，cookie 按域名分流。 */
    private suspend fun fetchHaixiu(
        url: String, proxyAddr: String?, cookieHaixiu: String?, cookieLehai: String?,
    ): DouyinStreamInfo {
        val cookie = if (url.contains("haixiutv.com")) cookieHaixiu else cookieLehai
        val info = haixiuSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 8a 来秀 → 抖音同构映射（spider.py:3309）：flv 直下。 */
    private suspend fun fetchLaixiu(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = laixiuSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 8a LiveMe → 抖音同构映射（spider.py:2209）：recordUrl = m3u8 ?: flv。 */
    private suspend fun fetchLiveMe(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = livemeSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 8a 淘宝 → 抖音同构映射（spider.py:3029）：画质降序后按码取档，recordUrl = m3u8。 */
    private suspend fun fetchTaobao(
        url: String, quality: String?, proxyAddr: String?, cookie: String?,
    ): DouyinStreamInfo {
        val info = taobaoSpider.getStreamInfo(url, quality, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 8b 小红书 → 抖音同构映射（spider.py:769）：固定 CDN 直链 flv，recordUrl = flv。 */
    private suspend fun fetchXhs(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = xhsSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 8c TikTok → 抖音同构映射（stream.py:82）：recordUrl = m3u8 ?: flv（录制层 FLV 优先）。 */
    private suspend fun fetchTiktok(
        url: String, quality: String?, proxyAddr: String?, cookie: String?,
    ): DouyinStreamInfo {
        val info = tiktokSpider.getStreamInfo(url, quality, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }

    /** 9a Twitch → 抖音同构映射（spider.py:2141）：recordUrl = m3u8。 */
    private suspend fun fetchTwitch(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = twitchSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl,
        )
    }

    /** 9a CHZZK → 抖音同构映射（spider.py:2696）：recordUrl = m3u8。 */
    private suspend fun fetchCHZZK(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = chzzkSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl,
        )
    }

    /** 9a YouTube → 抖音同构映射（spider.py:3002）：recordUrl = m3u8。 */
    private suspend fun fetchYouTube(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = youTubeSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl,
        )
    }

    /** 9a Shopee → 抖音同构映射（spider.py:2943）：recordUrl = flv。 */
    private suspend fun fetchShopee(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = shopeeSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            flvUrl = info.flvUrl, recordUrl = info.recordUrl,
        )
    }


    // ── Batch B: 花椒 / 流星 / 映客 / 音播 ──────────────────────────────────────

    /** 9b 花椒 → 双路径：房间 app API / 用户页（spider.py:2351）。 */
    private suspend fun fetchHuajiao(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = huajiaoSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true, title = info.title,
            flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9b 流星 → wap.7u66.com API（spider.py:2400）。 */
    private suspend fun fetchLiuxing(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = liuxingSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9b 映客 → webapi.busi.inke.cn（spider.py:2582）。 */
    private suspend fun fetchInke(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = inkeSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9b 音播 → wap.ybw1666.com API + 房间页 var config（spider.py:2615）。 */
    private suspend fun fetchYinbo(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = yinboSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    // ── Batch C: SOOP / PandaTV / WinkTV / FlexTV / PopkonTV ──────────────────

    /** 9c SOOP → 韩国站/国际站双路径（spider.py:1078），recordUrl = master m3u8。 */
    private suspend fun fetchSoop(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = soopliveSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl)
    }

    /** 9c PandaTV → api.pandalive.co.kr 双 POST（spider.py:1251）。 */
    private suspend fun fetchPandatv(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = pandatvSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl)
    }

    /** 9c WinkTV → api.winktv.co.kr 双 POST（spider.py:1361）。 */
    private suspend fun fetchWinktv(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = winktvSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl)
    }

    /** 9c FlexTV → __NEXT_DATA__ + stream API（spider.py:1472），19+ 走账密登录路径由 spider 内部处理。 */
    private suspend fun fetchFlextv(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = flextvSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9c PopkonTV → search/all + castwatch（spider.py:1740），token 登录路径由 spider 内部处理。 */
    private suspend fun fetchPopkontv(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = popkontvSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl)
    }

    // ── Batch D: 猫耳FM / 酷狗 / 畅聊 / VV星球 ─────────────────────────────────

    /** 9d 猫耳FM → fm.missevan.com API（spider.py:1303），recordUrl = flv ?: m3u8。 */
    private suspend fun fetchMaoerfm(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = maoerfmSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9d 酷狗 → getEnterRoomInfo + streamaddr 双 API（spider.py:2054），recordUrl = httpsFlv。 */
    private suspend fun fetchKugou(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = kugouSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9d 畅聊 → wap.tlclw.com API + 房间页 var config（spider.py:2541），recordUrl = flv。 */
    private suspend fun fetchChangliao(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = changliaoSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9d VV星球 → captain/banner + m3u8 探测（spider.py:2776），recordUrl = m3u8。 */
    private suspend fun fetchVvxqiu(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = vvxqiuSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl)
    }

    // ── Batch E: 17Live / 浪Live / 漂漂 ─────────────────────────────────────────

    /** 9e 17Live → user/room + lives/viewers 双 API（spider.py:2816），recordUrl = flv。 */
    private suspend fun fetch17Live(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = live17Spider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9e 浪Live → api.lang.live liveinfo（spider.py:2846），recordUrl = m3u8。 */
    private suspend fun fetchLanglive(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = langliveSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9e 漂漂/花猫 → live/preview POST（spider.py:2872），recordUrl = m3u8。 */
    private suspend fun fetchPplive(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = ppliveSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl)
    }

    // ── Batch F: 六间房 / 连接 / 千度热播 / ShowRoom ───────────────────────────────

    /** 9f 六间房 → v.6.cn 房间页 + coo mobile API（spider.py:2908），recordUrl = flv。 */
    private suspend fun fetchLiuJianFang(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = liujianFangSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9f 连接 → api.lailianjie.com（spider.py:3278），recordUrl = flv。 */
    private suspend fun fetchLianjie(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = lianjieSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9f 千度热播 → qiandurebo.com 房间页（spider.py:1220），recordUrl = flv。 */
    private suspend fun fetchQiandurebo(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = qiandureboSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            flvUrl = info.flvUrl, recordUrl = info.recordUrl)
    }

    /** 9f ShowRoom → showroom-live.com API（spider.py:2433），recordUrl = http m3u8。 */
    private suspend fun fetchShowroom(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = showroomSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(anchorName = info.anchorName, isLive = true,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl)
    }

    /** 9a AcFun → 抖音同构映射（spider.py:2498）：recordUrl = m3u8（快手协议 bitrate 最高档）。 */
    private suspend fun fetchAcfun(url: String, proxyAddr: String?, cookie: String?): DouyinStreamInfo {
        val info = acfunSpider.getStreamInfo(url, proxyAddr, cookie)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        return DouyinStreamInfo(
            anchorName = info.anchorName, isLive = true, title = info.title,
            m3u8Url = info.m3u8Url, recordUrl = info.recordUrl,
        )
    }

    /** 画质码 → B 站 qn（上游 stream.py:360 video_quality_options）。 */
    private fun biliQn(qualityCode: String?): String = when (qualityCode) {
        "OD" -> "10000"
        "BD" -> "400"
        "UHD" -> "250"
        "HD" -> "150"
        "SD", "LD" -> "80"
        else -> "10000"
    }
}

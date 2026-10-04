package com.xingyan.shortlink.common.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * UA 分类器语义（M2-32）：顺序优先、多 token、大小写、兜底 unknown，以及<b>表写错时必须炸</b>。
 * 真实 UA 样本集不在这里（那份要跟着 shipped 特征表走，见 sl-jump 的 UaFeatureTableTest）。
 */
class UaClassifierTest {

    private static UaFeature feature(String key, String... tokens) {
        return new UaFeature(key, List.of(tokens));
    }

    private static UaClassifier table() {
        return new UaClassifier(
                List.of(feature("wechat", "micromessenger"), feature("qq", "qq/", "mqqbrowser")),
                List.of(feature("ios", "iphone"), feature("desktop", "windows nt", "macintosh")));
    }

    @Test
    void containerAndDeviceAreIndependent() {
        // 一次 UA 要同时给出两个维度：微信里的 iPhone 不能因为"是微信"就丢掉设备信息
        var ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) MicroMessenger/8.0.40";
        assertEquals(UaContainer.WECHAT, table().container(ua));
        assertEquals(UaDevice.IOS, table().device(ua));
    }

    @Test
    void declarationOrderWinsNotTokenLength() {
        // 两个容器都能命中时，先声明的那个赢（DESIGN 6.2：首个子串命中即定类）
        var ua = "Android 13 MicroMessenger/8.0 QQ/9.0.9.4460";
        var classifier = new UaClassifier(
                List.of(feature("wechat", "micromessenger"), feature("qq", "qq/")),
                List.of(feature("android", "android")));
        assertEquals(UaContainer.WECHAT, classifier.container(ua), "wechat 排在前面就赢");

        var flipped = new UaClassifier(
                List.of(feature("qq", "qq/"), feature("wechat", "micromessenger")),
                List.of(feature("android", "android")));
        assertEquals(UaContainer.QQ, flipped.container(ua), "换顺序就换结论——所以顺序是配置的一部分，不是实现细节");
    }

    @Test
    void anyTokenMatchesAndMatchingIsCaseInsensitive() {
        assertEquals(UaContainer.QQ, table().container("Safari/533.1 MQQBrowser/11.0"));
        assertEquals(UaContainer.QQ, table().container("Safari/537.36 QQ/9.0.9.4460"));
        assertEquals(UaContainer.WECHAT, table().container("MICROMESSENGER/8.0.40"), "UA 的大小写不作数");
    }

    @Test
    void unmatchedAndEmptyUaAreUnknownNotError() {
        // 未识别不是异常：DESIGN 6.2 的兜底就是"新容器最多不走高级规则"
        assertEquals(UaContainer.UNKNOWN, table().container("curl/8.4.0"));
        assertEquals(UaContainer.UNKNOWN, table().container(""));
        assertEquals(UaContainer.UNKNOWN, table().container(null));
        assertEquals(UaDevice.UNKNOWN, table().device(null));
        assertEquals(UaDevice.UNKNOWN, table().device("Mozilla/5.0 (Phone; OpenHarmony 5.0) ArkWeb/4.0.6.1 Mobile"));
    }

    @Test
    void classifyDoesBothDimensionsInOneCall() {
        var profile = table().classify("(Windows NT 10.0) MicroMessenger/7.0.20");
        assertEquals(UaContainer.WECHAT, profile.container());
        assertEquals(UaDevice.DESKTOP, profile.device());
    }

    @Test
    void badTableFailsFastInsteadOfSilentlyNeverMatching() {
        // 这五个断言是 M2-32 最重要的一条：静默不命中的特征表，要等到"微信流量为 0"才被发现，
        // 而那时已经分不清是配置、UA 还是路由规则的问题
        assertThrows(IllegalArgumentException.class,
                () -> new UaClassifier(List.of(), List.of(feature("ios", "iphone"))), "空表＝所有流量都判 unknown");
        assertThrows(IllegalArgumentException.class,
                () -> new UaClassifier(List.of(feature("instagram", "instagram")), List.of(feature("ios", "iphone"))),
                "key 不在词汇表里");
        assertThrows(IllegalArgumentException.class,
                () -> new UaClassifier(List.of(feature("unknown", "micromessenger")), List.of(feature("ios", "iphone"))),
                "unknown 是兜底值不是特征");
        assertThrows(IllegalArgumentException.class,
                () -> new UaClassifier(List.of(feature("wechat", "micromessenger"), feature("wechat", "weixin")),
                        List.of(feature("ios", "iphone"))), "同一个 key 出现两次＝顺序埋雷");
        assertThrows(IllegalArgumentException.class,
                () -> new UaClassifier(List.of(feature("wechat")), List.of(feature("ios", "iphone"))),
                "没有 token 的条目永远不会命中");
    }

    @Test
    void enumKeysAreTheSharedVocabulary() {
        // route 条件值、click_event 取值、特征表 key 三处共用这套串（DESIGN 4.2）：
        // 微信的 key 是业务名 wechat，UA 串 micromessenger 只待在 tokens 里
        assertEquals("wechat", UaContainer.WECHAT.key());
        assertEquals("unknown", UaContainer.UNKNOWN.key());
        assertEquals(java.util.Optional.of(UaContainer.WECHAT), UaContainer.fromKey(" WeChat "));
        assertEquals(java.util.Optional.empty(), UaContainer.fromKey("micromessenger"));
        assertEquals("ios", UaDevice.IOS.key());
    }
}

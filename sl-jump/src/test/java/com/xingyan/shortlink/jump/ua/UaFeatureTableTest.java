package com.xingyan.shortlink.jump.ua;

import com.xingyan.shortlink.common.route.UaClassifier;
import com.xingyan.shortlink.common.route.UaContainer;
import com.xingyan.shortlink.common.route.UaDevice;
import com.xingyan.shortlink.common.route.UaFeature;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * M2-32 的验收主体：<b>真实 UA 样本重放</b>，而且跑的是 {@code application.yml} 里那份
 * shipped 特征表——不是测试自带的表。否则"单测全绿"和"线上认不出微信"可以同时成立。
 *
 * <p>样本都是线上见过的形态（微信 iOS/安卓/PC、抖音、手机 QQ、QQ 浏览器、微博、支付宝、
 * 裸浏览器、curl、爬虫、鸿蒙）。鸿蒙那条<b>判不出设备大类</b>，这是自维护表的已知覆盖代价，
 * 写在 DESIGN 6.2 里并用断言钉住——避免以后被当成"已支持"。
 */
class UaFeatureTableTest {

    private static final String WECHAT_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Mobile/15E148 MicroMessenger/8.0.40(0x18002832) NetType/WIFI Language/zh_CN";
    private static final String WECHAT_ANDROID =
            "Mozilla/5.0 (Linux; Android 13; M2102J2SC Build/TKQ1.220829.002; wv) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/107.0.5304.141 Mobile Safari/537.36 MMWEBID/9527 "
                    + "MicroMessenger/8.0.40.2420(0x28002837) WeChat/arm64 Weixin NetType/WIFI ABI/arm64";
    private static final String WECHAT_WINDOWS_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 "
                    + "Safari/537.36 NetType/WIFI MicroMessenger/7.0.20.1781(0x6001463B) WindowsWechat(0x63090c1b)";
    private static final String DOUYIN_ANDROID =
            "Mozilla/5.0 (Linux; U; Android 13; zh-CN; V2199A Build/TP1A.220624.014) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 Mobile Safari/537.36 douyin_29.6.0";
    private static final String DOUYIN_IPHONE_AWEME =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6_1 like Mac OS X) AppleWebKit/604.1.3 (KHTML, like Gecko) "
                    + "Mobile/15E148 aweme_24.6.0 NetType/WiFi";
    private static final String MOBILE_QQ_ANDROID =
            "Mozilla/5.0 (Linux; Android 13; 2211133C Build/TKQ1.221114.001; wv) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/107.0.5304.141 Mobile Safari/537.36 QQ/9.0.9.4460";
    private static final String QQ_BROWSER_ANDROID =
            "Mozilla/5.0 (Linux; U; Android 12; zh-cn; M2012K11AC Build/RKQ1.211001.001) AppleWebKit/533.1 "
                    + "(KHTML, like Gecko) Version/4.0 UCBrowser/1.0.0.100 UWS/3.5.2.7 Mobile Safari/533.1 MQQBrowser/11.0";
    private static final String WEIBO_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Mobile/15E148 Weibo (iPhone15,2__weibo__13.7.0__iphone__os16.6)";
    private static final String WEIBO_ANDROID =
            "Mozilla/5.0 (Linux; U; Android 12; zh-CN; M2102J2SC Build/SP1A.210812.016) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 UCBrowser/15.0.1.303 Mobile Safari/537.36 "
                    + "Weibo (Xiaomi-M2102J2SC__weibo__13.5.2__android__os12)";
    private static final String ALIPAY_ANDROID =
            "Mozilla/5.0 (Linux; U; Android 13; zh-CN; 2211133C Build/TKQ1.221114.001) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UWS/5.12.9.0 Mobile Safari/537.36 "
                    + "AliApp(AP/10.5.56.2000) AlipayClient/10.5.56.2000 Language/zh-Hans";
    private static final String SAFARI_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String SAFARI_IPAD =
            "Mozilla/5.0 (iPad; CPU OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/16.6 Mobile/15E148 Safari/604.1";
    private static final String CHROME_ANDROID =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/117.0.0.0 Mobile Safari/537.36";
    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/117.0.0.0 Safari/537.36";
    private static final String SAFARI_MACOS =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.0 Safari/605.1.15";
    private static final String OPENHARMONY_MOBILE =
            "Mozilla/5.0 (Phone; OpenHarmony 5.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 "
                    + "Safari/537.36 ArkWeb/4.0.6.1 Mobile HuaweiBrowser_1.5.9.302";
    private static final String CURL = "curl/8.4.0";
    private static final String GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    @Test
    void shippedTableClassifiesRealUaSamples() {
        UaClassifier ua = shippedTable();
        assertPair(ua, WECHAT_IPHONE, UaContainer.WECHAT, UaDevice.IOS);
        assertPair(ua, WECHAT_ANDROID, UaContainer.WECHAT, UaDevice.ANDROID);
        assertPair(ua, WECHAT_WINDOWS_PC, UaContainer.WECHAT, UaDevice.DESKTOP);
        assertPair(ua, DOUYIN_ANDROID, UaContainer.DOUYIN, UaDevice.ANDROID);
        assertPair(ua, DOUYIN_IPHONE_AWEME, UaContainer.DOUYIN, UaDevice.IOS);
        assertPair(ua, MOBILE_QQ_ANDROID, UaContainer.QQ, UaDevice.ANDROID);
        assertPair(ua, QQ_BROWSER_ANDROID, UaContainer.QQ, UaDevice.ANDROID);
        assertPair(ua, WEIBO_IPHONE, UaContainer.WEIBO, UaDevice.IOS);
        assertPair(ua, WEIBO_ANDROID, UaContainer.WEIBO, UaDevice.ANDROID);
        assertPair(ua, ALIPAY_ANDROID, UaContainer.ALIPAY, UaDevice.ANDROID);
        assertPair(ua, SAFARI_IPHONE, UaContainer.UNKNOWN, UaDevice.IOS);
        assertPair(ua, SAFARI_IPAD, UaContainer.UNKNOWN, UaDevice.IOS);
        assertPair(ua, CHROME_ANDROID, UaContainer.UNKNOWN, UaDevice.ANDROID);
        assertPair(ua, CHROME_WINDOWS, UaContainer.UNKNOWN, UaDevice.DESKTOP);
        assertPair(ua, SAFARI_MACOS, UaContainer.UNKNOWN, UaDevice.DESKTOP);
        // 自维护特征表的覆盖代价，如实钉在这里：鸿蒙 NEXT 的 UA 里没有 android/iphone/windows 任何一条串
        assertPair(ua, OPENHARMONY_MOBILE, UaContainer.UNKNOWN, UaDevice.UNKNOWN);
        assertPair(ua, CURL, UaContainer.UNKNOWN, UaDevice.UNKNOWN);
        assertPair(ua, GOOGLEBOT, UaContainer.UNKNOWN, UaDevice.UNKNOWN);
    }

    @Test
    void deviceOrderProtectsMobileFromDesktopBucket() {
        // Android UA 里有 "linux"、iPad UA 里有 "like mac OS X"：desktop 若排在前面就会把移动端吃掉
        UaClassifier ua = shippedTable();
        assertSame(UaDevice.ANDROID, ua.device(CHROME_ANDROID), "android 必须先于 desktop");
        assertSame(UaDevice.IOS, ua.device(SAFARI_IPAD), "ios 必须先于 desktop");
    }

    @Test
    void tableKeysMatchTheEventVocabulary() {
        // click_event.device_type 与 route 条件值取的就是这些 key（DESIGN 4.2 的词汇表约束）
        List<String> containerKeys = new ArrayList<>();
        shippedRaw("containers").forEach(f -> containerKeys.add(f.key()));
        assertEquals(List.of("wechat", "douyin", "qq", "weibo", "alipay"), containerKeys);

        List<String> deviceKeys = new ArrayList<>();
        shippedRaw("devices").forEach(f -> deviceKeys.add(f.key()));
        assertEquals(List.of("ios", "android", "desktop"), deviceKeys);
    }

    private static void assertPair(UaClassifier ua, String raw, UaContainer container, UaDevice device) {
        var profile = ua.classify(raw);
        assertEquals(container, profile.container(), () -> "容器判错: " + raw);
        assertEquals(device, profile.device(), () -> "设备判错: " + raw);
        // 入库的就是 key 串，jump 与 CH 之间不再有第二次转换
        assertEquals(container.key(), profile.container().key());
    }

    private static UaClassifier shippedTable() {
        return new UaClassifier(shippedRaw("containers"), shippedRaw("devices"));
    }

    @SuppressWarnings("unchecked")
    private static List<UaFeature> shippedRaw(String dimension) {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> ua = (Map<String, Object>) path(root, "xsl", "jump", "ua");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) ua.get(dimension);
            List<UaFeature> out = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                out.add(new UaFeature((String) row.get("key"), (List<String>) row.get("tokens")));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("读不到 shipped 特征表（application.yml 的 xsl.jump.ua）", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> path(Map<String, Object> root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            cur = ((Map<String, Object>) cur).get(k);
        }
        return (Map<String, Object>) cur;
    }
}

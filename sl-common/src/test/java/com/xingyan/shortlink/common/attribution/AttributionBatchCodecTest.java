package com.xingyan.shortlink.common.attribution;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回传契约的地基：发送端与接收端必须逐字符算出同一个 checksum 与 batch_id。
 * 这里钉的是"什么会让 id 变、什么不会"——幂等与迟到覆盖两条语义都建立在这上面（DESIGN 7.3 ②⑤）。
 */
class AttributionBatchCodecTest {

    private static final LocalDateTime M1 = LocalDateTime.of(2026, 10, 11, 12, 34, 0);
    private static final LocalDateTime M2 = LocalDateTime.of(2026, 10, 11, 12, 35, 0);
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 11, 12, 32, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 11, 12, 36, 0);

    private static AttributionStat stat(String campaign, String channel, LocalDateTime minute,
                                        long pv, long uv, long valid, long bot) {
        return new AttributionStat(campaign, channel, minute, pv, uv, valid, bot);
    }

    @Test
    void itemsOrderDoesNotMatterButContentDoes() {
        List<AttributionStat> a = List.of(
                stat("cp-1", "ch-wechat", M2, 5, 4, 5, 0),
                stat("cp-1", "ch-douyin", M1, 9, 7, 8, 1));
        List<AttributionStat> b = List.of(
                stat("cp-1", "ch-douyin", M1, 9, 7, 8, 1),
                stat("cp-1", "ch-wechat", M2, 5, 4, 5, 0));
        assertEquals(AttributionBatchCodec.checksum(a), AttributionBatchCodec.checksum(b),
                "乱序不该变出另一个批次号——否则重投同一批内容会被判成新批次而重复计入");
        assertEquals(AttributionBatchCodec.batchId(1001, FROM, TO, a),
                AttributionBatchCodec.batchId(1001, FROM, TO, b));
    }

    @Test
    void everyValueThatShouldMatterChangesTheId() {
        List<AttributionStat> base = List.of(stat("cp-1", "ch-wechat", M1, 9, 7, 8, 1));
        String id = AttributionBatchCodec.batchId(1001, FROM, TO, base);
        assertNotEquals(id, AttributionBatchCodec.batchId(1001, FROM, TO,
                List.of(stat("cp-1", "ch-wechat", M1, 10, 7, 8, 1))), "pv 变必须变 id（迟到补全就是这一类）");
        assertNotEquals(id, AttributionBatchCodec.batchId(1001, FROM, TO,
                List.of(stat("cp-1", "ch-wechat", M1, 9, 7, 9, 1))), "valid_pv 同理");
        assertNotEquals(id, AttributionBatchCodec.batchId(1001, FROM, TO,
                List.of(stat("cp-2", "ch-wechat", M1, 9, 7, 8, 1))), "campaign 换了就是另一批事实");
        assertNotEquals(id, AttributionBatchCodec.batchId(1001, FROM.plusMinutes(1), TO.plusMinutes(1), base),
                "窗口不同不能共用批次号");
        assertNotEquals(id, AttributionBatchCodec.batchId(1002, FROM, TO, base), "租户不同同理");
    }

    @Test
    void batchIdFitsTheColumnAndReadsWell() {
        String id = AttributionBatchCodec.batchId(1001, FROM, TO,
                List.of(stat("cp-1", "ch-wechat", M1, 9, 7, 8, 1)));
        assertTrue(id.startsWith("xsl-1001-202610111232-202610111236-"), id);
        // xsl_mock.attribution_batch.batch_id 是 VARCHAR(64)：超长会在插入时被截断或报错（严格模式下报错），
        // 而这条断言就是防止以后有人往 id 里再加一段而把它撑爆
        assertTrue(id.length() <= 64, "batch_id 长度 " + id.length() + " 超出列宽 64");
    }

    @Test
    void checksumIsLowercaseSha1Hex() {
        String sum = AttributionBatchCodec.checksum(List.of(stat("cp", "ch", M1, 1, 1, 1, 0)));
        assertTrue(sum.matches("^[0-9a-f]{40}$"), sum);
    }

    @Test
    void canonicalUsesUtcEpochForMinutes() {
        // 分钟要落成 epoch 秒而不是格式化串：两侧时区或秒位省略都会让同一个桶算出两个 id（假重复投递）
        String canonical = AttributionBatchCodec.canonical(List.of(stat("cp", "ch", M1, 1, 1, 1, 0)));
        assertEquals("cp|ch|" + M1.toEpochSecond(java.time.ZoneOffset.UTC) + "|1|1|1|0", canonical);
    }

    @Test
    void emptyItemsStillProduceAStableDigest() {
        assertEquals(AttributionBatchCodec.checksum(List.of()), AttributionBatchCodec.checksum(List.of()));
        assertEquals("", AttributionBatchCodec.canonical(null));
    }
}

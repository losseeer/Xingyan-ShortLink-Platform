package com.xingyan.shortlink.common.attribution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 归因回传的「规范化串 → checksum → batch_id」算法（DESIGN 7.3，M3-03）。
 *
 * <p>放 sl-common 是因为<b>发送端（sl-consumer）与接收端（sl-mock）必须逐字符算出同一个串</b>：
 * 两份实现漂了，症状是接收端把每一个批次都判成"校验和 mismatch"然后 400 拒收——回传看起来在跑，
 * 实际上一个数都没落地。这和 {@code RouteCacheContract}、UA 词汇表是同一类跨端契约。
 *
 * <p>三处细节是刻意的，改动前先想清楚：
 * ① 排序键是 {@code (campaign, channel, minute)}，与 checksum 用同一顺序——顺序就是内容的一部分；
 * ② minute 参与拼接时用 <b>UTC 偏移的 epoch 秒</b>而不是格式化串，避免两侧时区/秒位省略造成的假差异；
 * ③ 用 SHA-1 而不是取密码学强度：这里的目的是<b>传输完整性</b>（数没数错、有没有截断），
 *    身份与防篡改由 HMAC-SHA256 签名那一层负责（DESIGN 9.4）。
 */
public final class AttributionBatchCodec {

    /** batch_id 里窗口两端用的可读格式（不含时区，纯格式化，因此两侧一致即可稳定）。 */
    private static final DateTimeFormatter WINDOW = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private AttributionBatchCodec() {
    }

    /** 规范化 items：按 (campaign_id, channel_id, minute) 升序，每行 7 字段用 | 分隔，行间用 , 分隔。 */
    public static String canonical(List<AttributionStat> items) {
        List<AttributionStat> sorted = new ArrayList<>(items == null ? List.of() : items);
        sorted.sort(Comparator.comparing(AttributionStat::campaignId)
                .thenComparing(AttributionStat::channelId)
                .thenComparing(AttributionStat::minute));
        StringBuilder sb = new StringBuilder(sorted.size() * 48);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            AttributionStat s = sorted.get(i);
            sb.append(nz(s.campaignId())).append('|')
              .append(nz(s.channelId())).append('|')
              .append(s.minute().toEpochSecond(ZoneOffset.UTC)).append('|')
              .append(s.pv()).append('|')
              .append(s.uv()).append('|')
              .append(s.validPv()).append('|')
              .append(s.botPv());
        }
        return sb.toString();
    }

    /** 校验和：sha1(canonical)，40 位小写 hex。 */
    public static String checksum(List<AttributionStat> items) {
        return sha1Hex(canonical(items));
    }

    /**
     * 内容寻址的批次号：{@code xsl-{tenant}-{from}-{to}-{sha1 前 10 位}}。
     *
     * <p>为什么 id 里要带内容 hash 而不是只用窗口：同一分钟桶的数会因迟到而变全。纯窗口 id 会让
     * 「更全的那一版」被当成重复投递丢掉；带上内容后，重投（内容没变）id 相同被幂等吸收，
     * 补投（内容变了）id 不同、走按桶覆盖更新（DESIGN 6.3-3）。
     */
    public static String batchId(long tenantId, LocalDateTime from, LocalDateTime to, List<AttributionStat> items) {
        String hash = checksum(items);
        return "xsl-" + tenantId + "-" + from.format(WINDOW) + "-" + to.format(WINDOW) + "-" + hash.substring(0, 10);
    }

    public static String sha1Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

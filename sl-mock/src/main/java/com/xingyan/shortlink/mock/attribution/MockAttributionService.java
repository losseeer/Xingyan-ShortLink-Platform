package com.xingyan.shortlink.mock.attribution;

import com.xingyan.shortlink.common.attribution.AttributionBatchCodec;
import com.xingyan.shortlink.common.attribution.AttributionStat;
import com.xingyan.shortlink.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * mock 宣发平台的接收判定（DESIGN 7.3 五条契约的实现，M3-03）。
 *
 * <p>顺序是刻意排的：<b>先重算、再幂等、最后落库</b>。校验和/batch_id 都在服务端用
 * {@link AttributionBatchCodec} 从 items 重新算出来，发送端给的串只用来比对——外部系统的声明
 * 不当事实用。checksum 不符直接 400 且<em>台账里不留这一行</em>：一个坏批次如果在台账里留下记录，
 * 事后看台账会以为"这段数据收到过"，而那恰恰是没收到的。
 */
@Service
public class MockAttributionService {

    private static final Logger log = LoggerFactory.getLogger(MockAttributionService.class);

    public enum Verdict {ACCEPTED, DUPLICATED}

    public record Outcome(Verdict verdict, String batchId, int itemCount) {
    }

    private final JdbcTemplate jdbc;
    private final long tenantId;

    public MockAttributionService(JdbcTemplate jdbc, @Value("${xsl.mock.tenant-id:1001}") long tenantId) {
        this.jdbc = jdbc;
        this.tenantId = tenantId;
    }

    /** 这把 api key 属于哪个租户——信封里的 tenant_id 必须与它一致（DESIGN 7.3 ①）。 */
    public long tenantId() {
        return tenantId;
    }

    @Transactional
    public Outcome ingest(AttributionBatch batch) {
        List<AttributionStat> items = batch.items() == null ? List.of() : batch.items();
        if (items.isEmpty()) {
            throw reject(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "items 为空：没有内容的批次不该发出来");
        }
        if (!batch.from().isBefore(batch.to())) {
            throw reject(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "窗口不合法：from 必须早于 to");
        }
        String checksum = AttributionBatchCodec.checksum(items);
        if (!checksum.equals(batch.checksum())) {
            throw reject(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "checksum 不符：按 items 重算是 " + checksum + "，发来的是 " + batch.checksum());
        }
        String batchId = AttributionBatchCodec.batchId(batch.tenantId(), batch.from(), batch.to(), items);
        if (!batchId.equals(batch.batchId())) {
            throw reject(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "batch_id 与内容不符：按内容应是 " + batchId + "，发来的是 " + batch.batchId());
        }
        for (AttributionStat s : items) {
            if (s.minute().isBefore(batch.from()) || !s.minute().isBefore(batch.to())) {
                throw reject(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                        "桶 " + s.minute() + " 落在信封窗口 [" + batch.from() + ", " + batch.to() + ") 之外");
            }
        }

        // MySQL 的受影响行数在这里当幂等信号用：1 = 新批次插入成功，2 = 同 batch_id 已存在（只加了 times_received）
        int touched = jdbc.update("INSERT INTO attribution_batch (batch_id, tenant_id, window_from, window_to, item_count, checksum) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE times_received = times_received + 1",
                batchId, batch.tenantId(), batch.from(), batch.to(), items.size(), checksum);
        if (touched == 2) {
            log.info("[mock] 重复批次被幂等吸收 batch_id={} items={}", batchId, items.size());
            return new Outcome(Verdict.DUPLICATED, batchId, items.size());
        }

        for (AttributionStat s : items) {
            // 按桶覆盖而不是累加：迟到变全后的新一版会以另一个 batch_id 再进来，覆盖才是"这一桶更准了"
            jdbc.update("INSERT INTO attribution_stat (tenant_id, campaign_id, channel_id, minute, pv, uv, valid_pv, bot_pv, batch_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) AS new "
                            + "ON DUPLICATE KEY UPDATE pv = new.pv, uv = new.uv, valid_pv = new.valid_pv, "
                            + "bot_pv = new.bot_pv, batch_id = new.batch_id",
                    batch.tenantId(), s.campaignId(), s.channelId(), s.minute(),
                    s.pv(), s.uv(), s.validPv(), s.botPv(), batchId);
        }
        log.info("[mock] 批次已入账 batch_id={} items={}", batchId, items.size());
        return new Outcome(Verdict.ACCEPTED, batchId, items.size());
    }

    private static MockRejectException reject(HttpStatus status, ErrorCode code, String message) {
        return new MockRejectException(status, code, message);
    }
}

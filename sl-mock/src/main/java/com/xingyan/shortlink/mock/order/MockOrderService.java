package com.xingyan.shortlink.mock.order;

import com.xingyan.shortlink.common.error.ErrorCode;
import com.xingyan.shortlink.mock.MockRejectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 假订单落库（DESIGN 附录 B，M3-04）。
 *
 * <p>只有一件事值得单独说：{@code order_id} 是**幂等键**，不是普通列。购票侧"点了没成再点一次"
 * 会拿同一个订单号重发，此时必须返回已有记录而不是再插一行——否则订单数虚高，端到端对账 ②
 * 的分母当场失真，而且错得很难看（转化率莫名掉一截，没人会怀疑到"提交两次"上）。
 * {@code times_submitted} 把这次吸收留在台账里可查。
 */
@Service
public class MockOrderService {

    private static final Logger log = LoggerFactory.getLogger(MockOrderService.class);

    public enum Verdict {CREATED, DUPLICATED}

    public record Outcome(Verdict verdict, String orderId, int timesSubmitted) {
    }

    private final JdbcTemplate jdbc;

    public MockOrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Outcome ingest(MockOrder order) {
        String orderId = trim(order.orderId());
        String clickId = trim(order.clickId());
        if (orderId.isEmpty()) {
            throw reject("order_id 必填——它就是幂等键");
        }
        if (clickId.isEmpty()) {
            throw reject("click_id 必填（附录 B 的对账键，没有它这单在点击侧认不回来）");
        }
        // MySQL 的受影响行数：1 = 新订单插入；2 = 同 order_id 已存在，只把 times_submitted 加了一
        int touched = jdbc.update("INSERT INTO mock_order (order_id, click_id, channel_id, campaign_id, promoter_id, "
                        + "first_click_time, last_click_time) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE times_submitted = times_submitted + 1",
                orderId, clickId, orEmpty(order.channelId()), orEmpty(order.campaignId()), orEmpty(order.promoterId()),
                order.firstClickTime(), order.lastClickTime());
        Integer times = jdbc.queryForObject("SELECT times_submitted FROM mock_order WHERE order_id = ?",
                Integer.class, orderId);
        int submitted = times == null ? 1 : times;
        if (touched == 1) {
            log.info("[mock] 订单入账 order_id={} click_id={}", orderId, clickId);
            return new Outcome(Verdict.CREATED, orderId, submitted);
        }
        log.info("[mock] 重复订单被吸收 order_id={} 第 {} 次提交", orderId, submitted);
        return new Outcome(Verdict.DUPLICATED, orderId, submitted);
    }

    private static MockRejectException reject(String message) {
        return new MockRejectException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, message);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}

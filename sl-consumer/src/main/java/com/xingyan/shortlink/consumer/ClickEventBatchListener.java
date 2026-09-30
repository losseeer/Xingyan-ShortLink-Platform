package com.xingyan.shortlink.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.event.ClickEvent;
import com.xingyan.shortlink.consumer.clickhouse.ClickHouseWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量消费 → ClickHouse（DESIGN 6.3）：手动提交 offset，写失败抛出重试（at-least-once），
 * 重复由 event_id + ReplacingMergeTree 幂等消化。
 */
@Component
public class ClickEventBatchListener {

    private static final Logger log = LoggerFactory.getLogger(ClickEventBatchListener.class);

    private final ClickHouseWriter writer;
    private final ObjectMapper mapper;
    private final Counter consumed;
    private final Counter dropped;

    public ClickEventBatchListener(ClickHouseWriter writer, ObjectMapper mapper, MeterRegistry registry) {
        this.writer = writer;
        this.mapper = mapper;
        this.consumed = registry.counter("xsl_consumer_events_written_total");
        this.dropped = registry.counter("xsl_consumer_events_dropped_total");
    }

    @KafkaListener(topics = "${xsl.stats.click-topic:shortlink-click}",
            groupId = "${spring.kafka.consumer.group-id:sl-consumer}",
            batch = "true")
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) throws Exception {
        List<ClickEvent> batch = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> r : records) {
            try {
                batch.add(mapper.readValue(r.value(), ClickEvent.class));
            } catch (Exception e) {
                dropped.increment();
                log.error("[consume] 脏消息丢弃 offset={}", r.offset());
            }
        }
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                writer.writeBatch(batch);
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                Thread.sleep(500L * attempt);
            }
        }
        if (last != null) {
            throw last; // 不提交 offset，Kafka 重投
        }
        consumed.increment(batch.size());
        ack.acknowledge();
    }
}

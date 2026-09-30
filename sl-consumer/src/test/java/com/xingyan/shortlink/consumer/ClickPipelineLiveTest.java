package com.xingyan.shortlink.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.xingyan.shortlink.common.event.ClickEvent;
import com.xingyan.shortlink.consumer.clickhouse.ClickHouseWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M1-09 验收（活体 infra，不可达则跳过）：Kafka→批量消费→ClickHouse 落库计数；
 * 同 event_id 重复投递被 ReplacingMergeTree 幂等消化（count FINAL 不增）。隔离码 m109itest。
 */
class ClickPipelineLiveTest {

    private static final String TOPIC = "shortlink-click";
    private static final String CODE = "m109" + Long.toString(System.nanoTime() % 100_000_000L, 36) + "x";
    private static ClickHouseWriter ch;

    @BeforeAll
    static void up() {
        assumeTrue(infraUp());
        ch = new ClickHouseWriter("http://localhost:8123/", "xsl_app",
                System.getenv().getOrDefault("XSL_CH_PASSWORD", ""), "xsl");
    }

    @AfterAll
    static void clean() throws Exception {
        if (ch != null && infraUp()) {
            ch.execute("ALTER TABLE xsl.click_event DELETE WHERE short_code = '" + CODE + "'");
        }
    }

    private static boolean portOpen(String host, int p) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, p), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean infraUp() {
        return portOpen("127.0.0.1", 9094) && portOpen("127.0.0.1", 8123);
    }

    private static ClickEvent event(String eventId, long ts) {
        ClickEvent e = new ClickEvent();
        e.setEventId(eventId);
        e.setShortCode(CODE);
        e.setClickTime(ts);
        e.setIpHash("hash-" + eventId);
        e.setChannelId("ch-1");
        e.setCampaignId("cm-1");
        e.setTenantId(1001);
        e.setUtmParams("{}");
        return e;
    }

    @Test
    void kafkaToClickHouseDedupsByEventId() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String group = "m109itest-" + UUID.randomUUID();
        Properties pc = new Properties();
        pc.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9094");
        pc.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        pc.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        Properties cc = new Properties();
        cc.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9094");
        cc.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        cc.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cc.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cc.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cc.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);

        String idA = UUID.randomUUID().toString(), idB = UUID.randomUUID().toString();
        long base = System.currentTimeMillis() / 1000 - 3600;

        try (KafkaProducer<String, String> p = new KafkaProducer<>(pc);
             KafkaConsumer<String, String> c = new KafkaConsumer<>(cc)) {
            c.subscribe(List.of(TOPIC));
            // 同一事件投递两次（模拟 at-least-once 重复），另加一条正常事件
            p.send(new ProducerRecord<>(TOPIC, CODE, mapper.writeValueAsString(event(idA, base)))).get();
            p.send(new ProducerRecord<>(TOPIC, CODE, mapper.writeValueAsString(event(idA, base)))).get();
            p.send(new ProducerRecord<>(TOPIC, CODE, mapper.writeValueAsString(event(idB, base + 1)))).get();
            p.flush();

            ClickEventBatchListener listener = new ClickEventBatchListener(
                    ch, mapper, new SimpleMeterRegistry());
            List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records = List.of();
            long deadline = System.currentTimeMillis() + 20_000;
            while (records.size() < 3 && System.currentTimeMillis() < deadline) {
                records = java.util.stream.StreamSupport
                        .stream(c.poll(Duration.ofSeconds(1)).spliterator(), false)
                        .filter(r -> CODE.equals(r.key()))
                        .collect(java.util.stream.Collectors.toList());
            }
            assertEquals(3, records.size(), "Kafka 应收到本测试 3 条投递");
            Acknowledgment ack = () -> { };
            listener.onBatch(records, ack);
        }

        long count = ch.countByCode(CODE);
        assertEquals(2, count, "重复 event_id 被幂等消化，FINAL 计数恰为 2");
    }
}

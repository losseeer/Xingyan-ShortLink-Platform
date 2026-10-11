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
import java.util.ArrayList;
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
        // M3-01 的对账键：跳转 URL 上的 xy_click_id，订单侧带回的就是它
        e.setClickId("c-" + eventId);
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
        cc.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        // 不开 auto.commit，也不从 earliest 起爬：早先版本要越过 topic 的全部历史消息才拿得到
        // 自己那 3 条，积压涨到几百万条后 20s 内跑不完（表现为"Kafka 应收到 3 条投递 expected 3 was 0"）。
        cc.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        String idA = UUID.randomUUID().toString(), idB = UUID.randomUUID().toString();
        long base = System.currentTimeMillis() / 1000 - 3600;

        try (KafkaProducer<String, String> p = new KafkaProducer<>(pc);
             KafkaConsumer<String, String> c = new KafkaConsumer<>(cc)) {
            // 同一事件投递两次（模拟 at-least-once 重复），另加一条正常事件。
            // 先发、从 RecordMetadata 拿分区与位点：不猜分区（M2-22 加分区后也不受影响），
            // 也不依赖 topic 既有体量。
            var m1 = p.send(new ProducerRecord<>(TOPIC, CODE, mapper.writeValueAsString(event(idA, base)))).get();
            var m2 = p.send(new ProducerRecord<>(TOPIC, CODE, mapper.writeValueAsString(event(idA, base)))).get();
            var m3 = p.send(new ProducerRecord<>(TOPIC, CODE, mapper.writeValueAsString(event(idB, base + 1)))).get();
            p.flush();
            var tp = new org.apache.kafka.common.TopicPartition(m1.topic(), m1.partition());
            long first = Math.min(Math.min(m1.offset(), m2.offset()), m3.offset());

            c.assign(List.of(tp));
            c.seek(tp, first);
            List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> mine = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 20_000;
            while (mine.size() < 3 && System.currentTimeMillis() < deadline) {
                for (var r : c.poll(Duration.ofSeconds(1))) {
                    if (CODE.equals(r.key())) mine.add(r);
                }
            }
            assertEquals(3, mine.size(), "Kafka 应收到本测试 3 条投递（" + tp + " @" + first + "）");
            ClickEventBatchListener listener = new ClickEventBatchListener(
                    ch, mapper, new SimpleMeterRegistry());
            Acknowledgment ack = () -> { };
            listener.onBatch(mine, ack);
        }

        long count = ch.countByCode(CODE);
        assertEquals(2, count, "重复 event_id 被幂等消化，FINAL 计数恰为 2");

        // 对账键必须与事件一起落库（缺这一步，M3 的订单↔点击对账就没有 join 键）
        String landed = ch.scalar("SELECT groupArray(click_id) FROM (SELECT DISTINCT click_id FROM "
                + "xsl.click_event FINAL WHERE short_code = '" + CODE + "')");
        assertTrue(landed.contains("c-" + idA) && landed.contains("c-" + idB),
                "click_id 应随事件落库，实际读到 " + landed);
    }
}

package com.xingyan.shortlink.jump.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.event.ClickEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * ClickEvent 投递（DESIGN 6.3）：异步 send，分区键 short_code，失败落 WAL 不阻塞跳转响应；
 * 定时重放 WAL（at-least-once，重复由 event_id + CH ReplacingMergeTree 幂等消化）。
 */
@Component
public class ClickEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventProducer.class);

    private final KafkaTemplate<String, String> kafka;
    private final WalQueue wal;
    private final ObjectMapper mapper;
    private final String topic;
    private final Counter sent;
    private final Counter toWal;
    private final Counter replayed;
    private final Counter replayFailed;

    public ClickEventProducer(KafkaTemplate<String, String> kafka, WalQueue wal, ObjectMapper mapper,
                              MeterRegistry registry,
                              @Value("${xsl.jump.click-topic:shortlink-click}") String topic) {
        this.kafka = kafka;
        this.wal = wal;
        this.mapper = mapper;
        this.topic = topic;
        this.sent = registry.counter("xsl_jump_clickevent_total", "result", "sent");
        this.toWal = registry.counter("xsl_jump_clickevent_total", "result", "wal");
        this.replayed = registry.counter("xsl_jump_wal_replayed_total");
        this.replayFailed = registry.counter("xsl_jump_wal_replay_failed_total");
        registry.gauge("xsl_jump_wal_backlog_bytes", wal, WalQueue::backlogBytes);
    }

    public void publish(ClickEvent event) {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("[click] ClickEvent 序列化失败 code={}", event.getShortCode(), e);
            toWal.increment();
            wal.append("{\"_serialize_error\":\"" + event.getEventId() + "\"}");
            return;
        }
        sendLine(json, event.getShortCode(), false);
    }

    private void sendLine(String json, String key, boolean isReplay) {
        try {
            kafka.send(new ProducerRecord<>(topic, key, json)).whenComplete((r, ex) -> {
                if (ex != null) {
                    if (isReplay) {
                        replayFailed.increment();
                    } else {
                        toWal.increment();
                    }
                    wal.append(json);
                } else {
                    (isReplay ? replayed : sent).increment();
                }
            });
        } catch (Exception e) {
            // send() 同步抛错（broker 元数据不可达/max.block 超时）
            if (isReplay) replayFailed.increment(); else toWal.increment();
            wal.append(json);
        }
    }

    @Scheduled(fixedDelayString = "${xsl.jump.wal-replay-ms:15000}", initialDelay = 5000)
    @EventListener(ApplicationReadyEvent.class)
    public void replayWal() {
        for (Path f : wal.pendingFiles()) {
            for (String line : wal.take(f)) {
                sendLine(line, replayKey(line), true);
            }
            log.info("[wal] 重放文件完成 {}", f.getFileName());
        }
    }

    private String replayKey(String json) {
        try {
            return mapper.readTree(json).path("short_code").asText("");
        } catch (Exception e) {
            return null;
        }
    }
}

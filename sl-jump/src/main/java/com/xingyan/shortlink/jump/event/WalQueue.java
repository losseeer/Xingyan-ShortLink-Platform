package com.xingyan.shortlink.jump.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ClickEvent 本地磁盘重试队列（DESIGN 6.3/8.3 WAL 兜底）：
 * Kafka 发送失败的原始 JSON 按天落 JSONL；重放成功后整文件删除。
 * M1 简化：不做逐行回写，重放中的再失败由生产者回调重新 append。
 */
@Component
public class WalQueue {

    private static final Logger log = LoggerFactory.getLogger(WalQueue.class);

    private final Path dir;

    public WalQueue(@Value("${xsl.jump.wal-dir:./data/jump-wal}") String dir) {
        this.dir = Path.of(dir);
    }

    public synchronized void append(String json) {
        try {
            Files.createDirectories(dir);
            Path f = today();
            if (!Files.exists(f)) {
                log.warn("[wal] 新建 WAL 文件 {}", f.getFileName());
            }
            Files.writeString(f, json + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("[wal] WAL 落盘失败，事件丢失: {}", json, e);
        }
    }

    /** 待重放文件（含当天：take 为同步摘取+删除，再失败由回调重新 append，与 append 互斥）。 */
    public synchronized List<Path> pendingFiles() {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("click-event-")
                            && p.getFileName().toString().endsWith(".log"))
                    .sorted().collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    public synchronized List<String> take(Path file) {
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file));
            Files.delete(file);
            return lines.stream().filter(l -> !l.isBlank()).collect(Collectors.toList());
        } catch (IOException e) {
            log.error("[wal] WAL 文件读取失败，跳过 {}", file, e);
            return List.of();
        }
    }

    private Path today() {
        return dir.resolve("click-event-" + LocalDate.now() + ".log");
    }

    public long backlogBytes() {
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return s.mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}

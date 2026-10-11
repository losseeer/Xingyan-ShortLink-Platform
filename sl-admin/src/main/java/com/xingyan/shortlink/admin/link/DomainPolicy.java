package com.xingyan.shortlink.admin.link;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生效域名的来源：`xsl_base.domain_pool`（DESIGN 9.5，M3-10）。
 *
 * <p>9.5 那句话此前是"表与切换机制完整实现"，而真实情况只有表——生效域是一个环境变量，没有一行代码读那张表。
 * 这里补的就是"读表选域"这一半：{@code status=0 → weight DESC → domain ASC}。
 * 平手必须按域名字典序排死：seed 里三个域 weight 都是 100，不定死的话"现在生效的是哪个域"就是随机事件，
 * 演练无法断言，而一条被随机选中的域名出了问题，运维连"它什么时候开始生效"都答不上来。
 *
 * <p><b>为什么单开一条 JDBC 连接而不走 ShardingSphere</b>：{@code xsl_base} 是不分片的字典库
 * （{@code tenant}/{@code domain_pool} 都在里面），把它塞进分片规则只是给一张三行的表加一层路由；
 * 而 ShardingSphere 的 {@code ds0/ds1} 各自只连 {@code xsl_00/xsl_01}，跨库限定名能不能透传取决于它内部的
 * schema 解析——为一件不需要分片的事去验证那条路径不划算。sl-mock 的独立库是同一个先例。
 * 数据源<b>刻意不注册成 bean</b>：Boot 的 {@code JdbcTemplateAutoConfiguration} 一见容器里有
 * {@code JdbcOperations} 就退让，那样分片主库的 JdbcTemplate 反而会消失，全管理面跟着起不来。
 *
 * <p>读不到就退回环境变量并**计数**：静默退回会让"封禁切换"看起来生效了其实没有，
 * 那是这里最坏的失败形态（{@code xsl_domain_pool_total{result=fallback}}）。
 */
@Component
public class DomainPolicy {

    private static final Logger log = LoggerFactory.getLogger(DomainPolicy.class);
    private static final String SELECT =
            "SELECT domain FROM domain_pool WHERE status = 0 ORDER BY weight DESC, domain ASC LIMIT 1";

    private final HikariDataSource ds;          // 可能为 null：池被关掉时就不建连接
    private final JdbcTemplate jdbc;
    private final String fallbackDomain;
    private final long ttlMillis;
    private final MeterRegistry registry;

    private volatile String cached;
    private volatile long cachedAt;

    public DomainPolicy(@Value("${xsl.domain-pool.enabled:true}") boolean enabled,
                        @Value("${XSL_MYSQL_HOST:127.0.0.1}") String host,
                        @Value("${XSL_MYSQL_PORT:3307}") int port,
                        @Value("${XSL_MYSQL_USER:root}") String user,
                        @Value("${XSL_MYSQL_PASSWORD:}") String password,
                        @Value("${xsl.link.base-domain:xy1.test}") String fallbackDomain,
                        @Value("${xsl.domain-pool.refresh-seconds:30}") int refreshSeconds,
                        MeterRegistry registry) {
        this.fallbackDomain = fallbackDomain;
        this.ttlMillis = Math.max(1, refreshSeconds) * 1000L;
        this.registry = registry;
        this.cached = fallbackDomain;                    // 第一次读之前先有个能用的值，别让创建链接卡在选域上
        this.cachedAt = 0L;
        if (!enabled) {
            this.ds = null;
            this.jdbc = null;
            log.info("[domain] 选域池已关闭（XSL_DOMAIN_POOL=false），生效域固定为 {}", fallbackDomain);
            return;
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("xsl-base-db");
        cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/xsl_base"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                + "&connectTimeout=2000&socketTimeout=3000");
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        // Hikari 等一条连接的默认上限是 30s。字典库一旦不可达，那会把"创建链接"这个请求整条拖住 30 秒
        // ——比退回兜底域名糟得多，所以压到 1.5s：读不到就用上一次的值 + 计数，30s 后再试。
        cfg.setConnectionTimeout(1500);
        // 字典库暂时不可达不该让管理面起不来：连接失败留给第一次读时兜底
        cfg.setInitializationFailTimeout(-1);
        this.ds = new HikariDataSource(cfg);
        this.jdbc = new JdbcTemplate(ds);
    }

    /** 当前生效域。TTL 内用缓存——这条路径只在创建链接时走，跳转侧一次数据库往返都不加。 */
    public String effective() {
        if (jdbc == null) {
            count("disabled");
            return fallbackDomain;
        }
        long now = System.currentTimeMillis();
        if (now - cachedAt < ttlMillis && cached != null) {
            count("pool");
            return cached;
        }
        try {
            List<String> rows = jdbc.queryForList(SELECT, String.class);
            if (rows.isEmpty()) {
                // 表里一个可用域都没有（全被封禁）：这不是"读失败"，退回环境变量继续发码比拒绝创建更糟吗？
                // 是更糟——发出去的码会落在一个可能已被封的域上。所以照旧兜底但把状态记成 empty_pool 让人看得见。
                count("empty_pool");
                log.warn("[domain] domain_pool 里没有 status=0 的域，退回配置的生效域 {}（该切域或补池了）", fallbackDomain);
                touch(fallbackDomain);
                return fallbackDomain;
            }
            String picked = rows.get(0);
            if (!picked.equals(cached)) {
                log.info("[domain] 生效域切换：{} → {}", cached, picked);
            }
            count("pool");
            touch(picked);
            return picked;
        } catch (RuntimeException e) {
            count("fallback");
            log.warn("[domain] 读 domain_pool 失败，沿用上一次的值 {}：{}", cached, e.getMessage());
            return cached == null ? fallbackDomain : cached;
        }
    }

    /** 让下一次 effective() 真的去读表（演练与人工切换后用得着，不等 TTL）。 */
    public void invalidate() {
        cachedAt = 0L;
    }

    private void touch(String domain) {
        this.cached = domain;
        this.cachedAt = System.currentTimeMillis();
    }

    private void count(String result) {
        registry.counter("xsl_domain_pool_total", "result", result).increment();
    }

    @PreDestroy
    void close() {
        if (ds != null) {
            ds.close();
        }
    }
}

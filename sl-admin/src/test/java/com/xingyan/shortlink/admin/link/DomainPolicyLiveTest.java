package com.xingyan.shortlink.admin.link;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 生效域选源的活体验收（compose MySQL @3307 的 {@code xsl_base.domain_pool}，DESIGN 9.5，M3-10）。
 *
 * <p>盯的是两件"不测就会静默错"的事：<b>平手必须按字典序排死</b>（seed 里三个域 weight 都是 100，
 * 不排死就是随机，"现在生效的是哪个域"会变成不可复现的问题），以及<b>封禁只表示不再发新码</b>
 * （所以下一个域要能被选出来，而一个可用域都没有时必须仍有个值可用、不抛异常）。
 *
 * <p>每个用例都会把表恢复到 seed 的形状（{@code status=0/weight=100}）——它改的是本机共享数据字典，
 * 不复位就等于给下一个跑的人留下一个"不知道为什么域名变了"的坑。
 */
class DomainPolicyLiveTest {

    private static final String FALLBACK = "xy1.test";
    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;

    private DomainPolicy policy;

    @BeforeAll
    static void up() {
        assumeTrue(portOpen(3307), "MySQL 3307 不可达");
        String password = System.getenv("XSL_MYSQL_PASSWORD");
        assumeTrue(password != null && !password.isBlank(), "缺 XSL_MYSQL_PASSWORD（make verify 会从 .env 注入）");
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setJdbcUrl("jdbc:mysql://127.0.0.1:3307/xsl_base?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai");
        cfg.setUsername("root");
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);
        jdbc = new JdbcTemplate(ds);
        assumeTrue(Integer.valueOf(3).equals(jdbc.queryForObject(
                "SELECT count(*) FROM domain_pool", Integer.class)), "domain_pool 该有 seed 的三个域");
    }

    @AfterAll
    static void down() {
        if (ds != null) {
            ds.close();
        }
    }

    @BeforeEach
    void seedShape() {
        restoreSeedShape();
        // ttl 给 1 秒：用例里靠 invalidate() 强制重读，不靠等 TTL——等 TTL 会让测试变成计时器
        policy = new DomainPolicy(true, "127.0.0.1", 3307, "root", envPassword(), FALLBACK, 1,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void restore() {
        restoreSeedShape();
    }

    private static void restoreSeedShape() {
        jdbc.update("UPDATE domain_pool SET status = 0, weight = 100, switch_time = NULL");
    }

    private static String envPassword() {
        String p = System.getenv("XSL_MYSQL_PASSWORD");
        return p == null ? "" : p;
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void tiesAreBrokenByDictionaryOrderAndAreStable() {
        assertEquals(FALLBACK, policy.effective(), "三个域 weight 都是 100 ⇒ 取字典序最小的 xy1.test");
        policy.invalidate();
        assertEquals(policy.effective(), policy.effective(), "同一张表两次选择必须给同一个域，否则「切到了哪个域」不可断言");
    }

    @Test
    void bannedDomainFallsToTheNextAvailableOne() {
        jdbc.update("UPDATE domain_pool SET status = 2, switch_time = NOW() WHERE domain = 'xy1.test'");
        policy.invalidate();
        assertEquals("xy2.test", policy.effective(), "旧域被封不该把发码一起停掉：换下一个可用域");
    }

    @Test
    void weightOutranksDictionaryOrder() {
        jdbc.update("UPDATE domain_pool SET weight = 500 WHERE domain = 'xy3.test'");
        policy.invalidate();
        assertEquals("xy3.test", policy.effective(), "weight 优先于字典序（运维要能把某个域顶到前面）");
    }

    @Test
    void emptyPoolStillReturnsSomethingUsable() {
        jdbc.update("UPDATE domain_pool SET status = 2");
        policy.invalidate();
        String picked = policy.effective();
        assertNotNull(picked, "一个可用域都没有时也要有值可用（发出去的码落在哪比拒绝创建更重要）");
        assertEquals(FALLBACK, picked, "兜底就是配置里那个 base-domain，同时计数记成 empty_pool 让人看得见");
    }

    @Test
    void unreachablePoolKeepsTheLastKnownDomain() {
        DomainPolicy broken = new DomainPolicy(true, "127.0.0.1", 1, "root", "nope", FALLBACK, 0,
                new SimpleMeterRegistry());
        assertEquals(FALLBACK, broken.effective(), "字典库连不上时管理面必须照常能建链接（走兜底），不是 500");
    }
}

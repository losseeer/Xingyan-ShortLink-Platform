package com.xingyan.shortlink.admin.sharding;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M1-02 验收：ShardingSphere-JDBC 单实例两库（xsl_00/xsl_01）哈希路由正确性（DESIGN 4.1）。
 * 依赖本机 compose MySQL，不可达则跳过（不阻塞 CI 语义验证）。
 */
class ShardingRoutingVerificationTest {

    private static final String SS_URL = "jdbc:shardingsphere:classpath:sharding.yaml";
    private static DataSource shardingDs;

    @BeforeAll
    static void up() throws SQLException {
        assumeTrue(mysqlReachable(), "本机 compose MySQL(127.0.0.1:3307) 不可达，跳过分片路由验证");
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.apache.shardingsphere.driver.ShardingSphereDriver");
        ds.setJdbcUrl(SS_URL);
        ds.setMaximumPoolSize(4);
        shardingDs = ds;
    }

    @AfterAll
    static void down() throws SQLException {
        if (shardingDs instanceof HikariDataSource hikari && !hikari.isClosed()) {
            hikari.close();
        }
    }

    private static boolean mysqlReachable() {
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true", "root", "xsl-dev")) {
            return c.isValid(2);
        } catch (Exception e) {
            System.err.println("[sharding-spike] MySQL 探测失败: " + e);
            return false;
        }
    }

    @Test
    void tenantIdShardsRowsDeterministically() throws SQLException {
        // INLINE 算法 ds$->{tenant_id % 2}：1001→ds1(xsl_01)，1002→ds0(xsl_00)
        try (Connection conn = shardingDs.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM short_link WHERE tenant_id IN (1001,1002)");
            st.executeUpdate("INSERT INTO short_link (id, short_code, origin_url, tenant_id) "
                    + "VALUES (9000001,'spike1001','https://m.xingyan.com/spike',1001)");
            st.executeUpdate("INSERT INTO short_link (id, short_code, origin_url, tenant_id) "
                    + "VALUES (9000002,'spike1002','https://m.xingyan.com/spike',1002)");
            try (ResultSet rs = st.executeQuery("SELECT short_code FROM short_link WHERE tenant_id=1001")) {
                assertTrue(rs.next());
                assertEquals("spike1001", rs.getString(1));
            }

            assertEquals(1, physicalCount("xsl_01", "short_link", "tenant_id", "1001"), "1001 应落 xsl_01");
            assertEquals(0, physicalCount("xsl_00", "short_link", "tenant_id", "1001"), "1001 不应出现在 xsl_00");
            assertEquals(1, physicalCount("xsl_00", "short_link", "tenant_id", "1002"), "1002 应落 xsl_00");
            assertEquals(0, physicalCount("xsl_01", "short_link", "tenant_id", "1002"), "1002 不应出现在 xsl_01");

            st.executeUpdate("DELETE FROM short_link WHERE tenant_id IN (1001,1002)");
        }
    }

    @Test
    void shortCodeShardKeepsJumpQuerySingleRouted() throws SQLException {
        // INLINE 算法 ds$->{Math.abs(short_code.hashCode()) % 2}：测试内重算期望库，断言精确落点
        try (Connection conn = shardingDs.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM link_route WHERE short_code IN ('spike1001','spike1002')");
            st.executeUpdate("INSERT INTO link_route (short_code, route_json, version) "
                    + "VALUES ('spike1001','{\"originUrl\":\"https://m.xingyan.com/spike\"}',1)");
            st.executeUpdate("INSERT INTO link_route (short_code, route_json, version) "
                    + "VALUES ('spike1002','{\"originUrl\":\"https://m.xingyan.com/spike\"}',1)");

            String expectedDb = String.format("xsl_%02d", Math.abs("spike1001".hashCode() % 2));
            String otherDb = String.format("xsl_%02d", 1 - Math.abs("spike1001".hashCode() % 2));
            assertEquals(1, physicalCount(expectedDb, "link_route", "short_code", "'spike1001'"),
                    "spike1001 应精确落 " + expectedDb);
            assertEquals(0, physicalCount(otherDb, "link_route", "short_code", "'spike1001'"),
                    "单码回源不广播：另一分片应为空");

            try (ResultSet rs = st.executeQuery("SELECT short_code FROM link_route WHERE short_code='spike1001'")) {
                assertTrue(rs.next(), "分片键点查必须命中");
            }
            int fullScan = 0;
            try (ResultSet rs = st.executeQuery("SELECT short_code FROM link_route WHERE short_code LIKE 'spike%'")) {
                while (rs.next()) fullScan++;
            }
            assertEquals(2, fullScan, "无分片键查询广播两库并正确归并");

            st.executeUpdate("DELETE FROM link_route WHERE short_code IN ('spike1001','spike1002')");
        }
    }

    private static int physicalCount(String db, String table, String column, String value) throws SQLException {
        String url = "jdbc:mysql://127.0.0.1:3307/" + db + "?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(url, "root", "xsl-dev");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=" + value)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}

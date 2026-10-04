package com.xingyan.shortlink.jump;

import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.UaClassifier;
import com.xingyan.shortlink.common.route.UaFeature;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(JumpConfig.UaProperties.class)
public class JumpConfig {

    /** xy_click_id 生成（与 admin 各自独立节点号，SecureRandom 取，冲突概率可忽略）。 */
    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator();
    }

    /**
     * UA 特征表（M2-32，DESIGN 6.2「特征表自维护 + 配置加载」）。
     * 表只在 {@code application.yml} 里有一份，代码里不留默认值——留了就变成"改配置没生效，
     * 因为实际跑的是代码里那份"，而这句话在启动日志里看不出来。
     */
    @ConfigurationProperties(prefix = "xsl.jump.ua")
    public record UaProperties(List<UaFeature> containers, List<UaFeature> devices) {
    }

    @Bean
    UaClassifier uaClassifier(UaProperties props) {
        // 表的合法性（key 在词汇表里、token 非空、不重复）由 UaClassifier 构造时校验：
        // 配错要在这里炸掉让服务起不来，而不是上线后一条路由规则静默不命中
        return new UaClassifier(props.containers(), props.devices());
    }
}

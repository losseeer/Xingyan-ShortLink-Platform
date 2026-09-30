package com.xingyan.shortlink.jump;

import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class JumpConfig {

    /** xy_click_id 生成（与 admin 各自独立节点号，SecureRandom 取，冲突概率可忽略）。 */
    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator();
    }
}

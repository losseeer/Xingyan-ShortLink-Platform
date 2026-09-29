package com.xingyan.shortlink.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 数据源经 ShardingSphere driver（spring.datasource.url=jdbc:shardingsphere:classpath:sharding.yaml），M1-05 短码池 DB 登记起生效。
@SpringBootApplication
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}

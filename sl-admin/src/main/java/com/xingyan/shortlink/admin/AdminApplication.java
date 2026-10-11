package com.xingyan.shortlink.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// 数据源经 ShardingSphere driver（spring.datasource.url=jdbc:shardingsphere:classpath:sharding.yaml），M1-05 短码池 DB 登记起生效。
@SpringBootApplication
// 管理面的两个 @Scheduled（配额对账 M2-12、outbox 补偿 M3-02）都由它点亮。原先它挂在
// AccessQuotaReconciler 上——第二个任务出现后这就变成"某任务的类掌管全局调度"，
// 那个类一旦被改动或删除，另一个任务会静默停摆（不报错，只是再也不跑）。
@EnableScheduling
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}

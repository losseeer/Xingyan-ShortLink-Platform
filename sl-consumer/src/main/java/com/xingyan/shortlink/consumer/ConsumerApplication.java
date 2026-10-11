package com.xingyan.shortlink.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// M3-03 起本模块多了第二个 @Scheduled（归因回传）。调度开关挂在应用类而不是任务类上——
// 与 sl-admin 在 M3-02 里的处理一致：某个任务类一旦被挪走，另一个任务不该静默停摆。
@SpringBootApplication
@EnableScheduling
public class ConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}

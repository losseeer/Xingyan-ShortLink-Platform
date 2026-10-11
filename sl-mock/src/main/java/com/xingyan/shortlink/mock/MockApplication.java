package com.xingyan.shortlink.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mock 宣发平台（DESIGN 7.3 外部契约的等价实现，M3-03 起进 reactor）。
 *
 * <p>它的身份是<b>外部系统</b>：不挂 nginx 对外域名、不经网关、不进业务分片库（自己的 `xsl_mock` 库），
 * 只从 compose 内网接收 sl-consumer 的归因回传。之所以要做校验和与幂等而不是"照单全收"，
 * 是为了让 M3-04 的端到端对账有一个真会拒收坏批次的对侧——否则"回传成功"只证明了请求发出去过。
 */
@SpringBootApplication
public class MockApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockApplication.class, args);
    }
}

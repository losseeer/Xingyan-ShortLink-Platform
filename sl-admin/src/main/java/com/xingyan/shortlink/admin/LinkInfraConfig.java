package com.xingyan.shortlink.admin;

import com.xingyan.shortlink.common.admission.UrlAdmissionChecker;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Configuration
public class LinkInfraConfig {

    @Bean
    UrlAdmissionChecker urlAdmissionChecker(@Value("${xsl.admission.allowed-hosts:}") String allowedHosts) {
        Set<String> hosts = new HashSet<>(Arrays.asList(allowedHosts.split("\\s*,\\s*")));
        hosts.remove("");
        return new UrlAdmissionChecker(hosts);
    }

    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator();
    }
}

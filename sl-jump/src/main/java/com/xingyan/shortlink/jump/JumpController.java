package com.xingyan.shortlink.jump;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 跳转入口（DESIGN 7.1）：/s/{code} 与裸 /{code} 同义；404/410/403 返回极简落地 HTML，
 * 自定义落地页模板留 M2 看板期。
 */
@RestController
public class JumpController {

    private final JumpResolver resolver;

    public JumpController(JumpResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping({"/s/{code}", "/{code:[A-Za-z0-9]+}"})
    public ResponseEntity<String> jump(@PathVariable String code, HttpServletRequest request) {
        JumpDecision decision = resolver.resolve(code, request);
        if (decision.status() == HttpStatus.FOUND) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(decision.location()))
                    .<String>build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(decision.status())
                .contentType(MediaType.TEXT_HTML);
        if (decision.retryAfterSeconds() > 0) {
            builder = builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        }
        return builder.body(landingPage(decision));
    }

    private static String landingPage(JumpDecision decision) {
        return "<!DOCTYPE html><html><body style='font-family:sans-serif;text-align:center;padding-top:20vh'>"
                + "<h2>" + decision.status().value() + "</h2><p>" + escape(decision.message()) + "</p></body></html>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

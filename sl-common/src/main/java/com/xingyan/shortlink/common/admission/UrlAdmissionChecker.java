package com.xingyan.shortlink.common.admission;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * origin_url / target_url 准入校验（DESIGN 9.2）：
 * 强制 https、禁 userinfo、禁显式端口、禁 IP/localhost 字面量、域名白名单
 * （精确域或 ".example.test" 前缀条目含子域）。命中拒绝原因返回文本，通过返回 null。
 */
public final class UrlAdmissionChecker {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final Set<String> allowedHosts;

    public UrlAdmissionChecker(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .filter(h -> !h.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** @return null=通过；否则为拒绝原因 */
    public String check(String url) {
        if (url == null || url.isBlank()) {
            return "origin_url is required";
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return "origin_url is not a valid URI: " + e.getReason();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)) {
            return "scheme must be https";
        }
        if (uri.getUserInfo() != null) {
            return "userinfo in URL is forbidden";
        }
        if (uri.getPort() != -1) {
            return "explicit port is forbidden";
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return "host is missing";
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || IPV4.matcher(host).find() || host.startsWith("[")) {
            return "literal IP / localhost targets are forbidden";
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (matchesWhitelist(host)) {
            return null;
        }
        return "host not in admission whitelist: " + host;
    }

    private boolean matchesWhitelist(String host) {
        for (String entry : allowedHosts) {
            if (entry.startsWith(".")) {
                if (host.equals(entry.substring(1)) || host.endsWith(entry)) {
                    return true;
                }
            } else if (host.equals(entry)) {
                return true;
            }
        }
        return false;
    }
}

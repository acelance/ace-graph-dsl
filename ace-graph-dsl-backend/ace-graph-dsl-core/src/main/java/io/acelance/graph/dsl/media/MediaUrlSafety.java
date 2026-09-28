package io.acelance.graph.dsl.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Media URL 安全校验（§8.2.3.1 SSRF 防护）。
 */
public final class MediaUrlSafety {

    private static final Logger log = LoggerFactory.getLogger(MediaUrlSafety.class);

    private MediaUrlSafety() {
    }

    /**
     * @return true 表示允许作为外部资源引用
     */
    public static boolean isAllowed(String url, String nodeId) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                log.warn("节点 {} 拒绝非 http(s) media url scheme={}: {}", nodeId, scheme, redact(url));
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                log.warn("节点 {} media url 无 host: {}", nodeId, redact(url));
                return false;
            }
            if (isBlockedHostLiteral(host)) {
                log.warn("节点 {} 拒绝私网/回环 media host={}: {}", nodeId, host, redact(url));
                return false;
            }
            // DNS 解析后复检（防 rebinding）；解析失败时仅依赖字面量检查（离线/测试环境常见）
            try {
                for (InetAddress addr : InetAddress.getAllByName(host)) {
                    if (isBlockedAddress(addr)) {
                        log.warn("节点 {} 拒绝解析到私网 IP 的 media host={} ip={}: {}",
                                nodeId, host, addr.getHostAddress(), redact(url));
                        return false;
                    }
                }
            } catch (UnknownHostException e) {
                log.warn("节点 {} media host DNS 暂不可解析 host={}，字面量检查已通过，放行: {}",
                        nodeId, host, e.getMessage());
            }
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("节点 {} media url 非法: {} — {}", nodeId, redact(url), e.getMessage());
            return false;
        }
    }

    private static boolean isBlockedHostLiteral(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h)
                || h.endsWith(".localhost")
                || "metadata.google.internal".equals(h)
                || h.startsWith("127.")
                || h.startsWith("10.")
                || h.startsWith("192.168.")
                || h.startsWith("169.254.")
                || is172Private(h)
                || "::1".equals(h)
                || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80");
    }

    private static boolean is172Private(String h) {
        if (!h.startsWith("172.")) {
            return false;
        }
        String[] p = h.split("\\.");
        if (p.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(p[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        return addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress();
    }

    private static String redact(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 120 ? url.substring(0, 120) + "…" : url;
    }
}

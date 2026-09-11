package io.acelance.graph.dsl.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 模型可见工具名合法化（§5.3 / D2）。
 *
 * <p>分隔符使用 {@code __}（冒号违反 OpenAI 兼容 {@code ^[a-zA-Z0-9_-]{1,64}$}）。
 * 超长时保留 originalName 尾部 + 8 位哈希。</p>
 */
public final class ToolNames {

    private static final Logger log = LoggerFactory.getLogger(ToolNames.class);

    public static final int MAX_LEN = 64;
    private static final Pattern LEGAL = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern ILLEGAL_CHAR = Pattern.compile("[^a-zA-Z0-9_-]");

    private ToolNames() {
    }

    /**
     * 生成模型可见名：{@code source__serverOrNs__original}，再 sanitize / 压缩。
     *
     * @param source       来源枚举
     * @param namespace    MCP serverKey 或 local 命名空间，可空
     * @param originalName 原始工具名
     */
    public static String toModelName(ToolSource source, String namespace, String originalName) {
        String src = source == null ? "local" : source.name().toLowerCase(Locale.ROOT);
        String ns = (namespace == null || namespace.isBlank()) ? "default" : namespace.trim();
        String orig = (originalName == null || originalName.isBlank()) ? "tool" : originalName.trim();
        String raw = src + "__" + ns + "__" + orig;
        String sanitized = ILLEGAL_CHAR.matcher(raw).replaceAll("_");
        if (sanitized.length() <= MAX_LEN && LEGAL.matcher(sanitized).matches()) {
            return sanitized;
        }
        String compressed = compress(sanitized, orig);
        log.debug("工具名压缩: {} -> {}", sanitized, compressed);
        return compressed;
    }

    /** 断言模型可见名合法，否则 fail fast */
    public static void assertLegal(String modelName) {
        if (modelName == null || !LEGAL.matcher(modelName).matches()) {
            throw new IllegalArgumentException(
                    "非法工具名（须匹配 ^[a-zA-Z0-9_-]{1,64}$）: " + modelName);
        }
    }

    private static String compress(String full, String originalName) {
        String hash = sha8(full);
        String tail = ILLEGAL_CHAR.matcher(originalName).replaceAll("_");
        int keep = MAX_LEN - 1 - hash.length();
        if (keep < 1) {
            return hash.substring(0, Math.min(MAX_LEN, hash.length()));
        }
        if (tail.length() > keep) {
            tail = tail.substring(tail.length() - keep);
        }
        String out = tail + "_" + hash;
        if (out.length() > MAX_LEN) {
            out = out.substring(0, MAX_LEN);
        }
        // 确保以字母或数字开头（部分端点挑剔）
        if (!Character.isLetterOrDigit(out.charAt(0))) {
            out = "t" + out.substring(1);
        }
        return out;
    }

    private static String sha8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}

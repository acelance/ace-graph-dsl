package io.acelance.graph.dsl.persistence;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 生成回收箱区分键 {@code recycle_version}。
 *
 * <p>格式：服务器本地时间 {@code yyyy-MM-dd HH:mm:ss.SSS} + 4 位随机字符（A-Z a-z 0-9）。
 * 禁止与 DSL 业务版本号（如 {@code 1.0.22}）混用。</p>
 */
public final class RecycleVersionGenerator {

    /** recycle_version 时间戳部分格式 */
    public static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String ALPHANUM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SUFFIX_LENGTH = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecycleVersionGenerator() {
    }

    /**
     * 生成一条新的 {@code recycle_version}。
     *
     * @return 时间戳 + 4 位随机后缀
     */
    public static String generate() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        return timestamp + randomSuffix();
    }

    /**
     * 生成 4 位随机后缀（A-Z a-z 0-9）。
     */
    public static String randomSuffix() {
        char[] chars = new char[SUFFIX_LENGTH];
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            chars[i] = ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length()));
        }
        return new String(chars);
    }

    /**
     * 取 {@code recycle_version} 末 4 位，用于清除二次确认。
     *
     * @param recycleVersion 完整 recycle_version
     * @return 末 4 位；长度不足时返回原串
     */
    public static String suffixOf(String recycleVersion) {
        if (recycleVersion == null || recycleVersion.length() < SUFFIX_LENGTH) {
            return recycleVersion;
        }
        return recycleVersion.substring(recycleVersion.length() - SUFFIX_LENGTH);
    }
}

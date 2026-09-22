package io.acelance.graph.dsl.ai.media;

import io.acelance.graph.dsl.media.MediaRef;

import java.util.Locale;
import java.util.Map;

/**
 * 多模态分流辅助：模型原生 Media vs 工具/Skill 材料注记（§8.2 / §8.3）。
 */
public final class MediaMaterialSupport {

    /** 扩展名 → mime（含办公文档；图片/音视频走模型原生通道） */
    public static final Map<String, String> EXT_MIME = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    );

    private MediaMaterialSupport() {
    }

    /**
     * 是否应挂到 {@code UserMessage.media}（视觉/音视频等 Chat 多模态载荷）。
     * 办公文档、PDF 等返回 false，应走材料注记。
     */
    public static boolean isModelNative(String mime, MediaRef ref) {
        if (mime != null && !mime.isBlank()) {
            String m = mime.trim().toLowerCase(Locale.ROOT);
            if (m.startsWith("image/") || m.startsWith("audio/") || m.startsWith("video/")) {
                return true;
            }
            return false;
        }
        String type = ref != null ? ref.type() : null;
        if (type != null) {
            String t = type.trim().toLowerCase(Locale.ROOT);
            return "image".equals(t) || "audio".equals(t) || "video".equals(t);
        }
        return false;
    }

    public static String resolveMime(MediaRef ref) {
        if (ref == null) {
            return null;
        }
        if (ref.mime() != null && !ref.mime().isBlank()) {
            return ref.mime().trim();
        }
        String ext = extension(ref.url());
        if (ext == null) {
            return null;
        }
        return EXT_MIME.get(ext);
    }

    public static String extension(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String filename(String url) {
        if (url == null || url.isBlank()) {
            return "attachment";
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? "attachment" : name;
    }

    /**
     * 写入 user 文本的材料注记（供工具 / Skill 读 url，不依赖模型视觉能力）。
     */
    public static String materialNote(String url, String mime) {
        String m = mime == null || mime.isBlank() ? "unknown" : mime.trim();
        return "[material] name=" + filename(url)
                + " mime=" + m
                + " url=" + url;
    }

    public static String abbreviate(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 96 ? url.substring(0, 96) + "…" : url;
    }
}

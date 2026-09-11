package io.acelance.graph.dsl.media;

/**
 * 多模态引用（§8.2）：节点间只传引用，不传二进制。
 *
 * @param url     资源地址；存在引用时必须有值
 * @param mime    MIME，可选，由 Resolver 补全
 * @param mediaId 外置存储 id，可选
 * @param type    业务分类（image/file/…），可选
 */
public record MediaRef(String url, String mime, String mediaId, String type) {

    public MediaRef {
        url = url == null ? null : url.trim();
        mime = mime == null || mime.isBlank() ? null : mime.trim();
        mediaId = mediaId == null || mediaId.isBlank() ? null : mediaId.trim();
        type = type == null || type.isBlank() ? null : type.trim();
    }

    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }
}

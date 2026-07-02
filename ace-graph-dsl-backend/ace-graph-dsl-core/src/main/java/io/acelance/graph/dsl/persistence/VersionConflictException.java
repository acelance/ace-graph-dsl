package io.acelance.graph.dsl.persistence;

/**
 * 版本保存冲突：版本号已占用或未按序递增。
 */
public class VersionConflictException extends IllegalStateException {

    public static final String CODE_VERSION_EXISTS = "VERSION_EXISTS";
    public static final String CODE_VERSION_NOT_INCREASED = "VERSION_NOT_INCREASED";

    private final String code;

    public VersionConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

package io.acelance.graph.dsl.web.webflux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设计器 WebFlux API 统一异常映射（对齐产品 fail-fast 语义）。
 */
@RestControllerAdvice(basePackages = "io.acelance.graph.dsl.web.webflux")
public class DesignerWebFluxExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(DesignerWebFluxExceptionAdvice.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        log.warn("designer API 400: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(400, ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        log.warn("designer API 409/500: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(409, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception ex) {
        log.error("designer API 500: {}", ex.toString(), ex);
        String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(500, msg));
    }

    private static Map<String, Object> errorBody(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", message);
        return body;
    }
}

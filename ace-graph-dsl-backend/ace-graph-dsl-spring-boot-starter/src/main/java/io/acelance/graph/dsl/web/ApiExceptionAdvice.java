package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.persistence.VersionConflictException;
import io.acelance.graph.dsl.security.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(errorBody(ex));
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<Map<String, String>> versionConflict(VersionConflictException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", ex.code());
        body.put("error", ex.getMessage() != null ? ex.getMessage() : ex.code());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(ex));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody(ex));
    }

    private static Map<String, String> errorBody(Throwable ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        return Map.of("error", message);
    }
}

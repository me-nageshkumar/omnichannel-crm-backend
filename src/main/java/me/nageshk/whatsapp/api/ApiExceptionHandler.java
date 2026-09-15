package me.nageshk.whatsapp.api;

import me.nageshk.whatsapp.rpc.RpcException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message == null ? "" : message));
    }

    @ExceptionHandler(NoDeviceException.class)
    public ResponseEntity<Map<String, String>> noDevice(NoDeviceException e) {
        return body(HttpStatus.CONFLICT, e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", message);
    }

    @ExceptionHandler(RpcException.class)
    public ResponseEntity<Map<String, String>> rpc(RpcException e) {
        HttpStatus status = switch (e.code()) {
            case RpcException.DEVICE_DISCONNECTED, RpcException.SEND_FAILED -> HttpStatus.BAD_GATEWAY;
            case RpcException.WHATSAPP_NOT_READY -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return body(status, e.code(), e.getMessage());
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Map<String, String>> timeout(TimeoutException e) {
        return body(HttpStatus.GATEWAY_TIMEOUT, "TIMEOUT", "extension did not respond in time");
    }

    /** Async controller results may surface wrapped; unwrap and re-dispatch. */
    @ExceptionHandler({CompletionException.class, ExecutionException.class})
    public ResponseEntity<Map<String, String>> wrapped(Exception e) {
        Throwable cause = e.getCause();
        if (cause instanceof RpcException rpc) return rpc(rpc);
        if (cause instanceof TimeoutException t) return timeout(t);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", String.valueOf(cause));
    }
}

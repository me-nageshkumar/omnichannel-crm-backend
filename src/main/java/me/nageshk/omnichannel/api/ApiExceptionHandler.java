package me.nageshk.omnichannel.api;

import com.mongodb.MongoExecutionTimeoutException;
import me.nageshk.omnichannel.auth.AccountLockedException;
import me.nageshk.omnichannel.inbox.ChatClosedException;
import me.nageshk.omnichannel.inbox.ChatTakenException;
import me.nageshk.omnichannel.inbox.LookupFailedException;
import me.nageshk.omnichannel.inbox.MediaUnavailableException;
import me.nageshk.omnichannel.inbox.NotOnWhatsAppException;
import me.nageshk.omnichannel.rpc.RpcException;
import me.nageshk.omnichannel.user.InvalidOtpException;
import me.nageshk.omnichannel.user.TwoFactorStateException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message == null ? "" : message));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    /** Includes {@code @PreAuthorize} denials, which surface as {@link AccessDeniedException}. */
    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<Map<String, String>> forbidden(RuntimeException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> invalidToken(InvalidTokenException e) {
        return body(HttpStatus.UNAUTHORIZED, InvalidTokenException.CODE, e.getMessage());
    }

    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<Map<String, String>> weakPassword(WeakPasswordException e) {
        return body(HttpStatus.BAD_REQUEST, WeakPasswordException.CODE, e.getMessage());
    }

    @ExceptionHandler(NoDeviceException.class)
    public ResponseEntity<Map<String, String>> noDevice(NoDeviceException e) {
        return body(HttpStatus.CONFLICT, e.code(), e.getMessage());
    }

    @ExceptionHandler(ChatClosedException.class)
    public ResponseEntity<Map<String, String>> chatClosed(ChatClosedException e) {
        return body(HttpStatus.CONFLICT, ChatClosedException.CODE, e.getMessage());
    }

    @ExceptionHandler(ChatTakenException.class)
    public ResponseEntity<Map<String, String>> chatTaken(ChatTakenException e) {
        return body(HttpStatus.CONFLICT, ChatTakenException.CODE, e.getMessage());
    }

    @ExceptionHandler(NotOnWhatsAppException.class)
    public ResponseEntity<Map<String, String>> notOnWhatsApp(NotOnWhatsAppException e) {
        return body(HttpStatus.CONFLICT, NotOnWhatsAppException.CODE, e.getMessage());
    }

    @ExceptionHandler(MediaUnavailableException.class)
    public ResponseEntity<Map<String, String>> mediaUnavailable(MediaUnavailableException e) {
        return body(HttpStatus.GONE, MediaUnavailableException.CODE, e.getMessage());
    }

    @ExceptionHandler(LookupFailedException.class)
    public ResponseEntity<Map<String, String>> lookupFailed(LookupFailedException e) {
        return body(HttpStatus.BAD_GATEWAY, LookupFailedException.CODE, e.getMessage());
    }

    @ExceptionHandler(TwoFactorStateException.class)
    public ResponseEntity<Map<String, String>> twoFactorState(TwoFactorStateException e) {
        return body(HttpStatus.CONFLICT, TwoFactorStateException.CODE, e.getMessage());
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<Map<String, String>> invalidOtp(InvalidOtpException e) {
        return body(HttpStatus.BAD_REQUEST, InvalidOtpException.CODE, e.getMessage());
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> locked(AccountLockedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "code", "TOO_MANY_ATTEMPTS",
                "message", e.getMessage(),
                "retryAfterSeconds", e.retryAfterSeconds()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.merge(f.getField(),
                String.valueOf(f.getDefaultMessage()), (a, b) -> a + "; " + b));
        return validationBody(fields.isEmpty() ? "invalid request"
                : fields.entrySet().stream().map(f -> f.getKey() + " " + f.getValue())
                        .reduce((a, b) -> a + "; " + b).orElseThrow(), fields);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(ValidationException e) {
        return validationBody(e.getMessage(), e.fields());
    }

    private static ResponseEntity<Map<String, Object>> validationBody(String message, Map<String, String> fields) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", ValidationException.CODE, "message", message, "fields", fields));
    }

    @ExceptionHandler(CodedException.class)
    public ResponseEntity<Map<String, String>> coded(CodedException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", String.valueOf(e.getMessage()));
        if (e.field() != null) body.put("field", e.field());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(TooManyRowsException.class)
    public ResponseEntity<Map<String, String>> tooManyRows(TooManyRowsException e) {
        return body(HttpStatus.BAD_REQUEST, TooManyRowsException.CODE, e.getMessage());
    }

    /** An upload over the container's size limit reads as a bad {@code file} field. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return validationBody("file too large", Map.of("file", "is larger than the allowed size"));
    }

    @ExceptionHandler(DuplicateException.class)
    public ResponseEntity<Map<String, String>> duplicate(DuplicateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", DuplicateException.CODE, "message", String.valueOf(e.getMessage()), "field", e.field()));
    }

    @ExceptionHandler(RoleInUseException.class)
    public ResponseEntity<Map<String, String>> roleInUse(RoleInUseException e) {
        return body(HttpStatus.CONFLICT, RoleInUseException.CODE, e.getMessage());
    }

    @ExceptionHandler(AuthorityInUseException.class)
    public ResponseEntity<Map<String, Object>> authorityInUse(AuthorityInUseException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", AuthorityInUseException.CODE, "message", String.valueOf(e.getMessage()), "roles", e.roles()));
    }

    @ExceptionHandler(TemplateInUseException.class)
    public ResponseEntity<Map<String, String>> templateInUse(TemplateInUseException e) {
        return body(HttpStatus.CONFLICT, TemplateInUseException.CODE, e.getMessage());
    }

    @ExceptionHandler(InvalidConnectCodeException.class)
    public ResponseEntity<Map<String, String>> invalidConnectCode(InvalidConnectCodeException e) {
        return body(HttpStatus.BAD_REQUEST, InvalidConnectCodeException.CODE, e.getMessage());
    }

    @ExceptionHandler(DeviceDisabledException.class)
    public ResponseEntity<Map<String, String>> deviceDisabled(DeviceDisabledException e) {
        return body(HttpStatus.FORBIDDEN, DeviceDisabledException.CODE, e.getMessage());
    }

    @ExceptionHandler(DisabledAtHigherLevelException.class)
    public ResponseEntity<Map<String, Object>> disabledAtHigherLevel(DisabledAtHigherLevelException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", DisabledAtHigherLevelException.CODE, "message", String.valueOf(e.getMessage()),
                "level", e.level().name()));
    }

    @ExceptionHandler(AlreadyAllBrandsException.class)
    public ResponseEntity<Map<String, String>> alreadyAllBrands(AlreadyAllBrandsException e) {
        return body(HttpStatus.BAD_REQUEST, AlreadyAllBrandsException.CODE, e.getMessage());
    }

    @ExceptionHandler(AlreadyInWorkspaceException.class)
    public ResponseEntity<Map<String, String>> alreadyInWorkspace(AlreadyInWorkspaceException e) {
        return body(HttpStatus.CONFLICT, AlreadyInWorkspaceException.CODE, e.getMessage());
    }

    @ExceptionHandler(NotInWorkspaceException.class)
    public ResponseEntity<Map<String, String>> notInWorkspace(NotInWorkspaceException e) {
        return body(HttpStatus.BAD_REQUEST, NotInWorkspaceException.CODE, e.getMessage());
    }

    @ExceptionHandler(LastAccessException.class)
    public ResponseEntity<Map<String, String>> lastAccess(LastAccessException e) {
        return body(HttpStatus.BAD_REQUEST, LastAccessException.CODE, e.getMessage());
    }

    /** Someone else changed the record between this request's read and its write. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> conflict(OptimisticLockingFailureException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", "the record was changed by another request; reload and try again");
    }

    @ExceptionHandler(RpcException.class)
    public ResponseEntity<Map<String, String>> rpc(RpcException e) {
        HttpStatus status = switch (e.code()) {
            case RpcException.DEVICE_DISCONNECTED, RpcException.SEND_FAILED -> HttpStatus.BAD_GATEWAY;
            case RpcException.WHATSAPP_NOT_READY -> HttpStatus.CONFLICT;
            case RpcException.MEDIA_UNAVAILABLE -> HttpStatus.GONE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return body(status, e.code(), e.getMessage());
    }

    /** A filter query over its time limit, whether or not it went through {@code QueryTimeouts.guarded}. */
    @ExceptionHandler({QueryTimeoutException.class, MongoExecutionTimeoutException.class})
    public ResponseEntity<Map<String, String>> queryTimeout(RuntimeException e) {
        return body(HttpStatus.GATEWAY_TIMEOUT, QueryTimeoutException.CODE, QueryTimeoutException.MESSAGE);
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
        if (cause instanceof LookupFailedException lookup) return lookupFailed(lookup);
        if (cause instanceof NotOnWhatsAppException absent) return notOnWhatsApp(absent);
        if (cause instanceof MediaUnavailableException media) return mediaUnavailable(media);
        if (cause instanceof TimeoutException t) return timeout(t);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", String.valueOf(cause));
    }
}

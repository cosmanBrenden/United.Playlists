package dev.unitedplaylists.web;

import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.security.TokenCipher;
import dev.unitedplaylists.service.PlaylistService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to HTTP responses.
 *
 * <p>The {@code requiresReconnect} flag matters to the UI: a 401 from a service is
 * not the user's fault and is fixed by reconnecting, whereas a 403 from the same
 * service usually means a missing subscription and reconnecting will not help.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Error payload. */
    public record ApiError(
            String error,
            String message,
            String provider,
            boolean requiresReconnect,
            List<String> details,
            Instant timestamp) {

        static ApiError of(String error, String message) {
            return new ApiError(error, message, null, false, List.of(), Instant.now());
        }
    }

    @ExceptionHandler(PlaylistService.PlaylistNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(PlaylistService.PlaylistNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("playlist_not_found", e.getMessage()));
    }

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ApiError> handleProvider(ProviderException e) {
        HttpStatus status = switch (e.getKind()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNSUPPORTED -> HttpStatus.NOT_IMPLEMENTED;
            case UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
        // At WARN, not DEBUG. These are the failures a user actually hits — an expired
        // token, a refused import — and they are exactly what someone will be asked to
        // paste when it goes wrong. Logged below the configured level, the one useful
        // line about a 403 is discarded before anyone can read it.
        if (status.is5xxServerError() || status == HttpStatus.FORBIDDEN
                || status == HttpStatus.UNAUTHORIZED) {
            log.warn("{} failed: {}", e.getProvider(), e.getMessage());
        } else {
            log.info("{} failed: {}", e.getProvider(), e.getMessage());
        }
        return ResponseEntity.status(status).body(new ApiError(
                e.getKind().name().toLowerCase(),
                e.getMessage(),
                e.getProvider().name(),
                e.requiresReconnect(),
                List.of(),
                Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(new ApiError(
                "validation_failed", "Request is not valid", null, false, details, Instant.now()));
    }

    /** Covers a malformed track key from {@code TrackRef.fromKey} and similar. */
    @ExceptionHandler({IllegalArgumentException.class, IndexOutOfBoundsException.class})
    public ResponseEntity<ApiError> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(ApiError.of("bad_request", e.getMessage()));
    }

    @ExceptionHandler(TokenCipher.TokenDecryptionException.class)
    public ResponseEntity<ApiError> handleTokenDecryption(TokenCipher.TokenDecryptionException e) {
        // Unrecoverable without reconnecting, so say so rather than reporting a 500
        // the user cannot act on.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(
                "credentials_unreadable",
                "Stored credentials could not be read; reconnect the service",
                null, true, List.of(), Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiError.of("internal_error", "Something went wrong"));
    }
}

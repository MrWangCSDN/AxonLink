package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.service.ReplayConfigConflictException;
import com.axonlink.ai.replay.service.ReplayConfigNotFoundException;
import com.axonlink.ai.replay.service.ReplayConfigReviewForbiddenException;
import com.axonlink.common.R;
import com.axonlink.security.UserPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 回放配置 Controller 公共能力：登录人解析与统一异常映射。 */
public abstract class AbstractReplayConfigController {

    private static final Logger log = LoggerFactory.getLogger(AbstractReplayConfigController.class);

    private final UserPrincipalResolver userResolver;

    protected AbstractReplayConfigController(UserPrincipalResolver userResolver) {
        this.userResolver = userResolver;
    }

    protected ReplayConfigOperator resolveOperator(HttpServletRequest request) {
        UserPrincipalResolver.Resolved resolved = userResolver.resolve(request);
        if (resolved == null || resolved.principal == null || resolved.principal.isBlank()) {
            return null;
        }
        if (resolved.user == null) {
            return new ReplayConfigOperator(resolved.principal, resolved.principal);
        }
        String username = firstNonBlank(resolved.user.getUsername(), resolved.principal);
        return new ReplayConfigOperator(username, firstNonBlank(resolved.user.getRealName(), username),
                resolved.user.getEmpNo());
    }

    @ExceptionHandler(ReplayConfigNotFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(ReplayConfigNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ReplayConfigConflictException.class)
    public ResponseEntity<R<Void>> handleConflict(ReplayConfigConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(ReplayConfigReviewForbiddenException.class)
    public ResponseEntity<R<Void>> handleReviewForbidden(ReplayConfigReviewForbiddenException exception) {
        return error(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> handleInvalidArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleInvalidRequestParameter(MethodArgumentTypeMismatchException exception) {
        return error(HttpStatus.BAD_REQUEST, "请求参数错误");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "请求参数错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnexpectedException(Exception exception) {
        log.error("[replay-config] request failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "请求失败");
    }

    protected static <T> ResponseEntity<R<T>> error(HttpStatus status, String message) {
        R<T> body = R.fail(status.value(), message, null);
        return ResponseEntity.status(status).body(body);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}

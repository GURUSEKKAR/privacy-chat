package com.secretchat.common;

import com.secretchat.protocol.MessageDtos.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Uniform, non-leaky error bodies. Never echoes request content back. */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorResponse> api(ApiException e) {
    return body(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HandlerMethodValidationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ErrorResponse> validation(Exception e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
  }

  @ExceptionHandler({
    DataIntegrityViolationException.class,
    ObjectOptimisticLockingFailureException.class,
    PessimisticLockingFailureException.class
  })
  ResponseEntity<ErrorResponse> conflict(Exception e) {
    return body(HttpStatus.CONFLICT, "CONFLICT", "Concurrent or duplicate modification");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception e) {
    // Log type only: exception messages might include request data.
    log.error("Unhandled error: {}", e.getClass().getName());
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error");
  }

  private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String msg) {
    return ResponseEntity.status(status).body(new ErrorResponse(status.value(), code, msg));
  }
}

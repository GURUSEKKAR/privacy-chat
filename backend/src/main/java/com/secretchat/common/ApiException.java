package com.secretchat.common;

import org.springframework.http.HttpStatus;

/** Business error mapped to a stable HTTP status + code. Messages must not contain secrets. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException unauthorized() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
  }

  public static ApiException invalidCredentials() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials");
  }

  public static ApiException forbidden(String code, String message) {
    return new ApiException(HttpStatus.FORBIDDEN, code, message);
  }

  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
  }

  public static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  public static ApiException gone(String code, String message) {
    return new ApiException(HttpStatus.GONE, code, message);
  }

  public static ApiException badRequest(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }

  public static ApiException rateLimited() {
    return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests");
  }
}

package com.secretchat.client.api;

/** Non-2xx response from the backend, carrying the stable error code. */
public class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(status + " " + code + ": " + message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}

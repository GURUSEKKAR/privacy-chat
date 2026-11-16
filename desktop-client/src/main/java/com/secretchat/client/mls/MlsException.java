package com.secretchat.client.mls;

public class MlsException extends RuntimeException {
  public MlsException(String message) {
    super(message);
  }

  public MlsException(String message, Throwable cause) {
    super(message, cause);
  }
}

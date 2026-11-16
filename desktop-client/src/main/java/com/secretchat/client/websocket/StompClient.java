package com.secretchat.client.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Minimal STOMP 1.2 client over {@link java.net.http.WebSocket}. The bearer token is sent on the
 * HTTP upgrade request (the server authenticates the handshake). Use a {@code wss://} URI in
 * production.
 */
public final class StompClient implements WebSocket.Listener {
  public record Frame(String command, Map<String, String> headers, String body) {}

  public interface Handler {
    void onMessage(Frame frame);

    void onError(Frame frame);

    void onClosed(int statusCode, String reason);
  }

  private final Handler handler;
  private final StringBuilder buffer = new StringBuilder();
  private final CompletableFuture<Frame> connected = new CompletableFuture<>();
  private volatile WebSocket socket;
  private volatile boolean closed;

  private StompClient(Handler handler) {
    this.handler = handler;
  }

  public static StompClient connect(URI wsUri, String bearerToken, Handler handler) {
    StompClient c = new StompClient(handler);
    try {
      c.socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .header("Authorization", "Bearer " + bearerToken)
              .connectTimeout(Duration.ofSeconds(10))
              .buildAsync(wsUri, c)
              .get(15, TimeUnit.SECONDS);
      c.sendFrame(
          "CONNECT",
          Map.of("accept-version", "1.2", "host", wsUri.getHost(), "heart-beat", "0,0"),
          null);
      Frame f = c.connected.get(15, TimeUnit.SECONDS);
      if (!"CONNECTED".equals(f.command())) {
        throw new IllegalStateException("STOMP connect failed: " + f.headers().get("message"));
      }
      return c;
    } catch (Exception e) {
      c.close();
      throw new IllegalStateException("WebSocket connection failed: " + rootMessage(e), e);
    }
  }

  public void subscribe(String id, String destination) {
    sendFrame("SUBSCRIBE", Map.of("id", id, "destination", destination), null);
  }

  public void send(String destination, String jsonBody) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("destination", destination);
    h.put("content-type", "application/json");
    h.put("content-length", String.valueOf(jsonBody.getBytes(StandardCharsets.UTF_8).length));
    sendFrame("SEND", h, jsonBody);
  }

  public boolean isOpen() {
    return !closed && socket != null && !socket.isOutputClosed() && !socket.isInputClosed();
  }

  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    WebSocket s = socket;
    if (s != null) {
      try {
        sendFrame("DISCONNECT", Map.of(), null);
      } catch (RuntimeException ignored) {
        // already closing
      }
      s.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
      s.abort();
    }
  }

  private synchronized void sendFrame(String command, Map<String, String> headers, String body) {
    StringBuilder sb = new StringBuilder(command).append('\n');
    headers.forEach((k, v) -> sb.append(escape(k)).append(':').append(escape(v)).append('\n'));
    sb.append('\n');
    if (body != null) {
      sb.append(body);
    }
    sb.append('\0');
    socket.sendText(sb.toString(), true).join();
  }

  @Override
  public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    synchronized (buffer) {
      buffer.append(data);
      int nul;
      while ((nul = buffer.indexOf("\0")) >= 0) {
        String raw = buffer.substring(0, nul);
        buffer.delete(0, nul + 1);
        dispatch(raw);
      }
    }
    webSocket.request(1);
    return null;
  }

  private void dispatch(String raw) {
    String s = raw;
    while (s.startsWith("\n") || s.startsWith("\r\n")) {
      s = s.substring(s.indexOf('\n') + 1); // heart-beats / EOLs between frames
    }
    if (s.isEmpty()) {
      return;
    }
    int headerEnd = s.indexOf("\n\n");
    String head = headerEnd >= 0 ? s.substring(0, headerEnd) : s;
    String body = headerEnd >= 0 ? s.substring(headerEnd + 2) : "";
    String[] lines = head.split("\r?\n");
    Map<String, String> headers = new LinkedHashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int c = lines[i].indexOf(':');
      if (c > 0) {
        headers.putIfAbsent(
            unescape(lines[i].substring(0, c)), unescape(lines[i].substring(c + 1)));
      }
    }
    Frame f = new Frame(lines[0], headers, body);
    switch (f.command()) {
      case "CONNECTED" -> connected.complete(f);
      case "MESSAGE", "RECEIPT" -> handler.onMessage(f);
      case "ERROR" -> {
        connected.complete(f);
        handler.onError(f);
      }
      default -> {
        // ignore unknown frames
      }
    }
  }

  @Override
  public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    closed = true;
    connected.completeExceptionally(new IllegalStateException("closed: " + statusCode));
    handler.onClosed(statusCode, reason);
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    closed = true;
    connected.completeExceptionally(error);
    handler.onClosed(-1, rootMessage(error));
  }

  private static String escape(String v) {
    return v.replace("\\", "\\\\").replace("\n", "\\n").replace(":", "\\c").replace("\r", "\\r");
  }

  private static String unescape(String v) {
    return v.replace("\\r", "\r").replace("\\n", "\n").replace("\\c", ":").replace("\\\\", "\\");
  }

  private static String rootMessage(Throwable t) {
    while (t.getCause() != null) {
      t = t.getCause();
    }
    return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
  }

  /** Adapts a lambda into a handler for simple uses. */
  public static Handler handler(
      Consumer<Frame> onMessage, Consumer<Frame> onError, Runnable onClosed) {
    return new Handler() {
      @Override
      public void onMessage(Frame frame) {
        onMessage.accept(frame);
      }

      @Override
      public void onError(Frame frame) {
        onError.accept(frame);
      }

      @Override
      public void onClosed(int statusCode, String reason) {
        onClosed.run();
      }
    };
  }
}

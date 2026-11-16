package com.secretchat.client;

import java.net.URI;
import java.util.Set;

/** Credentials may use HTTP only for an explicitly local development server. */
public final class ClientConfiguration {
  private ClientConfiguration() {}

  public static URI validateServer(URI server) {
    boolean local =
        Set.of("localhost", "127.0.0.1", "::1", "[::1]")
            .contains(server.getHost() == null ? "" : server.getHost());
    if (!("https".equals(server.getScheme()) || ("http".equals(server.getScheme()) && local))
        || server.getHost() == null
        || server.getUserInfo() != null
        || server.getQuery() != null
        || server.getFragment() != null
        || (server.getPath() != null
            && !server.getPath().isEmpty()
            && !server.getPath().equals("/"))) {
      throw new IllegalArgumentException(
          "Use an HTTPS server origin, or HTTP on localhost for development");
    }
    return server;
  }

  public static void applyEnvironment() {
    setFromEnvironment("secretchat.server", "SECRETCHAT_SERVER_URL");
    setFromEnvironment("secretchat.dataDir", "SECRETCHAT_DATA_DIR");
    if (System.getProperty("secretchat.server") == null
        && System.getProperty("secretchat.packagedServer") != null)
      System.setProperty("secretchat.server", System.getProperty("secretchat.packagedServer"));
  }

  private static void setFromEnvironment(String property, String variable) {
    String value = System.getenv(variable);
    if (System.getProperty(property) == null && value != null && !value.isBlank())
      System.setProperty(property, value);
  }
}

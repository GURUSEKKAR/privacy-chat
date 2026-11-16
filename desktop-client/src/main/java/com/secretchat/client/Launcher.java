package com.secretchat.client;

/** Plain main class for jpackage's classpath launcher with the bundled JavaFX runtime. */
public final class Launcher {
  private Launcher() {}

  public static void main(String[] args) {
    if (java.util.Arrays.asList(args).contains("--verify-runtime")) {
      System.exit(RuntimeCheck.run());
    }
    ClientConfiguration.applyEnvironment();
    com.secretchat.client.ui.SecretChatApp.main(args);
  }
}

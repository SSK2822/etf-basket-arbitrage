package com.etfarb.cli;

/** Process entry point. Everything testable lives in {@link ScannerApp}. */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    System.exit(new ScannerApp(System.err).run(args).value());
  }
}

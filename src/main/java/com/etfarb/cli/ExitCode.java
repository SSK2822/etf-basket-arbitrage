package com.etfarb.cli;

/** Process exit codes, distinct so a caller can tell a bad invocation from bad data. */
public enum ExitCode {
  OK(0),
  USAGE(2),
  INVALID_INPUT(3),
  IO_FAILURE(4);

  private final int value;

  ExitCode(int value) {
    this.value = value;
  }

  public int value() {
    return value;
  }
}

package org.filesync;

class SyncOpcode {

  public static final byte INIT = 0x0;
  public static final byte NEW_FILE_PATH = 0x1;
  public static final byte NEW_FILE_PART = 0x2;
  public static final byte NEW_FILE_END = 0x3;
  public static final byte CLOSE = 0x4;

}

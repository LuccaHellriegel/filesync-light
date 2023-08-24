package org.filesync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class SyncFrame {

  //because the frame is treated as immutable anyway, we expose the properties directly instead of using getters
  public final byte syncOpCode;
  public final byte[] payload;

  public SyncFrame(byte syncOpCode, byte[] payload) {
    this.syncOpCode = syncOpCode;
    this.payload = payload;
  }

  public static SyncFrame readFrame(InputStream inputStream) throws IOException {
    byte opcode = (byte) inputStream.read(); //the first websocket byte contains 4 additional bits, which we don't implement for our protocol
    //for simplicity, we work with 4 bytes/int-size for the payload size
    // depending on specific use case (e.g. frequent big files vs frequent small files) a different size could make sense
    ByteBuffer payloadByteBuffer = ByteBuffer.wrap(inputStream.readNBytes(4));
    payloadByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
    int payloadLength = payloadByteBuffer
        .getInt();
    byte[] payload = new byte[payloadLength];
    if (payloadLength > 0) {
      int readPayloadAmount = inputStream.read(payload);
      if (readPayloadAmount != payloadLength) {
        throw new IOException("Expected payload amount did not match actually read amount");
      }
    }

    return new SyncFrame(opcode, payload);
  }

  public static void writeFrame(SyncFrame frame, OutputStream outputStream) throws IOException {
    outputStream.write(frame.syncOpCode);
    byte[] payloadLengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(frame.payload.length).array();

    for (byte b : payloadLengthBytes) {
      outputStream.write(b);
    }
    outputStream.write(frame.payload);
    outputStream.flush();
  }

  public byte[] toBytes() {
    byte[] bytes = new byte[1 + 4 + payload.length];
    bytes[0] = this.syncOpCode;
    byte[] payloadLengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(payload.length).array();
    bytes[1] = payloadLengthBytes[0];
    bytes[2] = payloadLengthBytes[1];
    bytes[3] = payloadLengthBytes[2];
    bytes[4] = payloadLengthBytes[3];
    System.arraycopy(payload, 0, bytes, 5, payload.length);
    return bytes;
  }

  public static SyncFrame closeFrame() {
    return new SyncFrame(SyncOpcode.CLOSE, new byte[]{});
  }
}

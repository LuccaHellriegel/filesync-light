export class FrameParser {
  private previousDataBuffer: Buffer | null = null;

  constructor() {}

  handle(dataBuffer: Buffer) {
    const resultFrames = [];
    if (this.previousDataBuffer) {
      dataBuffer = Buffer.concat([this.previousDataBuffer, dataBuffer]);
    }
    let offset = 0;
    let frame = this.parseFrameIfPossible(dataBuffer, offset);
    while (frame) {
      offset += frame.payload.length + 1 + 4;
      resultFrames.push(frame);
      frame = this.parseFrameIfPossible(dataBuffer, offset);
    }

    if (offset === dataBuffer.length) {
      this.previousDataBuffer = null;
    }

    if (offset != dataBuffer.length) {
      this.previousDataBuffer = dataBuffer.subarray(offset);
    }

    return resultFrames;
  }

  private parseFrameIfPossible(dataBuffer: Buffer, offset: number) {
    //partial frames can be send, so we need to collect them before processing
    const restLength = dataBuffer.length - offset;
    if (restLength < 1 + 4) {
      return null;
    } else {
      const payloadLength = dataBuffer.readUIntLE(offset + 1, 4);
      if (restLength < 1 + 4 + payloadLength) {
        return null;
      }
      return {
        opcode: dataBuffer[offset],
        payload: dataBuffer.subarray(
          offset + 1 + 4,
          offset + 1 + 4 + payloadLength
        ),
      };
    }
  }
}

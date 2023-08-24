import path from "path";
import fs from "fs";
import { Socket } from "net";
import { FrameParser } from "./FrameParser";
import { FolderWriteHandler } from "./FolderWriteHandler";
import {
  SYNC_INIT,
  SYNC_NEW_FILE_PATH,
  SYNC_NEW_FILE_PART,
  SYNC_NEW_FILE_END,
  SYNC_CLOSE,
} from "./syncOpcode";

export class FrameHandler {
  private filePaths: string[] = [];

  constructor(
    private chunkSize: number,
    private pathToFolder: string,
    private socket: Socket,
    private frameParser: FrameParser,
    private folderHandler: FolderWriteHandler
  ) {}

  init(apiKey: string) {
    this.initFilePaths();
    this.initSocket(apiKey);
  }

  private initFilePaths() {
    const filePaths: string[] = [];

    function traverseDirectory(currentPath: string) {
      const files = fs.readdirSync(currentPath);

      for (const file of files) {
        const filePath = path.join(currentPath, file);
        const fileStat = fs.statSync(filePath);

        if (fileStat.isDirectory()) {
          traverseDirectory(filePath);
        } else {
          filePaths.push(filePath.split("/").slice(1).join("/"));
        }
      }
    }
    traverseDirectory(this.pathToFolder);
    this.filePaths = filePaths;
    console.log(
      "Client is started with the following found files: " + filePaths
    );
  }

  initSocket(apiKey: string) {
    this.socket.on("connect", () => {
      this.socket.write(apiKey);
      this.sendFrame(
        Buffer.from(this.filePaths.join("\n"), "utf-8"),
        SYNC_INIT
      );
    });

    this.socket.on("data", (data) => {
      this.handleData(data);
    });
  }

  private handleData(data: Buffer) {
    const frames = this.frameParser.handle(data);

    for (const frame of frames) {
      switch (frame.opcode) {
        case SYNC_INIT:
          const requestedFiles = frame.payload.toString().split("\n");
          if (!(requestedFiles.length > 0)) {
            return;
          }
          console.log("Files were requested: " + requestedFiles);
          const callback = () => {
            const file = requestedFiles.pop();
            if (!file) return;
            this.sendFile(file, callback);
          };
          callback();
          break;
        case SYNC_NEW_FILE_PATH:
          const newFilePath = frame.payload.toString();
          console.log("Starting to receive new file: " + newFilePath);
          this.folderHandler.saveIncomingPath(newFilePath);
          break;
        case SYNC_NEW_FILE_PART:
          this.folderHandler.writeBuffer(frame.payload);
          break;
        case SYNC_NEW_FILE_END:
          const filePath = frame.payload.toString();
          this.folderHandler.endWrite(filePath);
          console.log("Fully received new file: " + filePath);
          this.filePaths.push(filePath);
          break;
        case SYNC_CLOSE:
        default:
          this.socket.end();
          break;
      }
    }
  }

  sendFrame(data: Buffer, syncOpcode: number) {
    const length = Buffer.alloc(4);
    length.writeUIntLE(data.length, 0, 4);
    this.socket.write(
      Buffer.concat([Buffer.from([syncOpcode]), length, Buffer.from(data)])
    );
  }

  sendFile(requestedFile: string, callback: () => void) {
    console.log("Starting to send file " + requestedFile);
    const pathBuffer = Buffer.from(requestedFile, "utf-8");
    this.sendFrame(pathBuffer, SYNC_NEW_FILE_PATH);
    const readStream = fs.createReadStream(
      path.join(this.pathToFolder, requestedFile)
    );
    readStream.on("readable", () => {
      let chunk;
      while (null !== (chunk = readStream.read(this.chunkSize))) {
        this.sendFrame(chunk, SYNC_NEW_FILE_PART);
      }
    });
    readStream.on("end", () => {
      this.sendFrame(pathBuffer, SYNC_NEW_FILE_END);
      callback();
    });
  }
}

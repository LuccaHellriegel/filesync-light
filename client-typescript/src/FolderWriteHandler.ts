import fs, { WriteStream } from "fs";
import path from "path";

export class FolderWriteHandler {
  private incomingPath: string | null = null;
  private openWriteStream: WriteStream | null = null;

  constructor(private pathToFolder: string) {}

  writeBuffer(buffer: Buffer) {
    if (!this.incomingPath) {
      return;
    }
    if (!this.openWriteStream) {
      this.createDirectoriesRecursively(
        path.join(this.pathToFolder, path.dirname(this.incomingPath))
      );
      this.openWriteStream = fs.createWriteStream(
        path.join(this.pathToFolder, this.incomingPath)
      );
    }

    this.openWriteStream.write(buffer);
  }

  saveIncomingPath(incomingPath: string) {
    this.incomingPath = incomingPath;
  }

  endWrite(incomingPath: string) {
    if (!(this.incomingPath === incomingPath)) {
      this.incomingPath = null;
      this.openWriteStream?.close();
      this.openWriteStream = null;
      return;
    }
    this.incomingPath = null;
    this.openWriteStream?.end();
    this.openWriteStream = null;
  }

  private createDirectoriesRecursively(dirPath: string) {
    const parentDir = path.dirname(dirPath);

    if (!fs.existsSync(parentDir)) {
      this.createDirectoriesRecursively(parentDir);
    }

    if (!fs.existsSync(dirPath)) {
      fs.mkdirSync(dirPath);
    }
  }
}

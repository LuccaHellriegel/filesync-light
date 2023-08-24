import net from "net";
import { FrameParser } from "./FrameParser";
import { FolderWriteHandler } from "./FolderWriteHandler";
import { FrameHandler } from "./FrameHandler";

function printHelp(): void {
  console.log(
    "The sync client allows to connect to a server to share one folder with all connected clients."
  );
  console.log("Options:");
  console.log("  --help, -h      Print this help message and exit.");
  console.log(
    "  --debug         Enable debug mode. This sets dummy values for all needed env vars."
  );

  console.log("Environment Variables:");
  console.log(
    "  SERVER_PORT     : The port number for the server. Debug value: 8080"
  );
  console.log(
    "  CLIENT_FOLDER   : The path folder to be synced. Needs to exist for the server to start. Debug value: mounted-client-folder"
  );
  console.log(
    "  API_KEY         : The API key for authentication with the server. Debug value: SUPER-SECRET-API-KEY"
  );
  console.log(
    "  CHUNK_SIZE      : The chunk size in bytes for sending file parts. Debug value: 10000000"
  );

  process.exit(0);
}

if (process.argv.includes("--help") || process.argv.includes("-h")) {
  printHelp();
}

//TypeScript does not recognize that we either exit or set them if its debug mode
let serverHost = process.env.SERVER_HOST as unknown as string;
let serverPort = (process.env.SERVER_PORT
  ? parseInt(process.env.SERVER_PORT)
  : null) as unknown as number;
let pathToFolder = process.env.CLIENT_FOLDER as unknown as string;
let apiKey = process.env.API_KEY as unknown as string;
let chunkSize = (process.env.CHUNK_SIZE
  ? parseInt(process.env.CHUNK_SIZE)
  : null) as unknown as number;

if (!pathToFolder || !serverHost || !serverPort || !apiKey || !chunkSize) {
  const args = process.argv.slice(2);
  const isDebugMode = args.includes("--debug");
  if (isDebugMode) {
    pathToFolder = "mounted-client-folder";
    serverHost = "localhost";
    serverPort = 8080;
    apiKey = "SUPER-SECRET-API-KEY";
    chunkSize = 1000000 * 10;
  } else {
    console.error(
      "At least one required env var was missing: " +
        [
          "serverHost: " + serverHost,
          "serverPort: " + serverPort,
          "pathToFolder: " + pathToFolder,
          "apiKey: " + !!apiKey ? "censored" : "",
          "chunkSize: " + chunkSize,
        ]
    );
    process.exit(1);
  }
}

const socket = net.connect({ host: serverHost, port: serverPort });

const folderHandler = new FolderWriteHandler(pathToFolder);
const frameParser = new FrameParser();
const frameHandler = new FrameHandler(
  chunkSize,
  pathToFolder,
  socket,
  frameParser,
  folderHandler
);

frameHandler.init(apiKey);

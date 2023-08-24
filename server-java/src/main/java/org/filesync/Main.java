package org.filesync;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class Main {

  public static void main(String[] args) {
    for (String arg : args) {
      if (arg.equals("--help") || arg.equals("-h")) {
        printHelp();
        return;
      }
    }

    EnvVars vars = new EnvVars(args);

    CopyOnWriteArrayList<Socket> clients = new CopyOnWriteArrayList<>();
    List<String> filePaths = new ArrayList<>();
    try {
      traverseFolder(vars, Paths.get(vars.pathToFolder), filePaths);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    System.out.println("Server is started with the following found files: " + filePaths);
    SharedSyncState sharedSyncState = new SharedSyncState(clients,
        Collections.synchronizedList(filePaths), vars.pathToFolder);
    SyncManager syncManager = new SyncManager(sharedSyncState, vars);

    try (ServerSocket serverSocket = new ServerSocket(vars.serverPort)) {
      syncManager.start();

      while (true) {
        try {
          Socket clientSocket = serverSocket.accept();
          String clientId = UUID.randomUUID().toString();
          System.out.println("Client " + clientId + " connected: " + clientSocket.getInetAddress());
          byte[] serverApiKeyBytes = vars.apiKey.getBytes();
          byte[] clientApiKeyBytes = clientSocket.getInputStream()
              .readNBytes(vars.apiKey.getBytes().length);
          if (!Arrays.equals(serverApiKeyBytes, clientApiKeyBytes)) {
            clientSocket.close();
            System.out.println("Client " + clientId + "invalid key detected.");
          } else {
            System.out.println("Client " + clientId + " key validated.");
            sharedSyncState.clientIds.put(clientSocket, clientId);
            clients.add(clientSocket);
          }
        } catch (IOException e) {
          e.printStackTrace();
        }

      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static void traverseFolder(EnvVars vars, Path folderPath, List<String> filePaths)
      throws IOException {
    try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(folderPath)) {
      for (Path path : directoryStream) {
        if (Files.isRegularFile(path)) {
          filePaths.add(path.toString().substring(vars.pathToFolder.length() + 1));
        } else if (Files.isDirectory(path)) {
          traverseFolder(vars, path, filePaths);
        }
      }
    }
  }

  private static void printHelp() {
    System.out.println("The sync server allows clients to synchronize folder contents.");
    System.out.println("Options:");
    System.out.println("  --help, -h      Print this help message and exit.");
    System.out.println(
        "  --debug         Enable debug mode. This sets dummy values for all needed env vars.");

    System.out.println("Environment Variables:");
    System.out.println("  SERVER_PORT     : The port number for the server. Debug value: 8080");
    System.out.println(
        "  SERVER_FOLDER   : The path to the folder used for syncing. Needs to exist for the server to start. Debug value: mounted-server-folder");
    System.out.println(
        "  API_KEY         : The API key to compare with clients for authentication. Debug value: SUPER-SECRET-API-KEY");
    System.out.println(
        "  CHUNK_SIZE      : The chunk size in bytes for sending file parts. Debug value: 10000000");

    System.exit(0);
  }

}


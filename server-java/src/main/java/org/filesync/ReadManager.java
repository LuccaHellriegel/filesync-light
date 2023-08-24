package org.filesync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

class ReadManager {

  private final SharedSyncState sharedSyncState;
  private final ThreadPoolExecutor workerPool;
  private final Consumer<Socket> closeCallback;

  private final ReadManagerIO ioAdapter;

  public final Map<Socket, InputStream> inputStreamMap = Collections.synchronizedMap(
      new HashMap<>());
  public final List<Socket> readInProgress = Collections.synchronizedList(new ArrayList<>());
  public final List<String> fileReadInProgress = Collections.synchronizedList(new ArrayList<>());

  public ReadManager(SharedSyncState sharedSyncState, ThreadPoolExecutor workerPool,
      Consumer<Socket> closeCallback, ReadManagerIO ioAdapter) {
    this.sharedSyncState = sharedSyncState;
    this.workerPool = workerPool;
    this.closeCallback = closeCallback;
    this.ioAdapter = ioAdapter;
  }

  public interface ReadManagerIO {

    void createDirectories(Path path) throws IOException;

    OutputStream newOutputStream(Path path) throws IOException;

    void deleteIfExists(Path path) throws IOException;

  }

  public static class IOAdapter implements ReadManagerIO {

    public void createDirectories(Path path) throws IOException {
      Files.createDirectories(path);
    }

    public OutputStream newOutputStream(Path path) throws IOException {
      return Files.newOutputStream(path);
    }

    public void deleteIfExists(Path path) throws IOException {
      Files.deleteIfExists(path);
    }


  }

  /**
   * Executes a thread blocking loop of checking for incoming frames from all clients and responding
   * to them.
   */
  public void executeReadingLoop() {
    while (true) {
      executeReadCheck();
    }
  }

  /**
   * Executes one iteration of the loop that is checking and responding to frames from all clients.
   */
  public void executeReadCheck() {
    Iterator<Socket> iterator = sharedSyncState.clients.iterator();
    Socket lastClient = null;
    while (iterator.hasNext()) {
      try {
        Socket client = iterator.next();
        lastClient = client;
        if (readInProgress.contains(client)) {
          continue;
        }
        InputStream inputStream = getInputStream(client);
        if (inputStream.available() > 0) {
          readInProgress.add(client);
          workerPool.execute(() -> handleRead(client, inputStream));
        }
      } catch (Exception e) {
        e.printStackTrace();
        closeCallback.accept(lastClient);
      }
    }
  }

  /**
   * Reads next frame from given client and responds to it.
   */
  public void handleRead(Socket client, InputStream inputStream) {
    String newPath = null;

    try {
      SyncFrame frame = SyncFrame.readFrame(inputStream);
      System.out.println(
          "Client " + sharedSyncState.clientIds.get(client) + ". Reacting to frame with opcode: "
              + frame.syncOpCode);
      switch (frame.syncOpCode) {
        case SyncOpcode.CLOSE:
          System.out.println(
              "Client " + sharedSyncState.clientIds.get(client) + ". Received CLOSE.");
          closeCallback.accept(client);
          break;
        case SyncOpcode.INIT:
          Set<String> initPaths = Arrays.stream(
                  new String(frame.payload, StandardCharsets.UTF_8).split("\n"))
              .filter(s -> !s.trim().equals(""))
              .collect(Collectors.toSet());
          System.out.println("Client " + sharedSyncState.clientIds.get(client)
              + ". Starting client init. Received files: " + initPaths);
          List<String> newPathsFromClient = new ArrayList<>();
          List<String> newPathsFromServer = new ArrayList<>();

          synchronized (sharedSyncState.availableFilePaths) {
            for (String serverPath : sharedSyncState.availableFilePaths) {
              if (!initPaths.contains(serverPath)) {
                newPathsFromServer.add(serverPath);
              }
            }
            for (String path : initPaths) {
              if (!sharedSyncState.availableFilePaths.contains(path)) {
                newPathsFromClient.add(path);
              }
            }
          }

          //sending all files to client that are missing there
          if (newPathsFromServer.size() > 0) {
            sharedSyncState.writeQueue.put(
                WriteRequest.requestForOneClient(newPathsFromServer, client));
          }

          if (newPathsFromClient.size() > 0) {
            //sending all paths that are new for the server to the client, so it can send them one by one via the normal NEW_FILE mechanism
            sharedSyncState.initQueue.put(new InitData(client, newPathsFromClient));
          }
          break;
        case SyncOpcode.NEW_FILE_PATH:
          newPath = new String(frame.payload, StandardCharsets.UTF_8);
          synchronized (fileReadInProgress) {
            synchronized (sharedSyncState.availableFilePaths) {
              if (fileReadInProgress.contains(newPath)
                  || sharedSyncState.availableFilePaths.contains(newPath)) {
                System.out.println("Client " + sharedSyncState.clientIds.get(client)
                    + " is being close due to collision. Incoming: " + newPath + ". In progress: "
                    + fileReadInProgress + ". Already available: "
                    + sharedSyncState.availableFilePaths);
                closeCallback.accept(client);
                return;
              }
            }
            fileReadInProgress.add(newPath);
          }

          Path path = Paths.get(sharedSyncState.pathToRelativeFolder, newPath);
          Path parent = path.getParent();
          if (parent != null) {
            ioAdapter.createDirectories(parent);
          }
          OutputStream os = ioAdapter.newOutputStream(path);
          SyncFrame fileFrame = SyncFrame.readFrame(inputStream);
          while (fileFrame.syncOpCode != SyncOpcode.NEW_FILE_END) {
            if (!(fileFrame.syncOpCode == SyncOpcode.NEW_FILE_PART)) {
              System.out.println("Client " + sharedSyncState.clientIds.get(client)
                  + ". Received wrong op code in the middle of new file: " + fileFrame.syncOpCode);
              closeCallback.accept(client);
              return;
            }
            os.write(fileFrame.payload);
            fileFrame = SyncFrame.readFrame(inputStream);
          }
          os.close();

          sharedSyncState.availableFilePaths.add(newPath);
          sharedSyncState.writeQueue.put(
              WriteRequest.requestForAllButOneClient(List.of(newPath), client));
          break;
        default:
          throw new IOException("Invalid opcode: " + frame.syncOpCode);
      }

      readInProgress.remove(client);
    } catch (Exception e) {
      if (newPath != null) {
        try {
          ioAdapter.deleteIfExists(Paths.get(sharedSyncState.pathToRelativeFolder, newPath));
        } catch (IOException e2) {
          e2.printStackTrace();
        }
        fileReadInProgress.remove(newPath);
      }
      e.printStackTrace();
      closeCallback.accept(client);
    }
  }

  private InputStream getInputStream(Socket client) throws IOException {
    synchronized (inputStreamMap) {
      InputStream inputStream = inputStreamMap.get(client);
      if (inputStream == null) {
        inputStream = client.getInputStream();
        inputStreamMap.put(client, inputStream);
      }
      return inputStream;
    }
  }
}

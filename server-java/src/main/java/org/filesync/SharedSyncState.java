package org.filesync;

import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class SharedSyncState {

  public final CopyOnWriteArrayList<Socket> clients;
  public final Map<Socket, String> clientIds = new HashMap<>();
  public final List<String> availableFilePaths;
  public final String pathToRelativeFolder;
  public final LinkedBlockingQueue<WriteRequest> writeQueue = new LinkedBlockingQueue<>();
  public final LinkedBlockingQueue<InitData> initQueue = new LinkedBlockingQueue<>();

  public SharedSyncState(CopyOnWriteArrayList<Socket> clients, List<String> availableFilePaths,
      String pathToRelativeFolder) {
    this.clients = clients;
    this.availableFilePaths = availableFilePaths;
    this.pathToRelativeFolder = pathToRelativeFolder;
  }
}

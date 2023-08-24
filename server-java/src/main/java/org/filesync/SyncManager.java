package org.filesync;

import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.filesync.ReadManager.IOAdapter;

class SyncManager {

  private final SharedSyncState sharedSyncState;
  private final ReadManager readManager;
  private final WriteManager writeManager;

  SyncManager(SharedSyncState sharedSyncState, EnvVars vars) {
    this.sharedSyncState = sharedSyncState;
    int corePoolSize = 4;
    int maxPoolSize = 32 + 6; //max client size + misc tasks
    long keepAliveTime = 60;

    ThreadPoolExecutor workerPool = new ThreadPoolExecutor(
        corePoolSize,
        maxPoolSize,
        keepAliveTime,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>()
    );
    this.readManager = new ReadManager(sharedSyncState, workerPool, this::close, new IOAdapter());
    this.writeManager = new WriteManager(sharedSyncState, vars, workerPool, this::close);
  }

  public void start() {
    new Thread(writeManager::executeWritingLoop).start();
    new Thread(readManager::executeReadingLoop).start();
  }

  private void close(Socket client) {
    if (client != null) {
      String clientId = sharedSyncState.clientIds.remove(client);
      System.out.println("Client " + clientId + " closing.");
      sharedSyncState.clients.remove(client);
      readManager.inputStreamMap.remove(client);
      readManager.readInProgress.remove(client);
      writeManager.outputStreamMap.remove(client);
      writeManager.writeInProgress.remove(client);
      try {
        SyncFrame.writeFrame(SyncFrame.closeFrame(), client.getOutputStream());
      } catch (Exception e) {
        e.printStackTrace();
      }
      try {
        client.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
      System.out.println("Client " + clientId + " closed: " + client.getInetAddress());
    }
  }


}

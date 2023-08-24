package org.filesync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

class WriteManager {

  private final SharedSyncState sharedSyncState;
  private final EnvVars vars;
  private final ThreadPoolExecutor workerPool;
  private final Consumer<Socket> closeCallback;

  public final Map<Socket, OutputStream> outputStreamMap = Collections.synchronizedMap(
      new HashMap<>());
  public final List<Socket> writeInProgress = Collections.synchronizedList(new ArrayList<>());

  public WriteManager(SharedSyncState sharedSyncState, EnvVars vars, ThreadPoolExecutor workerPool,
      Consumer<Socket> closeCallback) {
    this.sharedSyncState = sharedSyncState;
    this.vars = vars;
    this.workerPool = workerPool;
    this.closeCallback = closeCallback;
  }

  public void executeWritingLoop() {
    while (true) {
      executeWriteCheck();
    }
  }

  public void executeWriteCheck() {
    try {
      InitData initData = sharedSyncState.initQueue.poll();
      if (initData != null && !initData.client.isClosed()) {
        if (writeInProgress.contains(initData.client)) {
          sharedSyncState.initQueue.put(initData);

        } else {
          writeInProgress.add(initData.client);
          workerPool.execute(() -> handleInitResponse(initData));
        }
      }

      WriteRequest writeRequest = sharedSyncState.writeQueue.poll();
      if (writeRequest != null) {
        if (writeRequest.relevantClient != null) {
          if (writeInProgress.contains(writeRequest.relevantClient)) {
            sharedSyncState.writeQueue.put(writeRequest);
          } else {
            writeInProgress.add(writeRequest.relevantClient);
            workerPool.execute(() -> handleSingleWrite(writeRequest));
          }
        } else {
          boolean forAtLeastOneClientThereIsAlreadyAWriteOngoing;
          //if any of the clients which is not the ignored one currently has an ongoing write, we re-add this request
          synchronized (writeInProgress) {
            forAtLeastOneClientThereIsAlreadyAWriteOngoing = writeInProgress.stream()
                .anyMatch(client -> !client.equals(writeRequest.notRelevantClient));
          }
          if (forAtLeastOneClientThereIsAlreadyAWriteOngoing) {
            sharedSyncState.writeQueue.put(writeRequest);
          } else {
            List<Socket> relevantClients = new ArrayList<>();
            for (Socket client : sharedSyncState.clients) {
              if (!client.equals(writeRequest.notRelevantClient) && !client.isClosed()) {
                relevantClients.add(client);
              }
            }
            writeInProgress.addAll(relevantClients);
            workerPool.execute(() -> handleMultiWrite(relevantClients, writeRequest));
          }
        }
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }


  private OutputStream getOutputStream(Socket client) throws IOException {
    synchronized (outputStreamMap) {
      OutputStream outputStream = outputStreamMap.get(client);
      if (outputStream == null) {
        outputStream = client.getOutputStream();
        outputStreamMap.put(client, outputStream);
      }
      return outputStream;
    }
  }

  private void handleInitResponse(InitData initData) {
    try {
      System.out.println(
          "Writing init response, requesting files: " + initData.filePathsMissingOnServer);
      OutputStream outputStream = getOutputStream(initData.client);
      byte[] payload = String.join("\n", initData.filePathsMissingOnServer)
          .getBytes(StandardCharsets.UTF_8);
      SyncFrame.writeFrame(new SyncFrame(SyncOpcode.INIT, payload), outputStream);
      writeInProgress.remove(initData.client);
    } catch (IOException e) {
      e.printStackTrace();
      closeCallback.accept(initData.client);
    }

  }

  private void handleSingleWrite(WriteRequest writeRequest) {
    try {
      System.out.println("Writing files to single client: " + writeRequest.paths);
      OutputStream outputStream = getOutputStream(writeRequest.relevantClient);
      for (String newPathFromServer : writeRequest.paths) {
        writeFileToClient(newPathFromServer, outputStream);
      }
      writeInProgress.remove(writeRequest.relevantClient);
    } catch (IOException e) {
      e.printStackTrace();
      closeCallback.accept(writeRequest.relevantClient);
    }

  }

  private void writeFileToClient(String path, OutputStream outputStream)
      throws IOException {
    SyncFrame.writeFrame(new SyncFrame(SyncOpcode.NEW_FILE_PATH,
        path.getBytes(StandardCharsets.UTF_8)), outputStream);
    try (InputStream is = Files.newInputStream(
        Paths.get(sharedSyncState.pathToRelativeFolder, path))) {
      while (true) {
        byte[] chunk = is.readNBytes(vars.chunkSize);
        SyncFrame.writeFrame(new SyncFrame(SyncOpcode.NEW_FILE_PART,
            chunk), outputStream);
        if (chunk.length < vars.chunkSize) {
          break;
        }
      }
    }
    SyncFrame.writeFrame(new SyncFrame(SyncOpcode.NEW_FILE_END,
        path.getBytes(StandardCharsets.UTF_8)), outputStream);

  }

  private void handleMultiWrite(List<Socket> relevantClients, WriteRequest writeRequest) {
    try {
      System.out.println(
          "Writing files to " + relevantClients.size() + " clients: " + writeRequest.paths);
      var outputStreams = new ArrayList<OutputStream>(relevantClients.size());
      for (Socket client : relevantClients) {
        OutputStream outputStream = getOutputStream(client);
        outputStreams.add(outputStream);
      }
      for (String path : writeRequest.paths) {
        //the idea was to make this multi-threaded too, but the impl was slightly unstable
        //so I removed it due to time-constraints and made it single-threaded again
        byte[] pathPayload = path.getBytes(StandardCharsets.UTF_8);
        SyncFrame startFrame = new SyncFrame(SyncOpcode.NEW_FILE_PATH,
            pathPayload);
        for (OutputStream os : outputStreams) {
          SyncFrame.writeFrame(startFrame, os);
        }

        try (InputStream is = Files.newInputStream(
            Paths.get(sharedSyncState.pathToRelativeFolder, path))) {
          while (true) {
            byte[] chunk = is.readNBytes(vars.chunkSize);
            SyncFrame chunkFrame = new SyncFrame(SyncOpcode.NEW_FILE_PART,
                chunk);
            for (OutputStream os : outputStreams) {
              SyncFrame.writeFrame(chunkFrame, os);
            }
            if (chunk.length < vars.chunkSize) {
              break;
            }
          }
        }

        SyncFrame endFrame = new SyncFrame(SyncOpcode.NEW_FILE_END,
            pathPayload);
        for (OutputStream os : outputStreams) {
          SyncFrame.writeFrame(endFrame, os);
        }

      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    writeInProgress.removeAll(relevantClients);
  }
}

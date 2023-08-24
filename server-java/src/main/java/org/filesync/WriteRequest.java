package org.filesync;

import java.net.Socket;
import java.util.List;

class WriteRequest {

  public final List<String> paths;
  public final Socket relevantClient;
  public final Socket notRelevantClient;

  private WriteRequest(List<String> paths, Socket relevantClient, Socket notRelevantClient) {
    this.paths = paths;
    this.relevantClient = relevantClient;
    this.notRelevantClient = notRelevantClient;
  }

  public static WriteRequest requestForOneClient(List<String> paths, Socket relevantClient) {
    return new WriteRequest(paths, relevantClient, null);
  }

  public static WriteRequest requestForAllButOneClient(List<String> paths,
      Socket notRelevantClient) {
    return new WriteRequest(paths, null, notRelevantClient);
  }
}

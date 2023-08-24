package org.filesync;

import java.net.Socket;
import java.util.List;

class InitData {

  public final Socket client;
  public final List<String> filePathsMissingOnServer;

  InitData(Socket client, List<String> filePathsMissingOnServer) {
    this.client = client;
    this.filePathsMissingOnServer = filePathsMissingOnServer;
  }
}

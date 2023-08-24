package org.filesync;

class EnvVars {

  public final Integer serverPort;
  public final String pathToFolder;
  public final String apiKey;
  public final int chunkSize;
  public final boolean debugMode;

  public EnvVars(String[] args) {
    debugMode = isDebugMode(args);
    serverPort = getEnvInt("SERVER_PORT", 8080);
    pathToFolder = getEnv("SERVER_FOLDER", "mounted-server-folder");
    apiKey = getEnv("API_KEY", "SUPER-SECRET-API-KEY");
    chunkSize = getEnvInt("CHUNK_SIZE", 1000000 * 10);

    if (serverPort == -1 || pathToFolder == null || apiKey == null || chunkSize == -1) {
      String errorMessage = "Missing required environment variable(s): ";
      StringBuilder missingVars = new StringBuilder();
      missingVars.append("serverPort: ").append(serverPort).append(", ");
      missingVars.append("pathToFolder: ").append(pathToFolder).append(", ");
      missingVars.append("apiKey: ").append(apiKey != null ? "censored" : null).append(", ");
      missingVars.append("chunkSize: ").append(chunkSize).append(", ");
      missingVars.setLength(missingVars.length() - 2);
      throw new RuntimeException(errorMessage + missingVars);
    }
  }

  private String getEnv(String name, String defaultValue) {
    String value = System.getenv(name);
    if (value == null && !debugMode) {
      return null;
    }
    return (value != null) ? value : defaultValue;
  }

  private int getEnvInt(String name, int defaultValue) {
    String value = System.getenv(name);
    if (value == null && !debugMode) {
      return -1;
    }
    return (value != null) ? Integer.parseInt(value) : defaultValue;
  }

  private boolean isDebugMode(String[] args) {
    for (String arg : args) {
      if (arg.equals("--debug")) {
        return true;
      }
    }
    return false;
  }

}

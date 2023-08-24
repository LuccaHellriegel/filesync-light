package org.filesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.filesync.ReadManager.IOAdapter;
import org.filesync.ReadManager.ReadManagerIO;
import org.junit.jupiter.api.Test;

class ReadManagerTest {

  public static InputStream setupInputStream(byte[] bytes) {
    return new ByteArrayInputStream(bytes);
  }

  public static Socket mockSocket(List<SyncFrame> frames) {
    Socket client = mock(Socket.class);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    for (SyncFrame frame : frames) {
      try {
        outputStream.write(frame.toBytes());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    byte[] combinedArray = outputStream.toByteArray();

    try {
      InputStream inputStream = setupInputStream(combinedArray);
      when(client.getInputStream()).thenAnswer((invocation) -> inputStream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return client;
  }

  public static SharedSyncState createSharedSyncState() {
    return new SharedSyncState(new CopyOnWriteArrayList<>(), new ArrayList<>(), "");
  }

  public static ReadManager createManager(SharedSyncState sharedSyncState,
      ThreadPoolExecutor workerPool,
      Consumer<Socket> closeCallback) {
    return new ReadManager(sharedSyncState,
        workerPool, closeCallback, new IOAdapter());
  }

  public static ReadManager createManager(SharedSyncState sharedSyncState,
      ThreadPoolExecutor workerPool,
      Consumer<Socket> closeCallback, ReadManagerIO ioAdapter) {
    return new ReadManager(sharedSyncState,
        workerPool, closeCallback, ioAdapter);
  }

  public static class FakeReadMangerIO implements ReadManagerIO {

    private final OutputStream outputStream;
    public final List<String> actions = new ArrayList<>();

    public FakeReadMangerIO(OutputStream outputStream) {
      this.outputStream = outputStream;
    }

    public void createDirectories(Path path) {
      actions.add("createDirectories: " + path);
    }

    public OutputStream newOutputStream(Path path) {
      actions.add("newOutputStream: " + path);
      return outputStream;
    }

    public void deleteIfExists(Path path) {
      actions.add("deleteIfExists: " + path);
    }
  }

  public static ThreadPoolExecutor createWorkerPool() {
    return new ThreadPoolExecutor(
        1,
        1,
        1,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>()
    );
  }

  public static Consumer<Socket> createCloseCallback(AtomicReference<Integer> counter,
      AtomicReference<Socket> result) {
    return (socket) -> {
      Integer counterVal = counter.get();
      counter.compareAndSet(counterVal, counterVal + 1);
      result.set(socket);
    };
  }

  public static SyncFrame initFrame(List<String> filePaths) {
    return new SyncFrame(SyncOpcode.INIT,
        String.join("\n", filePaths).getBytes(
            StandardCharsets.UTF_8));
  }

  @Test
  public void sending_close_frame_executes_close_callback() throws IOException {
    // GIVEN
    Socket client = mockSocket(List.of(SyncFrame.closeFrame()));
    AtomicReference<Integer> counter = new AtomicReference<>(0);
    AtomicReference<Socket> result = new AtomicReference<>(null);
    Consumer<Socket> closeCallback = createCloseCallback(counter, result);
    SharedSyncState sharedSyncState = createSharedSyncState();
    sharedSyncState.clients.add(client);
    ReadManager manager = createManager(sharedSyncState, createWorkerPool(), closeCallback);

    // WHEN
    manager.handleRead(client, client.getInputStream());

    // THEN
    assertEquals(1, counter.get());
    assertEquals(client, result.get());
    assertEquals(0, client.getInputStream().available());
  }

  @Test
  public void already_in_progress_clients_are_not_read_again_until_the_work_finishes()
      throws InterruptedException {
    // GIVEN
    Socket client = mockSocket(List.of(SyncFrame.closeFrame(),
        SyncFrame.closeFrame())); //we add two close frames, so that two reads are executed - normally the close callback would clean up the state and disallow this
    SharedSyncState sharedSyncState = createSharedSyncState();
    sharedSyncState.clients.add(client);
    AtomicReference<Boolean> closeCallbackCanFinish = new AtomicReference<>(false);
    ThreadPoolExecutor workerPool = createWorkerPool();
    ReadManager manager = createManager(sharedSyncState, workerPool, (socket) -> {
      //we know that the close callback is executed, so we simulate work in that thread by blocking it
      //until after the second read check
      //if the read check would try to process clients twice, the readInProgress list would contain more than one value
      while (!closeCallbackCanFinish.get()) {
      }
    });

    // THEN
    //first check adds it to the list
    manager.executeReadCheck();
    assertEquals(List.of(client), manager.readInProgress);
    //second check changes nothing because its already added
    manager.executeReadCheck();
    assertEquals(List.of(client), manager.readInProgress);
    //after finish, its removed
    closeCallbackCanFinish.set(true);
    workerPool.awaitTermination(1L, TimeUnit.SECONDS);
    assertEquals(List.of(), manager.readInProgress);
    //checking again, adds it again
    manager.executeReadCheck();
    assertEquals(List.of(client), manager.readInProgress);
  }

  @Test
  public void clients_without_frames_are_not_processed() {
    // GIVEN
    Socket client = mockSocket(List.of());
    SharedSyncState sharedSyncState = createSharedSyncState();
    sharedSyncState.clients.add(client);
    ThreadPoolExecutor workerPool = createWorkerPool();
    ReadManager manager = createManager(sharedSyncState, workerPool, (socket) -> {
      //will not be executed
      throw new RuntimeException();
    });

    // THEN
    //check does not add it to the list because it has no frames
    manager.executeReadCheck();
    assertEquals(List.of(), manager.readInProgress);
  }

  @Test
  public void clients_can_be_added_later() {
    // GIVEN
    Socket client = mockSocket(List.of(SyncFrame.closeFrame()));
    SharedSyncState sharedSyncState = createSharedSyncState();
    ThreadPoolExecutor workerPool = createWorkerPool();
    AtomicReference<Boolean> closeCallbackCanFinish = new AtomicReference<>(false);
    ReadManager manager = createManager(sharedSyncState, workerPool, (socket) -> {
      while (!closeCallbackCanFinish.get()) {
      }
    });

    // THEN
    //no clients are in list, so nothing in progress
    manager.executeReadCheck();
    assertEquals(List.of(), manager.readInProgress);
    //after adding it to the clients lists, its in progress as long as we block the close-callback
    sharedSyncState.clients.add(client);
    manager.executeReadCheck();
    assertEquals(List.of(client), manager.readInProgress);
    closeCallbackCanFinish.set(true);
  }

  @Test
  public void init_adds_files_to_write_queue_that_are_missing_on_client() throws IOException {
    // GIVEN
    SyncFrame initFrameFromClient = initFrame(List.of());
    Socket client = mockSocket(List.of(initFrameFromClient));
    SharedSyncState sharedSyncState = createSharedSyncState();
    sharedSyncState.availableFilePaths.addAll(List.of("serverPath1", "serverPath2"));
    sharedSyncState.clients.add(client);
    ThreadPoolExecutor workerPool = createWorkerPool();
    ReadManager manager = createManager(sharedSyncState, workerPool, (socket) -> {
      //will not be executed
      throw new RuntimeException();
    });

    // WHEN
    manager.handleRead(client, client.getInputStream());

    // THEN
    WriteRequest writeRequest = sharedSyncState.writeQueue.poll();
    assert writeRequest != null;
    assertEquals(client, writeRequest.relevantClient);
    assertNull(writeRequest.notRelevantClient);
    assertEquals(List.of("serverPath1", "serverPath2"), writeRequest.paths);
    assertNull(sharedSyncState.initQueue.poll());
  }

  @Test
  public void init_adds_files_to_init_queue_that_are_missing_on_server() throws IOException {
    // GIVEN
    SyncFrame initFrameFromClient = initFrame(List.of("clientPath1", "clientPath2"));
    Socket client = mockSocket(List.of(initFrameFromClient));
    SharedSyncState sharedSyncState = createSharedSyncState();
    sharedSyncState.availableFilePaths.removeIf(s -> true);
    sharedSyncState.clients.add(client);
    ThreadPoolExecutor workerPool = createWorkerPool();
    ReadManager manager = createManager(sharedSyncState, workerPool, (socket) -> {
      //will not be executed
      throw new RuntimeException();
    });

    // WHEN
    manager.handleRead(client, client.getInputStream());

    // THEN
    InitData initData = sharedSyncState.initQueue.poll();
    assert initData != null;
    assertEquals(client, initData.client);
    assertEquals(List.of("clientPath1", "clientPath2"), initData.filePathsMissingOnServer);
    assertNull(sharedSyncState.writeQueue.poll());
  }

  @Test
  public void on_init_files_are_shared_between_client_and_server() throws IOException {
    // GIVEN
    SyncFrame initFrameFromClient = initFrame(List.of("sharedPath", "clientPath1"));
    Socket client = mockSocket(List.of(initFrameFromClient));
    SharedSyncState sharedSyncState = createSharedSyncState();
    sharedSyncState.availableFilePaths.addAll(List.of("sharedPath", "serverPath1"));
    sharedSyncState.clients.add(client);
    ThreadPoolExecutor workerPool = createWorkerPool();
    ReadManager manager = createManager(sharedSyncState, workerPool, (socket) -> {
      //will not be executed
      throw new RuntimeException();
    });

    // WHEN
    manager.handleRead(client, client.getInputStream());

    // THEN
    InitData initData = sharedSyncState.initQueue.poll();
    assert initData != null;
    assertEquals(client, initData.client);
    assertEquals(List.of("clientPath1"), initData.filePathsMissingOnServer);
    WriteRequest writeRequest = sharedSyncState.writeQueue.poll();
    assert writeRequest != null;
    assertEquals(client, writeRequest.relevantClient);
    assertNull(writeRequest.notRelevantClient);
    assertEquals(List.of("serverPath1"), writeRequest.paths);
  }

  @Test
  public void sending_duplicate_path_closes_socket() throws IOException {
    // GIVEN
    Socket client = mockSocket(List.of(new SyncFrame(SyncOpcode.NEW_FILE_PATH, "duplicate".getBytes(
        StandardCharsets.UTF_8))));
    AtomicReference<Integer> counter = new AtomicReference<>(0);
    AtomicReference<Socket> result = new AtomicReference<>(null);
    Consumer<Socket> closeCallback = createCloseCallback(counter, result);
    SharedSyncState sharedSyncState = createSharedSyncState();
    sharedSyncState.clients.add(client);
    sharedSyncState.availableFilePaths.add("duplicate");
    ReadManager manager = createManager(sharedSyncState, createWorkerPool(), closeCallback);

    // WHEN
    manager.handleRead(client, client.getInputStream());

    // THEN
    assertEquals(1, counter.get());
    assertEquals(client, result.get());
    assertEquals(0, client.getInputStream().available());
  }

  @Test
  public void sending_path_that_is_currently_being_uploaded_by_another_client_closes_socket()
      throws IOException {
    // GIVEN
    Socket client = mockSocket(List.of(new SyncFrame(SyncOpcode.NEW_FILE_PATH, "duplicate".getBytes(
        StandardCharsets.UTF_8))));
    AtomicReference<Integer> counter = new AtomicReference<>(0);
    AtomicReference<Socket> result = new AtomicReference<>(null);
    Consumer<Socket> closeCallback = createCloseCallback(counter, result);
    SharedSyncState sharedSyncState = createSharedSyncState();
    sharedSyncState.clients.add(client);
    ReadManager manager = createManager(sharedSyncState, createWorkerPool(), closeCallback);
    manager.fileReadInProgress.add("duplicate");

    // WHEN
    manager.handleRead(client, client.getInputStream());

    // THEN
    assertEquals(1, counter.get());
    assertEquals(client, result.get());
    assertEquals(0, client.getInputStream().available());
  }

  @Test
  public void incoming_file_is_written_to_server_and_added_to_write_queue() throws IOException {
    // GIVEN
    Socket client = mockSocket(List.of(
        new SyncFrame(SyncOpcode.NEW_FILE_PATH, "parent/path.txt".getBytes(
            StandardCharsets.UTF_8)),
        new SyncFrame(SyncOpcode.NEW_FILE_PART, "part1".getBytes(
            StandardCharsets.UTF_8)),
        new SyncFrame(SyncOpcode.NEW_FILE_PART, "part2".getBytes(
            StandardCharsets.UTF_8)),
        new SyncFrame(SyncOpcode.NEW_FILE_END, "parent/path.txt".getBytes(
            StandardCharsets.UTF_8))
    ));
    SharedSyncState sharedSyncState = createSharedSyncState();
    sharedSyncState.clients.add(client);
    ByteArrayOutputStream outputStream = spy(new ByteArrayOutputStream());
    FakeReadMangerIO fakeReadMangerIO = new FakeReadMangerIO(outputStream);
    ReadManager manager = createManager(sharedSyncState, createWorkerPool(), (socket) -> {
      //will not be executed
      throw new RuntimeException();
    }, fakeReadMangerIO);

    // WHEN
    manager.handleRead(client, client.getInputStream());

    // THEN
    assertEquals(List.of("createDirectories: parent", "newOutputStream: parent/path.txt"),
        fakeReadMangerIO.actions);
    assertEquals("part1part2", outputStream.toString(StandardCharsets.UTF_8));
    WriteRequest writeRequest = sharedSyncState.writeQueue.poll();
    assert writeRequest != null;
    assertEquals(client, writeRequest.notRelevantClient);
    assertNull(writeRequest.relevantClient);
    assertEquals(List.of("parent/path.txt"),writeRequest.paths);
  }



}
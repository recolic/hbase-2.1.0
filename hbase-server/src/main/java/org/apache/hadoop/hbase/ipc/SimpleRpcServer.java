/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.ipc;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

//import com.oracle.tools.packager.Log;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.Server;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.hadoop.hbase.monitoring.MonitoredRPCHandler;
import org.apache.hadoop.hbase.security.HBasePolicyProvider;
import org.apache.hbase.thirdparty.com.google.protobuf.BlockingService;
import org.apache.hbase.thirdparty.com.google.protobuf.Descriptors.MethodDescriptor;
import org.apache.hbase.thirdparty.com.google.protobuf.Message;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.authorize.ServiceAuthorizationManager;

import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * The RPC server with native java NIO implementation deriving from Hadoop to
 * host protobuf described Services. It's the original one before HBASE-17262,
 * and the default RPC server for now.
 *
 * An RpcServer instance has a Listener that hosts the socket.  Listener has fixed number
 * of Readers in an ExecutorPool, 10 by default.  The Listener does an accept and then
 * round robin a Reader is chosen to do the read.  The reader is registered on Selector.  Read does
 * total read off the channel and the parse from which it makes a Call.  The call is wrapped in a
 * CallRunner and passed to the scheduler to be run.  Reader goes back to see if more to be done
 * and loops till done.
 *
 * <p>Scheduler can be variously implemented but default simple scheduler has handlers to which it
 * has given the queues into which calls (i.e. CallRunner instances) are inserted.  Handlers run
 * taking from the queue.  They run the CallRunner#run method on each item gotten from queue
 * and keep taking while the server is up.
 *
 * CallRunner#run executes the call.  When done, asks the included Call to put itself on new
 * queue for Responder to pull from and return result to client.
 *
 * @see BlockingRpcClient
 */
@InterfaceAudience.LimitedPrivate({HBaseInterfaceAudience.CONFIG})
public class SimpleRpcServer extends RpcServer {
  private  RdmaNative rdma =null;

      //TODO isRdma get from conf
      

  protected int port;                             // port we listen on
  protected int rdmaPort;//rdma listener port
  protected InetSocketAddress address;            // inet address we listen on
  private int readThreads;                        // number of read threads


  protected int socketSendBufferSize;
  protected final long purgeTimeout;    // in milliseconds

  // maintains the set of client connections and handles idle timeouts
  private ConnectionManager connectionManager;
  private Listener listener = null;
  private RdmaListener rdmalistener = null;
  protected SimpleRpcServerResponder responder = null;

  // public String getHostAddr(){
  //   return listener.acceptChannel.hostAddress;
  // }
  /** Listens on the socket. Creates jobs for the handler threads*/
  private class Listener extends Thread {

    private ServerSocketChannel acceptChannel = null; //the accept channel
    private Selector selector = null; //the selector that we use for the server
    private Reader[] readers = null;
    private int currentReader = 0;
    private final int readerPendingConnectionQueueLength;

    private ExecutorService readPool;


    public Listener(final String name) throws IOException {
      super(name);
      // The backlog of requests that we will have the serversocket carry.
      int backlogLength = conf.getInt("hbase.ipc.server.listen.queue.size", 128);
      readerPendingConnectionQueueLength =
          conf.getInt("hbase.ipc.server.read.connection-queue.size", 100);
      // Create a new server socket and set to non blocking mode
      acceptChannel = ServerSocketChannel.open();
      acceptChannel.configureBlocking(false);

      // Bind the server socket to the binding addrees (can be different from the default interface)
      bind(acceptChannel.socket(), bindAddress, backlogLength);
      port = acceptChannel.socket().getLocalPort(); //Could be an ephemeral port
      address = (InetSocketAddress)acceptChannel.socket().getLocalSocketAddress();
      // create a selector;
      selector = Selector.open();

      readers = new Reader[readThreads];
      // Why this executor thing? Why not like hadoop just start up all the threads? I suppose it
      // has an advantage in that it is easy to shutdown the pool.
      readPool = Executors.newFixedThreadPool(readThreads,
        new ThreadFactoryBuilder().setNameFormat(
          "Reader=%d,bindAddress=" + bindAddress.getHostName() +
          ",port=" + port).setDaemon(true)
        .setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER).build());
      for (int i = 0; i < readThreads; ++i) {
        Reader reader = new Reader();
        readers[i] = reader;
        readPool.execute(reader);
      }
      LOG.info(getName() + ": started " + readThreads + " reader(s) listening on port=" + port);

      // Register accepts on the server socket with the selector.
      acceptChannel.register(selector, SelectionKey.OP_ACCEPT);
      this.setName("Listener,port=" + port);
      this.setDaemon(true);
    }


    private class Reader implements Runnable {
      final private LinkedBlockingQueue<SimpleServerRpcConnection> pendingConnections;
      private final Selector readSelector;

      Reader() throws IOException {
        this.pendingConnections = new LinkedBlockingQueue<>(readerPendingConnectionQueueLength);
        this.readSelector = Selector.open();
      }

      @Override
      public void run() {
        try {
          doRunLoop();
        } finally {
          try {
            readSelector.close();
          } catch (IOException ioe) {
            LOG.error(getName() + ": error closing read selector in " + getName(), ioe);
          }
        }
      }

      private synchronized void doRunLoop() {
        while (running) {
          try {
            // Consume as many connections as currently queued to avoid
            // unbridled acceptance of connections that starves the select
            int size = pendingConnections.size();
            for (int i=size; i>0; i--) {
              SimpleServerRpcConnection conn = pendingConnections.take();
              conn.channel.register(readSelector, SelectionKey.OP_READ, conn);
            }
            readSelector.select();
            Iterator<SelectionKey> iter = readSelector.selectedKeys().iterator();
            while (iter.hasNext()) {
              SelectionKey key = iter.next();
              iter.remove();
              if (key.isValid()) {
                if (key.isReadable()) {
                  //LOG.warn("normal reader doread");
                  doRead(key);
                }
              }
              key = null;
            }
          } catch (InterruptedException e) {
            if (running) {                      // unexpected -- log it
              LOG.info(Thread.currentThread().getName() + " unexpectedly interrupted", e);
            }
          } catch (CancelledKeyException e) {
            LOG.error(getName() + ": CancelledKeyException in Reader", e);
          } catch (IOException ex) {
            LOG.info(getName() + ": IOException in Reader", ex);
          }
        }
      }

      /**
       * Updating the readSelector while it's being used is not thread-safe,
       * so the connection must be queued.  The reader will drain the queue
       * and update its readSelector before performing the next select
       */
      public void addConnection(SimpleServerRpcConnection conn) throws IOException {
        pendingConnections.add(conn);
        readSelector.wakeup();
      }
    }

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="IS2_INCONSISTENT_SYNC",
      justification="selector access is not synchronized; seems fine but concerned changing " +
        "it will have per impact")
    public void run() {
      LOG.info(getName() + ": starting");
      connectionManager.startIdleScan();
      while (running) {
        SelectionKey key = null;
        try {
          selector.select(); // FindBugs IS2_INCONSISTENT_SYNC
          Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
          while (iter.hasNext()) {
            key = iter.next();
            iter.remove();
            try {
              if (key.isValid()) {
                if (key.isAcceptable())
                  doAccept(key);
              }
            } catch (IOException ignored) {
              if (LOG.isTraceEnabled()) LOG.trace("ignored", ignored);
            }
            key = null;
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.info(getName() + ": exiting on OutOfMemoryError");
              closeCurrentConnection(key, e);
              connectionManager.closeIdle(true);
              return;
            }
          } else {
            // we can run out of memory if we have too many threads
            // log the event and sleep for a minute and give
            // some thread(s) a chance to finish
            LOG.warn(getName() + ": OutOfMemoryError in server select", e);
            closeCurrentConnection(key, e);
            connectionManager.closeIdle(true);
            try {
              Thread.sleep(60000);
            } catch (InterruptedException ex) {
              LOG.debug("Interrupted while sleeping");
            }
          }
        } catch (Exception e) {
          closeCurrentConnection(key, e);
        }
      }
      LOG.info(getName() + ": stopping");
      synchronized (this) {
        try {
          acceptChannel.close();
          selector.close();
        } catch (IOException ignored) {
          if (LOG.isTraceEnabled()) LOG.trace("ignored", ignored);
        }

        selector= null;
        acceptChannel= null;

        // close all connections
        connectionManager.stopIdleScan();
        connectionManager.closeAll();
      }
    }

    private void closeCurrentConnection(SelectionKey key, Throwable e) {
      if (key != null) {
        SimpleServerRpcConnection c = (SimpleServerRpcConnection)key.attachment();
        if (c != null) {
          closeConnection(c);
          key.attach(null);
        }
      }
    }

    InetSocketAddress getAddress() {
      return address;
    }

    void doAccept(SelectionKey key) throws InterruptedException, IOException, OutOfMemoryError {
      ServerSocketChannel server = (ServerSocketChannel) key.channel();
      SocketChannel channel;
      while ((channel = server.accept()) != null) {
        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(tcpNoDelay);
        channel.socket().setKeepAlive(tcpKeepAlive);
        Reader reader = getReader();
        SimpleServerRpcConnection c = connectionManager.register(channel);
        // If the connectionManager can't take it, close the connection.
        if (c == null) {
          if (channel.isOpen()) {
            IOUtils.cleanup(null, channel);
          }
          continue;
        }
        key.attach(c);  // so closeCurrentConnection can get the object
        reader.addConnection(c);
      }
    }

    void doRead(SelectionKey key) throws InterruptedException {
      int count;
      SimpleServerRpcConnection c = (SimpleServerRpcConnection) key.attachment();
      if (c == null) {
        return;
      }
      c.setLastContact(System.currentTimeMillis());
      try {
        count = c.readAndProcess();
      } catch (InterruptedException ieo) {
        LOG.info(Thread.currentThread().getName() + ": readAndProcess caught InterruptedException", ieo);
        throw ieo;
      } catch (Exception e) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Caught exception while reading:", e);
        }
        count = -1; //so that the (count < 0) block is executed
      }
      if (count < 0) {
        closeConnection(c);
        c = null;
      } else {
        c.setLastContact(System.currentTimeMillis());
      }
    }

    synchronized void doStop() {
      if (selector != null) {
        selector.wakeup();
        Thread.yield();
      }
      if (acceptChannel != null) {
        try {
          acceptChannel.socket().close();
        } catch (IOException e) {
          LOG.info(getName() + ": exception in closing listener socket. " + e);
        }
      }
      readPool.shutdownNow();
    }

    // The method that will return the next reader to work with
    // Simplistic implementation of round robin for now
    Reader getReader() {
      currentReader = (currentReader + 1) % readers.length;
      return readers[currentReader];
    }
  }
  private class RdmaListener extends Thread {

    private Reader[] readers = null;
    private int currentReader = 0;
    private final int readerPendingConnectionQueueLength;

    private ExecutorService readPool;

    public RdmaListener(final String name, final int rdmaport) throws IOException {
      super(name);
      
      // The backlog of requests that we will have the serversocket carry.
      int backlogLength = conf.getInt("hbase.ipc.server.listen.queue.size", 128);
      readerPendingConnectionQueueLength =
          conf.getInt("hbase.ipc.server.read.connection-queue.size", 100);
      // Create a new server socket and set to non blocking mode

      if(port!=16020){//only for regionserver
        LOG.info("drop for the not regionserver"+name);
        return;
      }
      //LOG.warn("Server Loading the rdmalib");
      rdma= new RdmaNative();
      //LOG.warn("Server bind! the rdmalib");
      rdma.rdmaBind(rdmaport);

      readers = new Reader[readThreads];
      // Why this executor thing? Why not like hadoop just start up all the threads? I suppose it
      // has an advantage in that it is easy to shutdown the pool.
      readPool = Executors.newFixedThreadPool(readThreads,
        new ThreadFactoryBuilder().setNameFormat(
          "Reader=%d,bindAddress=" + bindAddress.getHostName() +
          ",port=" + port).setDaemon(true)
        .setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER).build());
      for (int i = 0; i < readThreads; ++i) {
        Reader reader = new Reader();
        readers[i] = reader;
        readPool.execute(reader);
      }
      LOG.info("rdmaListener: started " + readThreads + " reader(s) listening on port=" + rdmaPort);

      // Register accepts on the server socket with the selector.
      //acceptChannel.register(selector, SelectionKey.OP_ACCEPT);
      this.setName("RdmaListener,port=" + rdmaPort);
      this.setDaemon(true);
    }


    private class Reader implements Runnable {
      final private LinkedBlockingQueue<SimpleServerRdmaRpcConnection> pendingConnections;
      //private final Selector readSelector;

      Reader() throws IOException {
        this.pendingConnections = new LinkedBlockingQueue<>(readerPendingConnectionQueueLength);
        //this.readSelector = Selector.open();
      }

      @Override
      public void run() {
        SimpleRpcServer.LOG.info("RDMA reader starting");
          doRunLoop();
      }

      private synchronized void doRunLoop() {
        while (running) {
          try {
            //LOG.warn("RDMA reader running");
            Iterator<SimpleServerRdmaRpcConnection> iter = pendingConnections.iterator();
            while (iter.hasNext()) {
              //LOG.warn("RDMA reader running");
              SimpleServerRdmaRpcConnection  rdma_conn= iter.next();
                if (rdma_conn.isReadable()) {
                  doRead(rdma_conn);
                  //closeRdmaConnection(rdma_conn);
                  //iter.remove();//just close it
                }
            }
          } catch (InterruptedException e) {
            if (running) {                      // unexpected -- log it
              LOG.info(Thread.currentThread().getName() + " unexpectedly interrupted RDMA", e);
            }
          } catch (CancelledKeyException e) {
            LOG.error(getName() + ": CancelledKeyException in RDMA Reader", e);
          } 
        }
      }
      void doRead(SimpleServerRdmaRpcConnection c) throws InterruptedException {
        //SimpleRpcServer.LOG.warn("RDMA  reader doRead");
        int count;
        if (c == null) {
          return;
        }
        c.setLastContact(System.currentTimeMillis());
        try {
          count = c.readAndProcess();
        } catch (InterruptedException ieo) {
          LOG.info(Thread.currentThread().getName() + ": RDMA readAndProcess caught InterruptedException", ieo);
          throw ieo;
        } catch (Exception e) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Caught exception while readAndProcess RDMA:", e);
          }
          count = -1; //so that the (count < 0) block is executed
        }
        if (count < 0) {
          closeRdmaConnection(c);
          c = null;
        } else {
          c.setLastContact(System.currentTimeMillis());
        }
      }

    }

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="IS2_INCONSISTENT_SYNC",
      justification="selector access is not synchronized; seems fine but concerned changing " +
        "it will have per impact")
    public void run() {
      SimpleRpcServer.LOG.info("RDMA listener start and bind at port "+ rdmaPort+" this rpcserver is at port "+port);
      int i=1;
      while (running) {
        SimpleServerRdmaRpcConnection rdma_conn=getRdmaConnection(rdmaPort,System.currentTimeMillis());
        this.readers[i].pendingConnections.add(rdma_conn);
      SimpleRpcServer.LOG.info("RDMA listener add a conn to reader "+ i);
      //   synchronized (this.readers[i].lock) {  should we add a lock????
      //     this.readers[i].lock.notify();
      // }
      i++;
      i = i % readThreads ;//add in a round robin way  

      }
      LOG.info(getName() + ": stopping");
      doStop();      
    }


    synchronized void doStop() {
      SimpleRpcServer.LOG.warn("RDMA listener doStop");
      readPool.shutdownNow();
    }


  }

  /**
   * Constructs a server listening on the named port and address.
   * @param server hosting instance of {@link Server}. We will do authentications if an
   * instance else pass null for no authentication check.
   * @param name Used keying this rpc servers' metrics and for naming the Listener thread.
   * @param services A list of services.
   * @param bindAddress Where to listen
   * @param conf
   * @param scheduler
   * @param reservoirEnabled Enable ByteBufferPool or not.
   */
  public SimpleRpcServer(final Server server, final String name,
      final List<BlockingServiceAndInterface> services,
      final InetSocketAddress bindAddress, Configuration conf,
      RpcScheduler scheduler, boolean reservoirEnabled) throws IOException {
    super(server, name, services, bindAddress, conf, scheduler, reservoirEnabled);
    this.socketSendBufferSize = 0;
    this.readThreads = conf.getInt("hbase.ipc.server.read.threadpool.size", 10);
    this.purgeTimeout = conf.getLong("hbase.ipc.client.call.purge.timeout",
      2 * HConstants.DEFAULT_HBASE_RPC_TIMEOUT);

    // Start the listener here and let it bind to the port
    listener = new Listener(name);
    this.port = listener.getAddress().getPort();
    if(this.port==16020){
      this.rdmaPort=port+1;
        rdmalistener = new RdmaListener(name, rdmaPort);
      
    }

    // Create the responder here
    responder = new SimpleRpcServerResponder(this);
    connectionManager = new ConnectionManager(); 
    initReconfigurable(conf);

    this.scheduler.init(new RpcSchedulerContext(this));
  }

  /**
   * Subclasses of HBaseServer can override this to provide their own
   * Connection implementations.
   */
  protected SimpleServerRpcConnection getConnection(SocketChannel channel, long time) {
    return new SimpleServerRpcConnection(this, channel, time);
  }
  protected SimpleServerRdmaRpcConnection getRdmaConnection(int port, long time) {
    return new SimpleServerRdmaRpcConnection(this,port, time);
  }
  protected void closeConnection(SimpleServerRpcConnection connection) {
    connectionManager.close(connection); 
  }

  protected static void closeRdmaConnection(SimpleServerRdmaRpcConnection connection) {
    if(!connection.rdmaconn.close())
    {
      LOG.warn("RDMA close failed L583");
    }
  }

  /** Sets the socket buffer size used for responding to RPCs.
   * @param size send size
   */
  @Override
  public void setSocketSendBufSize(int size) { this.socketSendBufferSize = size; }

  /** Starts the service.  Must be called before any calls will be handled. */
  @Override
  public synchronized void start() {
    if (started) return;
    authTokenSecretMgr = createSecretManager();
    if (authTokenSecretMgr != null) {
      setSecretManager(authTokenSecretMgr);
      authTokenSecretMgr.start();
    }
    this.authManager = new ServiceAuthorizationManager();
    HBasePolicyProvider.init(conf, authManager);
    responder.start();
    listener.start();
    if(this.port==16020){
      rdmalistener.start();
    }
    
    scheduler.start();
    started = true;
  }

  /** Stops the service.  No new calls will be handled after this is called. */
  @Override
  public synchronized void stop() {
    LOG.info("Stopping server on " + port);
    running = false;
    if (authTokenSecretMgr != null) {
      authTokenSecretMgr.stop();
      authTokenSecretMgr = null;
    }
    listener.interrupt();
    listener.doStop();
    rdmalistener.interrupt();
    rdmalistener.doStop();
    responder.interrupt();
    scheduler.stop();
    notifyAll();
  }

  /** Wait for the server to be stopped.
   * Does not wait for all subthreads to finish.
   *  See {@link #stop()}.
   * @throws InterruptedException e
   */
  @Override
  public synchronized void join() throws InterruptedException {
    while (running) {
      wait();
    }
  }

  /**
   * Return the socket (ip+port) on which the RPC server is listening to. May return null if
   * the listener channel is closed.
   * @return the socket (ip+port) on which the RPC server is listening to, or null if this
   * information cannot be determined
   */
  @Override
  public synchronized InetSocketAddress getListenerAddress() {
    if (listener == null) {
      return null;
    }
    return listener.getAddress();
  }

  @Override
  public Pair<Message, CellScanner> call(BlockingService service, MethodDescriptor md,
      Message param, CellScanner cellScanner, long receiveTime, MonitoredRPCHandler status)
      throws IOException {
    return call(service, md, param, cellScanner, receiveTime, status, System.currentTimeMillis(),
      0);
  }

  @Override
  public Pair<Message, CellScanner> call(BlockingService service, MethodDescriptor md,
      Message param, CellScanner cellScanner, long receiveTime, MonitoredRPCHandler status,
      long startTime, int timeout) throws IOException {
    SimpleServerCall fakeCall = new SimpleServerCall(-1, service, md, null, param, cellScanner,
        null, -1, null, receiveTime, timeout, reservoir, cellBlockBuilder, null, responder);
    return call(fakeCall, status);
  }

  /**
   * This is a wrapper around {@link java.nio.channels.WritableByteChannel#write(java.nio.ByteBuffer)}.
   * If the amount of data is large, it writes to channel in smaller chunks.
   * This is to avoid jdk from creating many direct buffers as the size of
   * buffer increases. This also minimizes extra copies in NIO layer
   * as a result of multiple write operations required to write a large
   * buffer.
   *
   * @param channel writable byte channel to write to
   * @param bufferChain Chain of buffers to write
   * @return number of bytes written
   * @throws java.io.IOException e
   * @see java.nio.channels.WritableByteChannel#write(java.nio.ByteBuffer)
   */
  protected long channelWrite(GatheringByteChannel channel, BufferChain bufferChain)
  throws IOException {
    long count =  bufferChain.write(channel, NIO_BUFFER_LIMIT);
    if (count > 0) this.metrics.sentBytes(count);
    return count;
  }

  /**
   * A convenience method to bind to a given address and report
   * better exceptions if the address is not a valid host.
   * @param socket the socket to bind
   * @param address the address to bind to
   * @param backlog the number of connections allowed in the queue
   * @throws BindException if the address can't be bound
   * @throws UnknownHostException if the address isn't a valid host name
   * @throws IOException other random errors from bind
   */
  public static void bind(ServerSocket socket, InetSocketAddress address,
                          int backlog) throws IOException {
    try {
      socket.bind(address, backlog);
    } catch (BindException e) {
      BindException bindException =
        new BindException("Problem binding to " + address + " : " +
            e.getMessage());
      bindException.initCause(e);
      throw bindException;
    } catch (SocketException e) {
      // If they try to bind to a different host's address, give a better
      // error message.
      if ("Unresolved address".equals(e.getMessage())) {
        throw new UnknownHostException("Invalid hostname for server: " +
                                       address.getHostName());
      }
      throw e;
    }
  }

  /**
   * The number of open RPC conections
   * @return the number of open rpc connections
   */
  @Override
  public int getNumOpenConnections() {
    return connectionManager.size();
  }

  private class ConnectionManager {
    final private AtomicInteger count = new AtomicInteger();
    final private Set<SimpleServerRpcConnection> connections;

    final private Timer idleScanTimer;
    final private int idleScanThreshold;
    final private int idleScanInterval;
    final private int maxIdleTime;
    final private int maxIdleToClose;

    ConnectionManager() {
      this.idleScanTimer = new Timer("RpcServer idle connection scanner for port " + port, true);
      this.idleScanThreshold = conf.getInt("hbase.ipc.client.idlethreshold", 4000);
      this.idleScanInterval =
          conf.getInt("hbase.ipc.client.connection.idle-scan-interval.ms", 10000);
      this.maxIdleTime = 2 * conf.getInt("hbase.ipc.client.connection.maxidletime", 10000);
      this.maxIdleToClose = conf.getInt("hbase.ipc.client.kill.max", 10);
      int handlerCount = conf.getInt(HConstants.REGION_SERVER_HANDLER_COUNT,
          HConstants.DEFAULT_REGION_SERVER_HANDLER_COUNT);
      int maxConnectionQueueSize =
          handlerCount * conf.getInt("hbase.ipc.server.handler.queue.size", 100);
      // create a set with concurrency -and- a thread-safe iterator, add 2
      // for listener and idle closer threads
      this.connections = Collections.newSetFromMap(
          new ConcurrentHashMap<SimpleServerRpcConnection,Boolean>(
              maxConnectionQueueSize, 0.75f, readThreads+2));
    }

    private boolean add(SimpleServerRpcConnection connection) {
      boolean added = connections.add(connection);
      if (added) {
        count.getAndIncrement();
      }
      return added;
    }

    private boolean remove(SimpleServerRpcConnection connection) {
      boolean removed = connections.remove(connection);
      if (removed) {
        count.getAndDecrement();
      }
      return removed;
    }

    int size() {
      return count.get();
    }

    SimpleServerRpcConnection[] toArray() {
      return connections.toArray(new SimpleServerRpcConnection[0]);
    }

    SimpleServerRpcConnection register(SocketChannel channel) {
      SimpleServerRpcConnection connection = getConnection(channel, System.currentTimeMillis());
      add(connection);
      if (LOG.isTraceEnabled()) {
        LOG.trace("Connection from " + connection +
            "; connections=" + size() +
            ", queued calls size (bytes)=" + callQueueSizeInBytes.sum() +
            ", general queued calls=" + scheduler.getGeneralQueueLength() +
            ", priority queued calls=" + scheduler.getPriorityQueueLength());
      }
      return connection;
    }

    boolean close(SimpleServerRpcConnection connection) {
      boolean exists = remove(connection);
      if (exists) {
        if (LOG.isTraceEnabled()) {
          LOG.trace(Thread.currentThread().getName() +
              ": disconnecting client " + connection +
              ". Number of active connections: "+ size());
        }
        // only close if actually removed to avoid double-closing due
        // to possible races
        connection.close();
      }
      return exists;
    }

    // synch'ed to avoid explicit invocation upon OOM from colliding with
    // timer task firing
    synchronized void closeIdle(boolean scanAll) {
      long minLastContact = System.currentTimeMillis() - maxIdleTime;
      // concurrent iterator might miss new connections added
      // during the iteration, but that's ok because they won't
      // be idle yet anyway and will be caught on next scan
      int closed = 0;
      for (SimpleServerRpcConnection connection : connections) {
        // stop if connections dropped below threshold unless scanning all
        if (!scanAll && size() < idleScanThreshold) {
          break;
        }
        // stop if not scanning all and max connections are closed
        if (connection.isIdle() &&
            connection.getLastContact() < minLastContact &&
            close(connection) &&
            !scanAll && (++closed == maxIdleToClose)) {
          break;
        }
      }
    }

    void closeAll() {
      // use a copy of the connections to be absolutely sure the concurrent
      // iterator doesn't miss a connection
      for (SimpleServerRpcConnection connection : toArray()) {
        close(connection);
      }
    }

    void startIdleScan() {
      scheduleIdleScanTask();
    }

    void stopIdleScan() {
      idleScanTimer.cancel();
    }

    private void scheduleIdleScanTask() {
      if (!running) {
        return;
      }
      TimerTask idleScanTask = new TimerTask(){
        @Override
        public void run() {
          if (!running) {
            return;
          }
          if (LOG.isTraceEnabled()) {
            LOG.trace("running");
          }
          try {
            closeIdle(false);
          } finally {
            // explicitly reschedule so next execution occurs relative
            // to the end of this scan, not the beginning
            scheduleIdleScanTask();
          }
        }
      };
      idleScanTimer.schedule(idleScanTask, idleScanInterval);
    }
  }

}

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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.hadoop.hbase.client.VersionInfoUtil;
import org.apache.hadoop.hbase.exceptions.RequestTooBigException;
import org.apache.hadoop.hbase.ipc.RpcServer.CallCleanup;
import org.apache.hadoop.hbase.nio.ByteBuff;
import org.apache.hadoop.hbase.io.ByteArrayOutputStream;
//import org.apache.hadoop.hbase.io.ByteArrayInputStream;
import java.io.ByteArrayInputStream; 
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.DataOutputStream;
import org.apache.hadoop.hbase.nio.SingleByteBuff;
import org.apache.hbase.thirdparty.com.google.protobuf.BlockingService;
import org.apache.hbase.thirdparty.com.google.protobuf.CodedInputStream;
import org.apache.hbase.thirdparty.com.google.protobuf.Descriptors.MethodDescriptor;
import org.apache.hbase.thirdparty.com.google.protobuf.Message;
import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RPCProtos.RequestHeader;
import org.apache.hadoop.hbase.util.Pair;

/** Reads calls from a connection and queues them for handling. */
@edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "VO_VOLATILE_INCREMENT",
    justification = "False positive according to http://sourceforge.net/p/findbugs/bugs/1032/")
@InterfaceAudience.Private
class SimpleServerRdmaRpcConnection extends ServerRpcConnection {


  private RdmaNative rdma= new RdmaNative();
  public  RdmaNative.RdmaServerConnection rdmaconn;//the core of the rdmaconn class TODO init  these two
  private ByteBuff data;
  private byte[] arr;
  private int oldDataLength;
  private ByteBuffer dataLengthBuffer;
  private ByteBuffer preambleBuffer;
  private ByteBuffer rbuf;
  private DataInputStream rdma_in;
  private final LongAdder rpcCount = new LongAdder(); // number of outstanding rpcs
  private long lastContact;
  //final SimpleRpcServerRdmaResponder rdmaresponder;
  //final RdmaHandler rdmahandler;

  // If initial preamble with version and magic has been read or not.
  private boolean connectionPreambleRead = true;//we drop it in rdma

  final ConcurrentLinkedDeque<RpcResponse> responseQueue = new ConcurrentLinkedDeque<>();
  final Lock responseWriteLock = new ReentrantLock();
  long lastSentTime = -1L;

  public SimpleServerRdmaRpcConnection(SimpleRpcServer rpcServer,int port,
      long lastContact) {
    super(rpcServer);
    this.lastContact = lastContact;
    this.connectionHeaderRead=false;
    this.data = null;
    this.dataLengthBuffer = ByteBuffer.allocate(4);
    this.oldDataLength=0;
    this.arr=null;
    
    this.hostAddress ="0.0.0.0";// rpcServer.getHostAddr();//tmp fix
    try {
      this.addr=InetAddress.getByName(this.hostAddress);
    } catch (Exception e) {
      SimpleRpcServer.LOG.warn("RDMARpcConn init addr failed.");
    }
    this.remotePort = port;
    do this.rdmaconn = rdma.rdmaBlockedAccept();
         while (this.rdmaconn==null);  
    SimpleRpcServer.LOG.info("RDMARpcConn rdmaAccept <- "+rdmaconn.getClientIp().toString());
  }

  public void setLastContact(long lastContact) {
    this.lastContact = lastContact;
  }

  public long getLastContact() {
    return lastContact;
  }

  /* Return true if the connection has no outstanding rpc */
  boolean isIdle() {
    return rpcCount.sum() == 0;
  }
// if it is readable , then just read into the rbuf
  boolean isReadable(){
    if (rdmaconn.isQueryReadable()) {
      this.rbuf=rdmaconn.readQuery();
      this.rbuf.rewind();
      //this.rdma_in=new DataInputStream(new ByteArrayInputStream(rbuf));
      //SimpleRpcServer.LOG.debug("RDMARpcConn isReadable <- rbuf("
      //+rbuf.remaining() +", "+ StandardCharsets.UTF_8.decode(rbuf).toString() + ")");
      return true;
    } else {
       //SimpleRpcServer.LOG.debug("RDMARpcConn not Readable");
      return false;
    }
  }
  /* Decrement the outstanding RPC count */
  protected void decRpcCount() {
    rpcCount.decrement();
  }

  /* Increment the outstanding RPC count */
  protected void incRpcCount() {
    rpcCount.increment();
  }


  public static int bufcopy(ByteBuffer src, ByteBuffer dst){
    int i=0;
    while (src.hasRemaining()&&dst.hasRemaining())
    {dst.put(src.get()); 
    i++;}
    return i;
  }

  private int readPreamble() throws IOException {
    if (preambleBuffer == null) {
      preambleBuffer = ByteBuffer.allocate(6);
    }
    preambleBuffer.rewind(); 
    int count = bufcopy(rbuf, preambleBuffer);
    //SimpleRpcServer.LOG.debug("RDMARpcConn readPreamble() <- count "+ count+" preambleBuffer "+preambleBuffer);
    if (count < 0 || preambleBuffer.remaining() > 0) {
      //SimpleRpcServer.LOG.warn("RDMARpcConn readPreamble() -> bufcopy() failed.");
      return count;
    }
    preambleBuffer.flip();
    if (!processPreamble(preambleBuffer)) {
      //SimpleRpcServer.LOG.warn("RDMARpcConn readPreamble() -> processPreamble() failed.");
      return -1;
    }
    preambleBuffer = null; // do not need it anymore
    connectionPreambleRead = true;
    return count;
  }

  private int read4Bytes() throws IOException {
    //if (this.dataLengthBuffer.remaining() > 0) {
      return bufcopy(rbuf, this.dataLengthBuffer);
    //} else {
    //  return 0;
    //}
  }

  /**
   * Read off the wire. If there is not enough data to read, update the connection state with what
   * we have and returns.
   * @return Returns -1 if failure (and caller will close connection), else zero or more.
   * @throws IOException
   * @throws InterruptedException
   */
  public int readAndProcess() throws IOException, InterruptedException {
    //SimpleRpcServer.LOG.info("RDMARpcConn readAndProcess() invoked.");

    if (!connectionHeaderRead)// force drop the conn header after first rbuf
    //SimpleRpcServer.LOG.info("RDMARpcConn readAndProcess() detected header not read.");

    rbuf.rewind();
    dataLengthBuffer.rewind();
    // Try and read in an int. it will be length of the data to read (or -1 if a ping). We catch the
    // integer length into the 4-byte this.dataLengthBuffer.
    int count = read4Bytes();
    //SimpleRpcServer.LOG.info("RDMARpcConn readAndProcess() -> read4Bytes() -> "+ count);
    if (count < 0 || dataLengthBuffer.remaining() > 0) {
      //SimpleRpcServer.LOG.warn("RDMARpcConn readAndProcess() -> read4Bytes() Failed.");
      return count;
    }

    //if (data == null) { //TODO always init the data buffer? Y00
      dataLengthBuffer.flip();
      int dataLength = dataLengthBuffer.getInt();
      //SimpleRpcServer.LOG.debug("RDMARpcConn readAndProcess() -> dataLength "+ dataLength);
      int realDataLength=rbuf.remaining();

      if(oldDataLength<dataLength | data==null){
        SimpleRpcServer.LOG.error("init data! ");
      initByteBuffToReadInto(dataLength);
      this.arr = new byte[dataLength];
      this.oldDataLength=dataLength;
      }
      incRpcCount();

      //SimpleRpcServer.LOG.debug("RDMARpcConn readAndProcess() -> rbuf remaining " + rbuf.remaining());
      
      rbuf.get(arr);
      data.put(arr, 0, dataLength);// debug
      
      //SimpleRpcServer.LOG.debug("RDMARpcConn readAndProcess() -> rbuf -> "+
      //StandardCharsets.UTF_8.decode(ByteBuffer.wrap(arr)).toString());
      if (realDataLength>dataLength)
      {
        connectionHeaderRead=false;//force it to read the head
      }
      process();


      if (realDataLength>dataLength) {
        //SimpleRpcServer.LOG.info("RDMARpcConn readAndProcess() read header done, continue to remain");
        //if (!connectionHeaderRead)// force drop the conn header after first rbuf
        //  SimpleRpcServer.LOG.warn("RDMARpcConn readAndProcess() header not read !?");
        
        int trueDataLength = realDataLength - dataLength ;
        if(oldDataLength<trueDataLength | data==null){
          SimpleRpcServer.LOG.error("re init data! ");
        initByteBuffToReadInto(trueDataLength);
        arr = new byte[trueDataLength];
        this.oldDataLength=trueDataLength;
        }

        incRpcCount();
        
        rbuf.get(arr);//read the left things
        data.put(arr, 4, trueDataLength - 4);//drop the first int
        //SimpleRpcServer.LOG.warn("RDMARpcConn readAndProcess() -> rbuf -> "+
          //      StandardCharsets.UTF_8.decode(ByteBuffer.wrap(arr)).toString());
        process();
    }
    //SimpleRpcServer.LOG.info("RDMA readAndProcess done");

    return dataLength;//return what we've read if -1, we will close it
  }

  // It creates the ByteBuff and CallCleanup and assign to Connection instance.
  private void initByteBuffToReadInto(int length) {
    // We create random on heap buffers are read into those when
    // 1. ByteBufferPool is not there.
    // 2. When the size of the req is very small. Using a large sized (64 KB) buffer from pool is
    // waste then. Also if all the reqs are of this size, we will be creating larger sized
    // buffers and pool them permanently. This include Scan/Get request and DDL kind of reqs like
    // RegionOpen.
    // 3. If it is an initial handshake signal or initial connection request. Any way then
    // condition 2 itself will match
    // 4. When SASL use is ON.
    if (this.rpcServer.reservoir == null || skipInitialSaslHandshake || !connectionHeaderRead ||
        useSasl || length < this.rpcServer.minSizeForReservoirUse) {
      this.data = new SingleByteBuff(ByteBuffer.allocate(length));
    } else {
      Pair<ByteBuff, CallCleanup> pair = RpcServer.allocateByteBuffToReadInto(
        this.rpcServer.reservoir, this.rpcServer.minSizeForReservoirUse, length);
      this.data = pair.getFirst();
      this.callCleanup = pair.getSecond();
    }
  }



  /**
   * Process the data buffer and clean the connection state for the next call.
   */
  private void process() throws IOException, InterruptedException {
    data.rewind();
    //byte[] arr = new byte[data.remaining()];
    //data.get(arr);
    //SimpleRpcServer.LOG.info("RDMARpcConn process() <- content " + StandardCharsets.UTF_8.decode(ByteBuffer.wrap(arr)).toString());

    try {
      //SimpleRpcServer.LOG.info("RDMARpcConn process() <- processOneRpc() invoked.");
        processOneRpc(data);
    } finally {
      dataLengthBuffer.clear(); // Clean for the next call
      data = null; // For the GC
      this.callCleanup = null;
      this.oldDataLength=0;
    }
  }

  @Override
  public synchronized void close() {
    SimpleRpcServer.LOG.info("RDMARpcConn close() invoked.");
    if(!rdmaconn.close())
    {
      SimpleRpcServer.LOG.warn("RDMARpcConn close() failed.");
    }
    //rdma.rdmaDestroyGlobal();
    data = null;
    callCleanup = null;
    
  }

  @Override
  public boolean isConnectionOpen() {
    //SimpleRpcServer.LOG.warn("RDMA isConnectionOpen get result "+!(rdmaconn.isClosed()));
    return true;
    //return !(rdmaconn.isClosed());
  }

  @Override
  public SimpleRdmaServerCall createCall(int id, BlockingService service, MethodDescriptor md,
      RequestHeader header, Message param, CellScanner cellScanner, long size,
      InetAddress remoteAddress, int timeout, CallCleanup reqCleanup) {
        //SimpleRpcServer.LOG.warn("RDMARpcConn createCall()");
    return new SimpleRdmaServerCall(id, service, md, header, param, cellScanner, this, size,
        remoteAddress, System.currentTimeMillis(), timeout, this.rpcServer.reservoir,
        this.rpcServer.cellBlockBuilder, reqCleanup);
  }

  @Override
  protected void doRespond(RpcResponse resp) throws IOException {
    //SimpleRpcServer.LOG.warn("RDMARpcConn doRespond()");
    processResponse(this, resp);// this should be okey if we just respond it here,without a responder? TODO
  }

  public static boolean processResponse(SimpleServerRdmaRpcConnection conn, RpcResponse resp) throws IOException {
    boolean error = true;
    //SimpleRpcServer.LOG.info("RDMARpcConn processResponse() -> RpcResponse getResponse()");
    BufferChain buf = resp.getResponse();
    try {
      int length = buf.size();
      //for (ByteBuffer var : buf.getBuffers()) {
        //SimpleRpcServer.LOG.info("buf length " +var.remaining());
      //}
      byte[] sbuf =buf.getBytes();
      ByteBuffer directbuf=ByteBuffer.allocateDirect(sbuf.length);
      directbuf.put(sbuf);
      //ByteBuffer directbuf=ByteBuffer.allocateDirect(length);
      //ByteBuffer tmp = buf.concat(); //ByteBuffer.wrap(sbuf);
      //directbuf.put(tmp);


      //SimpleRpcServer.LOG.info("RDMARpcConn processResponse() -> try RDMAConn writeResponse()");
      if (!conn.rdmaconn.writeResponse(directbuf)) {
        error = true;
        //SimpleRpcServer.LOG.warn("RDMARpcConn processResponse() -> writeResponse() -> failed");
      } else {
        error = false;
        //directbuf.rewind();
       // SimpleRpcServer.LOG.info("RDMARpcConn processResponse() -> writeResponse() -> done with length and content "+directbuf.remaining()+"  "+ StandardCharsets.UTF_8.decode(directbuf).toString());
      }
      //rdma_out.close();
    } catch (Exception e){
      SimpleRpcServer.LOG.info("RDMARpcConn processResponse() !! EXCEPTION!");
      e.printStackTrace();
    }

    resp.done();
    if (error) {
      SimpleRpcServer.LOG.warn("RDMARpcConn closing due to previous failure.");
      SimpleRpcServer.closeRdmaConnection(conn);
      return false;
    }
    return true;

 }
}

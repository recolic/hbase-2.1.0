package org.apache.hadoop.hbase.ipc;

import org.apache.yetus.audience.InterfaceAudience;
import java.nio.ByteBuffer;

@InterfaceAudience.Public
public class RdmaNative {
    // This function must be called exactly once to construct necessary structs.
    // It will construct rdmaContext and other global var.
    public native int rdmaInitGlobal();
    // This function must be called exactly once to destruct global structs.
    public native int rdmaDestroyGlobal();

    // Connect to remote host. Blocked operation. If success, returnedConn.errorCode holds 0.
    public native RdmaClientConnection rdmaConnect(String addr, int port);
    // Wait and accept a connection. Blocked operation. If success, returnedConn.errorCode holds 0.
    public native RdmaServerConnection rdmaBlockedAccept(int port);

    public class RdmaClientConnection {
        private long ptrCxxClass;

        public native boolean isClosed();
        public native boolean isConnectSucceed();
        public native ByteBuffer readResponse(); // blocked. Will wait for the server for response.
        public native boolean writeQuery(ByteBuffer data); // blocked until success.
        public native boolean close(); // You may call it automatically in destructor. It MUST be called once.
    }

    public class RdmaServerConnection {
        /* 
            The server holds two buffer. DynamicBufferTokenBuffer holds std::pair<Magic, DynamicBufferToken>, and DynamicBuffer
            holds the real data. The Magic is inited to 0x00000000 and set to 0xffffffff if DynamicBufferToken is ready to use.
            (For the initial 4K buffer, the magic is 0x00000000)
        begin:
            On accepting connection, the server creates a 4K DynamicBuffer, and register it, put the token into DynamicBufferTokenBuffer.
            Then the server send the DynamicBufferTokenBufferToken to client as userData. The client send its query size as userData.
            If the client's query size is less than 4K, it just write it in the pre-allocated 4K buffer. If the query is larger, 
            the client must read DynamicBufferTokenBuffer again and again, until the Magic is NOT 0x00000000. Then write the query in.
            If the query is larger than 4K, the server must resize and register the DynamicBuffer, put its new token into 
            DynamicBufferTokenBuffer, and set the Magic to 0xffffffff atomically(will atomic CPU instruction works for rdma? It doesn't matter).
            
            Once the query is wrote into server, the client must set Magic to 0xaaaaaaaa(use compareAndSwap, if possible. It doesn't matter).
            The server call isQueryReadable() to check if the Magic is 0xaaaaaaaa. 
            After the response is ready, the server use writeResponse to write its data to local buffer. (In fact, the memory in ByteBuffer maybe 
            used and prevent its GC). The DynamicBufferToken is updated, then the Magic is set to 0x55555555. The client check the value again
            and again. Once available, the client can get the response.
            I have not consider how should I close the connection. Maybe just destruct the Rdma*Connection, and destruct the insiding C++ QP. 
            If we want to reuse the connection in the future, just leave the connection there and the server use current DynamicBuffer as initial
            buffer, goto begin;
        */
        private long ptrCxxClass;

        public native boolean isClosed();
        public native boolean isAcceptSucceed();
        public native boolean isQueryReadable();
        // use java global weak ref to prevent gc.
        public native ByteBuffer readQuery();
        public native boolean writeResponse(ByteBuffer data);
        public native boolean close(); // You may call it automatically in destructor. It MUST be called once.
    }
}
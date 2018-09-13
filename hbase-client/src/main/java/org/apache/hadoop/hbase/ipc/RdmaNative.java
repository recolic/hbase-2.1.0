// package org.apache.hadoop.hbase.ipc;

// import java.nio.ByteBuffer;
// public class RdmaNative {
//     public native boolean rdmaIsOpen(Object RdmaConnection);
//     public native Object rdmaConnect(int port,String addr);
//     public native Object rdmaBlockedAccept(int port);
//     public native int rdmaClose(Object RdmaConnection);
//     public native ByteBuffer rdmaRead(Object RdmaConnection);
//     public native boolean rdmaReadable(Object RdmaConnection);
//     public native boolean rdmaWrite(Object RdmaConnection, ByteBuffer sbuf);
//     public native boolean rdmaRespond(Object RdmaConnection, ByteBuffer sbuf);

//     public class RdmaConnection {
//         private long ptrQP;
//     }
// }
package org.apache.hadoop.hbase.ipc;
//org[.]apache[.]yetus[.]audience[.]InterfaceAudience.*
//the pattern 'org[.]apache[.]yetus[.]audience[.]InterfaceAudience.*'
import org.apache.yetus.audience.InterfaceAudience;
//org.apache.yetus.audience.InterfaceAudience.aaaaa
import java.nio.ByteBuffer;
@InterfaceAudience.Public
public class RdmaNative {
    static {
        System.loadLibrary("rdma");
      }
    // This function must be called exactly once to construct necessary structs.
    // It will construct rdmaContext and other global var.
    public native int rdmaInitGlobal();
    // This function must be called exactly once to destruct global structs.
    public native int rdmaDestroyGlobal();

    // Connect to remote host. Blocked operation. If success, returnedConn.errorCode holds 0.
    public native RdmaConnection rdmaConnect(String addr, int port);
    // Wait and accept a connection. Blocked operation. If success, returnedConn.errorCode holds 0.
    public native RdmaConnection rdmaBlockedAccept(int port);

    public class RdmaConnection {
        /* 4ptr is maintained by C++ class.
        private long ptrQP;
        private long ptrRegionTokenBuf; // registered as fixed length while making conn.
        private long ptrRemoteSerialBuf; // remote buffer serial number.
        private long ptrDynamicDataBuf; // must register on every local write. Invalidate it only if ptrRegionTokenBuf has changed!
        */
        private long ptrCxxClass;
        private boolean isServer; 
        private int errorCode;
        private boolean isClosed;

        // check if this connection is closed.
        public boolean isClosed() {
            return this.isClosed;
        }
        // true if is server, false if is client.
        public boolean isServer() {
            return this.isServer;
        }
        // Only used for rdmaConnect and rdmaBlockedAccept.
        public int getErrorCode() {
            return this.errorCode;
        }
        // Assume remote holds only one buffer. Read the buffer. Blockd call.
        public native ByteBuffer readRemote();
        // Use JNI GlobalRef to prevent GC, register this buffer, and replace ptrRegionTokenBuf atomicly for peer read. Blocked call.
        // Finally, use async rdmaWrite to set remote->ptrRemoteSerialBuf->data(), which will trigger isRemoteReadable soon. // maybe a bool is enough
        public native int writeLocal(ByteBuffer data); 
        // Add a serial number into *ptrRegionTokenBuf to implement it.
        public native boolean isRemoteReadable(); 
        public native int close();
    }
}

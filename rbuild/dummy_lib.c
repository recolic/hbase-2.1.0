#include <jni.h>
#include <stdlib.h>
#include <stdio.h>

JNIEXPORT jobject JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_rdmaBlockedAccept(JNIEnv *env, jobject foo, jint port)
{
    void * bufferToReadWrite = malloc(1000);
    jlong capacity = 1000;
    jobject directBuffer = (*env)->NewDirectByteBuffer(env,bufferToReadWrite, capacity);
    jobject globalRef =(*env)->NewGlobalRef(env,directBuffer);
    return globalRef;
}

JNIEXPORT jint JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_rdmaInitGlobal(JNIEnv *env, jobject foo)
{
    return 1;
}

JNIEXPORT jint JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_00024RdmaConnection_writeLocal(JNIEnv *env, jobject foo,jobject buf)
{
    return 1;
}
JNIEXPORT jint JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_rdmaDestroyGlobal(JNIEnv *env, jobject foo)
{
    return 1;
}
JNIEXPORT jobject JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_rdmaConnect(JNIEnv *env, jobject foo,jstring  addr, jint port)
{
    void * bufferToReadWrite = malloc(1000);
    jlong capacity = 1000;
    jobject directBuffer = (*env)->NewDirectByteBuffer(env,bufferToReadWrite, capacity);
    jobject globalRef =(*env)->NewGlobalRef(env,directBuffer);
    return globalRef;
}
JNIEXPORT jobject JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_00024RdmaConnection_readRemote(JNIEnv *env, jobject foo)
{
    void * bufferToReadWrite = malloc(1000);
    jlong capacity = 1000;
    jobject directBuffer = (*env)->NewDirectByteBuffer(env,bufferToReadWrite, capacity);
    jobject globalRef =(*env)->NewGlobalRef(env,directBuffer);
    return globalRef;
}

JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_isClosed(JNIEnv *env, jobject foo)
{
    return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_isServer(JNIEnv *env, jobject foo)
{
    return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_00024RdmaConnection_isRemoteReadable(JNIEnv *env, jobject foo)
{
    return JNI_TRUE;
}
JNIEXPORT jint JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_getErrorCode(JNIEnv *env, jobject foo)
{
    return JNI_TRUE;
}
JNIEXPORT jint JNICALL Java_org_apache_hadoop_hbase_ipc_RdmaNative_00024RdmaConnection_close(JNIEnv *env, jobject foo)
{
    return JNI_TRUE;
}




package com.intel.hpnl.core;

import java.util.HashMap;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class EqService {
  static {
    System.loadLibrary("hpnl");
  }

  public EqService(int worker_num, int buffer_num, boolean is_server) {
    this.worker_num = worker_num;
    this.buffer_num = buffer_num;
    this.is_server = is_server;
    this.curCon = null;
    this.rmaBufferId = new AtomicInteger(0);

    this.conMap = new HashMap<Long, Connection>();
    this.reapCons = new LinkedBlockingQueue<Connection>();
    this.rmaBufferMap = new ConcurrentHashMap<Integer, ByteBuffer>();
    this.connectLatch = new ConcurrentHashMap<Long, CountDownLatch>();
  }

  public EqService init() {
    if (init(worker_num, buffer_num, is_server) == -1)
      return null;
    this.eqThread = new EqThread(this);
    this.eqThread.start();
    return this;
  }

  public Connection connect(String ip, String port, long timeout) {
    synchronized (EqService.class) {
      localEq = native_connect(ip, port, nativeHandle);
      if (localEq == -1) {
        return null;
      }
      connectLatch.put(localEq, new CountDownLatch(1));
      add_eq_event(localEq, nativeHandle);
      try {
        if (timeout == 0) {
          this.connectLatch.get(localEq).await();
          return curCon;
        } else {
          if (!this.connectLatch.get(localEq).await(timeout, TimeUnit.MILLISECONDS)) {
            return null;
          }
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    return null;
  }

  public int listen(String ip, String port) {
    localEq = native_connect(ip, port, nativeHandle);
    if (localEq == -1) {
      return -1;
    }
    add_eq_event(localEq, nativeHandle);
    return 0;
  }

  public void join() {
    try {
      eqThread.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
    }
  }

  public void shutdown() {
    for (Connection con : reapCons) {
      addReapCon(con);
    }
    synchronized(EqService.class) {
      eqThread.shutdown();
    }
    delete_eq_event(localEq, nativeHandle);
  }

  private void regCon(long eq, long con, String dest_addr, int dest_port, String src_addr, int src_port) {
    Connection connection = new Connection(eq, con, this);
    connection.setAddrInfo(dest_addr, dest_port, src_addr, src_port);
    conMap.put(eq, connection);
  }

  public void unregCon(long eq) {
    if (conMap.containsKey(eq)) {
      conMap.remove(eq);
    }
    if (!is_server) {
      eqThread.shutdown(); 
    }
  }

  private void handleEqCallback(long eq, int eventType, int blockId) {
    Connection connection = conMap.get(eq);
    if (eventType == EventType.CONNECTED_EVENT) {
      connection.setConnectedCallback(connectedCallback);
      connection.setRecvCallback(recvCallback);
      connection.setSendCallback(sendCallback);
      connection.setReadCallback(readCallback);
      connection.setShutdownCallback(shutdownCallback);
    }
    connection.handleCallback(eventType, 0, 0);
    if (!is_server && eventType == EventType.CONNECTED_EVENT) {
      curCon = connection;
      this.connectLatch.get(eq).countDown();
      this.connectLatch.remove(eq);
    }
  }

  public void setConnectedCallback(Handler callback) {
    connectedCallback = callback;
  }

  public void setRecvCallback(Handler callback) {
    recvCallback = callback;
  }

  public void setSendCallback(Handler callback) {
    sendCallback = callback;
  }

  public void setReadCallback(Handler callback) {
    readCallback = callback; 
  }

  public void setShutdownCallback(Handler callback) {
    shutdownCallback = callback;
  }

  public Connection getCon(long eq) {
    return conMap.get(eq);
  }

  public void initBufferPool(int initBufferNum, int bufferSize, int nextBufferNum) {
    this.sendBufferPool = new MemPool(this, initBufferNum, bufferSize, nextBufferNum, MemPool.Type.SEND);
    this.recvBufferPool = new MemPool(this, initBufferNum*2, bufferSize, nextBufferNum*2, MemPool.Type.RECV);
  }

  public void reallocBufferPool() {
    this.sendBufferPool.realloc();
    this.recvBufferPool.realloc();
  }

  public void putSendBuffer(long eq, int rdmaBufferId) {
    Connection connection = conMap.get(eq);
    connection.putSendBuffer(sendBufferPool.getBuffer(rdmaBufferId));
  }

  public RdmaBuffer getSendBuffer(int rdmaBufferId) {
    return sendBufferPool.getBuffer(rdmaBufferId); 
  }

  public RdmaBuffer getRecvBuffer(int rdmaBufferId) {
    return recvBufferPool.getBuffer(rdmaBufferId);
  }

  public RdmaBuffer regRmaBuffer(ByteBuffer byteBuffer, int bufferSize) {
    int bufferId = this.rmaBufferId.getAndIncrement();
    rmaBufferMap.put(bufferId, byteBuffer);
    long rkey = reg_rma_buffer(byteBuffer, bufferSize, bufferId, nativeHandle);
    if (rkey < 0) {
      return null;
    }
    RdmaBuffer buffer = new RdmaBuffer(bufferId, byteBuffer, rkey);
    return buffer;
  }

  public RdmaBuffer regRmaBufferByAddress(ByteBuffer byteBuffer, long address, long bufferSize) {
    int bufferId = this.rmaBufferId.getAndIncrement();
    if (byteBuffer != null) {
      rmaBufferMap.put(bufferId, byteBuffer);
    }
    long rkey = reg_rma_buffer_by_address(address, bufferSize, bufferId, nativeHandle);
    if (rkey < 0) {
      return null;
    }
    RdmaBuffer buffer = new RdmaBuffer(bufferId, byteBuffer, rkey);
    return buffer;
  }

  public void unregRmaBuffer(int rdmaBufferId) {
    unreg_rma_buffer(rdmaBufferId, nativeHandle);
  }

  public RdmaBuffer getRmaBuffer(int bufferSize) {
    int bufferId = this.rmaBufferId.getAndIncrement();
    // allocate memory from on-heap, off-heap or AEP.
    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bufferSize);
    long address = get_buffer_address(byteBuffer, nativeHandle);
    rmaBufferMap.put(bufferId, byteBuffer);
    long rkey = reg_rma_buffer(byteBuffer, bufferSize, bufferId, nativeHandle);
    if (rkey < 0) {
      return null;
    }
    RdmaBuffer buffer = new RdmaBuffer(bufferId, byteBuffer, rkey, address);
    return buffer; 
  }

  public ByteBuffer getRmaBufferByBufferId(int rmaBufferId) {
    return rmaBufferMap.get(rmaBufferId); 
  }

  public void addReapCon(Connection con) {
    reapCons.offer(con);
  }

  public boolean needReap() {
    return reapCons.size() > 0; 
  }

  public void externalEvent() {
    while (needReap()) {
      Connection con = reapCons.poll();
      con.shutdown();
    }
  }

  public int getWorkerNum() {
    return this.worker_num;
  }

  public long getNativeHandle() {
    return nativeHandle; 
  }

  public native void shutdown(long eq, long nativeHandle);
  private native long native_connect(String ip, String port, long nativeHandle);
  public native int wait_eq_event(long nativeHandle);
  public native int add_eq_event(long eq, long nativeHandle);
  public native int delete_eq_event(long eq, long nativeHandle);
  public native void set_recv_buffer(ByteBuffer buffer, long size, int rdmaBufferId, long nativeHandle);
  public native void set_send_buffer(ByteBuffer buffer, long size, int rdmaBufferId, long nativeHandle);
  private native long reg_rma_buffer(ByteBuffer buffer, long size, int rdmaBufferId, long nativeHandle);
  private native long reg_rma_buffer_by_address(long address, long size, int rdmaBufferId, long nativeHandle);
  private native void unreg_rma_buffer(int rdmaBufferId, long nativeHandle);
  private native long get_buffer_address(ByteBuffer buffer, long nativeHandle);
  private native int init(int worker_num_, int buffer_num_, boolean is_server_);
  private native void free(long nativeHandle);
  public native void finalize();

  public String getIp() {
    return ip;
  }

  public String getPort() {
    return port;
  }

  private long nativeHandle;
  private long localEq;
  private String ip;
  private String port;
  private int worker_num;
  private int buffer_num;
  public boolean is_server;
  private Connection curCon;
  private ConcurrentHashMap<Long, CountDownLatch> connectLatch;
  private HashMap<Long, Connection> conMap;
  private LinkedBlockingQueue<Connection> reapCons;

  private ConcurrentHashMap<Integer, ByteBuffer> rmaBufferMap;

  private MemPool sendBufferPool;
  private MemPool recvBufferPool;

  AtomicInteger rmaBufferId;

  private Handler connectedCallback;
  private Handler recvCallback;
  private Handler sendCallback;
  private Handler readCallback;
  private Handler shutdownCallback;

  private EqThread eqThread;
  private final AtomicBoolean needReap = new AtomicBoolean(false);
  private boolean needStop = false;
}

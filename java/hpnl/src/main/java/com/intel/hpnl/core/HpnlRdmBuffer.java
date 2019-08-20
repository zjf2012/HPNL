package com.intel.hpnl.core;

import com.intel.hpnl.api.AbstractHpnlBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class HpnlRdmBuffer extends AbstractHpnlBuffer {
  public static final int METADATA_SIZE = 8 + BASE_METADATA_SIZE;
  private static final Logger log = LoggerFactory.getLogger(HpnlRdmBuffer.class);
  private long connectionId;
  private long peerConnectionId;

  public HpnlRdmBuffer(int bufferId, ByteBuffer byteBuffer) {
    super(bufferId, byteBuffer);
  }

  private void putMetadata(int srcSize, byte type, long seq) {
    this.byteBuffer.rewind();
    this.byteBuffer.limit(METADATA_SIZE + srcSize);
    this.byteBuffer.put(type);
    this.byteBuffer.putLong(this.connectionId);
    this.byteBuffer.putLong(seq);
  }

  @Override
  public void putData(ByteBuffer src, byte type, long seq) {
    this.putMetadata(src.remaining(), type, seq);
    this.byteBuffer.put(src.slice());
    this.byteBuffer.flip();
  }

  @Override
  public int getMetadataSize() {
    return METADATA_SIZE;
  }

  @Override
  public ByteBuffer parse(int blockBufferSize) {
    this.byteBuffer.position(0);
    this.byteBuffer.limit(blockBufferSize);
    this.frameType = this.byteBuffer.get();
    peerConnectionId = this.byteBuffer.getLong();
    this.seq = this.byteBuffer.getLong();
    return this.byteBuffer.slice();
  }

  @Override
  public void insertMetadata(byte frameType, long seqId, int limit) {
    this.byteBuffer.position(0);
    this.byteBuffer.put(frameType);
    this.byteBuffer.putLong(this.connectionId);
    this.byteBuffer.putLong(seqId);
    this.byteBuffer.limit(limit);
    this.byteBuffer.position(limit);
//    log.info("buffer-> {}, {} {}, {}, {}", getBufferId(), frameType, connectionId, seqId, limit);
  }

  @Override
  public void clear(){
    super.clear();
    peerConnectionId = -1;
  }

  public void setConnectionId(long connectionId) {
    this.connectionId = connectionId;
  }

  @Override
  public long getConnectionId() {
    return connectionId;
  }

  @Override
  public long getPeerConnectionId(){
    return peerConnectionId;
  }
}

package com.intel.hpnl.core.rdm;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.intel.hpnl.api.*;
import com.intel.hpnl.core.RdmHpnlService;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Command;

import com.intel.hpnl.core.RdmService;
import com.intel.hpnl.core.RdmConnection;

@Command(mixinStandardHelpOptions = true, version = "auto help demo - picocli 3.0")
public class ClientTest implements Runnable {

  @Option(names = {"-a", "--address"}, required = true, description = "server address")
  String addr = "localhost";

  @Option(names = {"-p", "--port"}, required = false, description = "server port")
  String port = "123456";

  @Option(names = {"-s", "--buffer_size"}, required = false, description = "buffer size")
  int bufferSize = 65536;

  @Option(names = {"-n", "--buffer_number"}, required = false, description = "buffer number")
  int bufferNbr = 32;

  @Option(names = {"-m", "--message_size"}, required = false, description = "pingpong message size")
  int msgSize = 4096;

  @Option(names = {"-w", "--worker_number"}, required = false, description = "worker numbers")
  int workNbr = 1;

  @Option(names = {"-i", "--interval"}, required = false, description = "statistics interval time")
  int interval = 5;

  @Option(names = {"-f", "--affinity"}, required = false, split = ",",  description = "HPNL thread affinity")
  int[] affinities = null;

  public void run(){
    Object[] ret = startClient();
    try {
      Thread.sleep(5000);
    }catch (Exception e){
      e.printStackTrace();
    }
    ((RdmConnection)ret[1]).shutdown();
    ((RdmHpnlService)ret[0]).stop();
    try {
      Thread.sleep(5000);
    }catch (Exception e){
      e.printStackTrace();
    }
    System.out.println("xyz");
    startClient();
  }

  private Object[] startClient(){
    RdmHpnlService hpnlService = new RdmHpnlService(1, bufferNbr, bufferSize, 50, false);
    assert(hpnlService != null);

    ExecutorService es = Executors.newFixedThreadPool(1);
    es.submit(hpnlService.getRdmService().getEventTask());

    Object[] ret = new Object[2];

    ret[0] = hpnlService;
    hpnlService.connect(addr, Integer.valueOf(port), 0, new Handler() {
      @Override
      public int handle(Connection connection, int bufferId, int bufferSize) {
        ret[1] = connection;
        connection.setRecvCallback(new RecvCallback(false));
        HpnlBuffer sendBuffer = connection.takeSendBuffer();
        ByteBuffer rawBuffer = sendBuffer.getRawBuffer();
        rawBuffer.clear();
        rawBuffer.position(sendBuffer.getMetadataSize());
        ByteBuffer localName = connection.getLocalName();
        localName.rewind();
        rawBuffer.putInt(localName.remaining());
        rawBuffer.put(localName);
        int limit = rawBuffer.position();
        sendBuffer.insertMetadata((byte)0, -1L, limit);
        rawBuffer.flip();
        connection.send(sendBuffer.remaining(), sendBuffer.getBufferId());

        return Handler.RESULT_DEFAULT;
      }
    });
    return ret;
  }

  public static void main(String... args) {
    HpnlFactory.loadLib();
    CommandLine.run(new ClientTest(), args);
  }
}
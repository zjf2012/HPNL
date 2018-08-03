package com.intel.hpnl.core;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class EqThread extends Thread {
  public EqThread(EqService service_) {
    this.service = service_;
    running.set(true);
  }

  public void run() {
    while (running.get()) {
      Map<Long, Integer> eqs = service.getEqs();
      for (Map.Entry<Long, Integer> entry : eqs.entrySet()) {
        if (entry.getValue() == 1) {
          service.wait_eq_event(entry.getKey());
        }
      }
    }
  }

  public void iterrupt() {
    running.set(false); 
  }

  private EqService service;
  private final AtomicBoolean running = new AtomicBoolean(false);
}

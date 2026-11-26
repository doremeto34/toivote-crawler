package com.cache;

import java.util.concurrent.atomic.AtomicLong;

public class CacheEntry<V> {

  V value;
  long expiryTime;
  AtomicLong lastReadTime;

  CacheEntry(V value, long ttl) {
    this.value = value;
    long now = System.nanoTime();
    this.expiryTime = now + ttl * 1_000_000_000L;
    this.lastReadTime = new AtomicLong(now);
  }
}

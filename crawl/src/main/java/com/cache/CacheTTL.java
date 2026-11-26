package com.cache;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class CacheTTL<K, V> implements Map<K, V> {

  private final Map<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
  private final int tti;
  private final int ttl;
  private final AtomicLong hits = new AtomicLong(0);
  private final AtomicLong requests = new AtomicLong(0);

  public CacheTTL(int tti, int ttl) {
    if (ttl <= 0 || tti <= 0) {
      throw new IllegalArgumentException("TTL must be positive");
    }
    this.tti = tti;
    this.ttl = ttl;
  }

  @Override
  public V get(Object key) {
    
    requests.incrementAndGet();

    CacheEntry<V> entry = cache.get(key);
    if (entry == null) {
      return null;
    }
    long now = System.nanoTime();

    if (isExpired(entry, now)) {
      cache.remove(key, entry);
      return null;
    }

    entry.lastReadTime.accumulateAndGet(now, Math::max);
    hits.incrementAndGet();
    return entry.value;
  }

  @Override
  public V put(K key, V value) {
    CacheEntry<V> previous = cache.put(
        key,
        new CacheEntry<>(value, ttl));
    return previous == null ? null : previous.value;
  }

  @Override
  public int size() {
    cleanUp();
    return cache.size();
  }

  @Override
  public boolean isEmpty() {
    cleanUp();
    return cache.isEmpty();
  }

  @Override
  public boolean containsKey(Object key) {
    return get(key) != null;
  }

  @Override
  public boolean containsValue(Object value) {
    cleanUp();

    for (CacheEntry<V> entry : cache.values()) {
      if (Objects.equals(entry.value, value)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public V remove(Object key) {
    CacheEntry<V> entry = cache.remove(key);

    if (entry == null) {
      return null;
    }

    long now = System.nanoTime();

    if (isExpired(entry, now)) {
      return null;
    }

    return entry.value;
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public void clear() {
    cache.clear();
  }

  @Override
  public Set<K> keySet() {
    cleanUp();
    return Collections.unmodifiableSet(cache.keySet());
  }

  @Override
  public Collection<V> values() {
    cleanUp();

    List<V> result = new ArrayList<>();

    for (CacheEntry<V> entry : cache.values()) {
      result.add(entry.value);
    }

    return Collections.unmodifiableList(result);
  }

  @Override
  public Set<Map.Entry<K, V>> entrySet() {
    cleanUp();

    Set<Map.Entry<K, V>> result = new HashSet<>();

    for (Map.Entry<K, CacheEntry<V>> entry : cache.entrySet()) {
      result.add(new AbstractMap.SimpleImmutableEntry<>(
          entry.getKey(),
          entry.getValue().value));
    }

    return Collections.unmodifiableSet(result);
  }

  public Map<K, V> getMap() {
    cleanUp();

    Map<K, V> result = new HashMap<>();

    for (Map.Entry<K, CacheEntry<V>> entry : cache.entrySet()) {
      result.put(entry.getKey(), entry.getValue().value);
    }

    return result;
  }

  private void cleanUp() {
    long now = System.nanoTime();

    for (Map.Entry<K, CacheEntry<V>> entry : cache.entrySet()) {
      CacheEntry<V> value = entry.getValue();

      if (isExpired(value, now)) {
        cache.remove(entry.getKey(), value);
      }
    }
  }

  private boolean isExpired(CacheEntry<V> entry, long now) {
    boolean ttlExpired = now - entry.expiryTime >= 0;

    boolean ttiExpired = now - entry.lastReadTime.get() >= tti * 1_000_000_000L;

    return ttlExpired || ttiExpired;
  }

  public int getHitRate() {
    long total = requests.get();

    if (total == 0) {
        return 0;
    }

    return (int) (hits.get() * 100 / total);
}
}

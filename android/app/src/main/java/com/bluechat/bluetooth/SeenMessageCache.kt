package com.bluechat.bluetooth

import java.util.LinkedHashMap

class SeenMessageCache(maxSize: Int = 500) {
  private val cache = LinkedHashMap<String, Long>(maxSize, 0.75f, true)

  fun has(key: String): Boolean {
    val now = System.currentTimeMillis()
    synchronized(cache) {
      val timestamp = cache.remove(key)
      if (timestamp != null) {
        if (now - timestamp < EXPIRY_MS) {
          cache[key] = now
          return true
        }
        return false
      }
      return false
    }
  }

  fun add(key: String) {
    val now = System.currentTimeMillis()
    synchronized(cache) {
      cache[key] = now
      if (cache.size > MAX_SIZE) {
        val oldest = cache.keys.firstOrNull()
        if (oldest != null) {
          cache.remove(oldest)
        }
      }
    }
  }

  private companion object {
    private const val MAX_SIZE = 500
    private const val EXPIRY_MS = 5 * 60 * 1000L
  }
}

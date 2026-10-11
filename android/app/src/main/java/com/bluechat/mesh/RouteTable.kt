package com.bluechat.mesh

import java.util.concurrent.ConcurrentHashMap

data class RouteEntry(
    val nextHop: String,
    val cost: Int,
    val lastUpdated: Long,
)

class RouteTable {
  private val routes = ConcurrentHashMap<String, RouteEntry>()

  fun get(dest: String): RouteEntry? = routes[dest]

  fun update(dest: String, nextHop: String, cost: Int) {
    routes[dest] = RouteEntry(nextHop, cost, System.currentTimeMillis())
  }

  fun remove(dest: String) {
    routes.remove(dest)
  }

  fun clear() {
    routes.clear()
  }
}

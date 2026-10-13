package com.energiacontexto.server

typealias EventHandler = (Any) -> Unit

class EventBus {
    private val handlers = mutableMapOf<String, MutableList<EventHandler>>()

    fun on(name: String, handler: EventHandler) {
        handlers.getOrPut(name) { mutableListOf() }.add(handler)
    }

    fun emit(name: String, data: Any) {
        handlers[name]?.forEach { h ->
            try {
                h(data)
            } catch (e: Exception) {
                android.util.Log.e("EventBus", "Error en $name: ${e.message}")
            }
        }
    }
}

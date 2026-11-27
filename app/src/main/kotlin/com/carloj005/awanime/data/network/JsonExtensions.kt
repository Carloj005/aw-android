package com.carloj005.awanime.data.network

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.str(key: String): String? {
    val el = this[key] ?: return null
    if (el is JsonNull) return null
    return (el as? JsonPrimitive)?.content
}

internal fun JsonObject.intVal(key: String): Int? {
    val el = this[key] as? JsonPrimitive ?: return null
    return el.intOrNull ?: el.content.toIntOrNull()
}

internal fun JsonObject.longVal(key: String): Long? {
    val el = this[key] as? JsonPrimitive ?: return null
    return el.longOrNull ?: el.content.toLongOrNull()
}

internal fun JsonObject.boolVal(key: String): Boolean? {
    val el = this[key] as? JsonPrimitive ?: return null
    return el.booleanOrNull
}

internal fun JsonObject.doubleVal(key: String): Double? {
    val el = this[key] as? JsonPrimitive ?: return null
    return el.doubleOrNull ?: el.content.toDoubleOrNull()
}

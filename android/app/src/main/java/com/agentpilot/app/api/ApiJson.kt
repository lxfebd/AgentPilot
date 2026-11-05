package com.agentpilot.app.api

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

internal fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

internal fun JSONObject.optNullableBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return opt(name) as? Boolean
}

internal fun JSONObject?.toMap(): Map<String, Any?> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { key ->
        val value = opt(key)
        when (value) {
            JSONObject.NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> List(value.length()) { index ->
                when (val item = value.opt(index)) {
                    JSONObject.NULL -> null
                    is JSONObject -> item.toMap()
                    is JSONArray -> List(item.length()) { nestedIndex -> item.opt(nestedIndex) }
                    else -> item
                }
            }
            else -> value
        }
    }
}

internal fun Map<String, Any?>.toJsonObject(): JSONObject {
    val json = JSONObject()
    forEach { (key, value) ->
        json.put(key, value ?: JSONObject.NULL)
    }
    return json
}

internal inline fun <T> JSONArray?.toObjectList(
    transform: JSONObject.() -> T,
): List<T> {
    if (this == null) return emptyList()
    return List(length()) { index ->
        getJSONObject(index).transform()
    }
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { index -> optString(index) }.filter { it.isNotBlank() }
}

internal fun String.urlEncode(): String {
    return java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
}

/** JSON text, such as a configuration field the user typed, as Kotlin values. Trailing text is an error. */
internal fun parseJsonValue(text: String): Any? {
    val tokener = JSONTokener(text)
    val value = tokener.nextValue()
    if (tokener.nextClean() != 0.toChar()) throw JSONException("Unexpected text after the JSON value")
    return value.toKotlinValue()
}

/** Indented JSON text for a value read with [toMap] or [parseJsonValue]. */
internal fun formatJsonValue(value: Any?): String {
    val text = when (val json = value.toJsonValue()) {
        is JSONObject -> json.toString(2)
        is JSONArray -> json.toString(2)
        is String -> JSONObject.quote(json)
        else -> json.toString()
    }
    return text.withoutEscapedSlashes()
}

/** Android escapes every "/" in strings; paths read better without it and parse the same. */
private fun String.withoutEscapedSlashes(): String {
    return replace("\\/", "/")
}

private fun Any?.toKotlinValue(): Any? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> toMap()
    is JSONArray -> List(length()) { index -> opt(index).toKotlinValue() }
    else -> this
}

private fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { json ->
        forEach { (key, value) -> json.put(key.toString(), value.toJsonValue()) }
    }
    is Iterable<*> -> JSONArray().also { json -> forEach { json.put(it.toJsonValue()) } }
    is Array<*> -> JSONArray().also { json -> forEach { json.put(it.toJsonValue()) } }
    else -> this
}

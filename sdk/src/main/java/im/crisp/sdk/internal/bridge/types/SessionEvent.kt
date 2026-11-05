package im.crisp.sdk.internal.bridge.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal enum class SessionEventColor {
    @SerialName("red") RED,
    @SerialName("orange") ORANGE,
    @SerialName("yellow") YELLOW,
    @SerialName("green") GREEN,
    @SerialName("blue") BLUE,
    @SerialName("purple") PURPLE,
    @SerialName("pink") PINK,
    @SerialName("brown") BROWN,
    @SerialName("grey") GREY,
    @SerialName("black") BLACK,
}

/** `[key, value]` pair — the shape `setSessionData` expects. Values are plain JSON. */
internal data class Tuple(val key: String, val value: JsonElement) {
    fun toJson(): JsonElement = JsonArray(listOf(JsonPrimitive(key), value))
}

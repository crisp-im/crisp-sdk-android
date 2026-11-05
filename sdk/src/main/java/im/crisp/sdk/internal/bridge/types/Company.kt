package im.crisp.sdk.internal.bridge.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject

@Serializable
internal data class Company(
    val name: String? = null,
    val url: String? = null,
    val description: String? = null,
    @Serializable(with = EmploymentSerializer::class) val employment: Employment? = null,
    @Serializable(with = GeolocationSerializer::class) val geolocation: Geolocation? = null,
)

@Serializable internal data class Employment(val role: String, val title: String? = null)

@Serializable internal data class Geolocation(val city: String? = null, val country: String? = null)

/** The Web client takes and returns [employment][Employment] as `[title, role]`. */
private object EmploymentSerializer : ArrayObjectSerializer<Employment>(Employment.serializer(), "title", "role")

/** The Web client takes and returns [geolocation][Geolocation] as `[country, city]`. */
private object GeolocationSerializer : ArrayObjectSerializer<Geolocation>(Geolocation.serializer(), "country", "city")

/**
 * Encodes an object as the array of its [keys] values, in this order.
 *
 * Decodes both shapes, as the Web client returns the object unchanged when its first value is missing.
 */
private abstract class ArrayObjectSerializer<T : Any>(
    serializer: KSerializer<T>,
    private vararg val keys: String,
) : JsonTransformingSerializer<T>(serializer) {
    override fun transformSerialize(element: JsonElement): JsonElement =
        JsonArray(keys.map { element.jsonObject[it] ?: JsonNull })

    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonObject(keys.zip(element).toMap()) else element
}

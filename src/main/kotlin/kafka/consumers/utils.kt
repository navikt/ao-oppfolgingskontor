package kafka.consumers

import kotlinx.serialization.json.Json
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer


inline fun <reified T> jsonSerde(): Serde<T> {
    return object : Serde<T> {
        override fun serializer(): Serializer<T> =
            Serializer<T> { topic, data -> Json.encodeToString(data).toByteArray() }

        override fun deserializer(): Deserializer<T> =
            Deserializer<T> { topic, data -> Json.decodeFromString(data.decodeToString()) }
    }
}
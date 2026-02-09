package kafka.consumers

import kotlinx.serialization.json.Json
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer


val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

inline fun <reified T> jsonSerde(): Serde<T> {
    return object : Serde<T> {
        override fun serializer(): Serializer<T> =
            Serializer<T> { topic, data -> json.encodeToString(data).toByteArray() }

        override fun deserializer(): Deserializer<T> =
            Deserializer<T> { topic, data -> json.decodeFromString(data.decodeToString()) }
    }
}
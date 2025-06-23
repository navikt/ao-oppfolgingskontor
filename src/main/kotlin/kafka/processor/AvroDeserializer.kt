package no.nav.kafka.processor

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import org.apache.kafka.common.serialization.Deserializer


data class LeesahDto(
    val id: String,
    val name: String,
    val age: Int
)

class LeesahAvroDeserializer (
    val schemaRegistryUrl: String,
    val schemaRegistryUser: String,
    val schemaRegistryPassword: String
): Deserializer<LeesahDto> {
    val SCHEMA_MAP_CAPACITY: Int = 100
    val schemaRegistryClient: SchemaRegistryClient by lazy {
        val configs: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        configs.put(SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE, "USER_INFO")
        configs.put(SchemaRegistryClientConfig.USER_INFO_CONFIG, String.format("%s:%s", schemaRegistryUser, schemaRegistryPassword))
        CachedSchemaRegistryClient(schemaRegistryUrl, SCHEMA_MAP_CAPACITY, configs)
    }
    val avroDeserializer: KafkaAvroDeserializer = KafkaAvroDeserializer(schemaRegistryClient)

    override fun deserialize(topic: String, payload: ByteArray): LeesahDto {
        return avroDeserializer.deserialize(topic, payload) as LeesahDto
    }
}
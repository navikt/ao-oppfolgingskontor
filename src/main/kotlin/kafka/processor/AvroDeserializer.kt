package no.nav.kafka.processor

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.streams.serdes.avro.SpecificAvroDeserializer
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import org.apache.avro.Schema
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.common.serialization.Deserializer


data class LeesahDto(
    val id: String,
    val name: String,
    val age: Int
): SpecificRecord {
    override fun put(i: Int, v: Any?) {
        TODO("Not yet implemented")
    }

    override fun get(i: Int): Any? {
        TODO("Not yet implemented")
    }

    override fun getSchema(): Schema? {
        TODO("Not yet implemented")
    }
}

class LeesahAvroDeserializer (
    val schemaRegistryUrl: String,
    val schemaRegistryUser: String,
    val schemaRegistryPassword: String
) {
    val SCHEMA_MAP_CAPACITY: Int = 100
    val schemaRegistryClient: SchemaRegistryClient by lazy {
        val configs: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        configs.put(SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE, "USER_INFO")
        configs.put(SchemaRegistryClientConfig.USER_INFO_CONFIG, String.format("%s:%s", schemaRegistryUser, schemaRegistryPassword))
        CachedSchemaRegistryClient(schemaRegistryUrl, SCHEMA_MAP_CAPACITY, configs)
    }
    val avroDeserializer: SpecificAvroSerde<LeesahDto> = SpecificAvroSerde<LeesahDto>(schemaRegistryClient)
}

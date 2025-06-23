package no.nav.kafka.processor

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.ktor.server.config.ApplicationConfig
import org.apache.avro.Schema
import org.apache.avro.specific.SpecificRecord

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
    config: ApplicationConfig,
) {
    val schemaRegistryUrl: String = config.property("kafka.schema-registry").getString()
    val schemaRegistryUser: String = config.property("kafka.schema-registry-user").getString()
    val schemaRegistryPassword: String = config.property("kafka.schema-registry-password").getString()
    val SCHEMA_MAP_CAPACITY: Int = 100
    val schemaRegistryClient: SchemaRegistryClient by lazy {
        val configs = mapOf(
            SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE to "USER_INFO",
            SchemaRegistryClientConfig.USER_INFO_CONFIG to String.format("%s:%s", schemaRegistryUser, schemaRegistryPassword)
        )
        CachedSchemaRegistryClient(schemaRegistryUrl, SCHEMA_MAP_CAPACITY, configs)
    }

    val deserializer: SpecificAvroSerde<LeesahDto> = SpecificAvroSerde<LeesahDto>(schemaRegistryClient)
        .apply {
            configure(
                mapOf(
                    "specific.avro.reader" to true
                ),
                false
            )
        }
}

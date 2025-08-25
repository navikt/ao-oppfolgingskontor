package no.nav.kafka.processor

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.ktor.server.config.ApplicationConfig
import no.nav.person.pdl.aktor.v2.Aktor
import no.nav.person.pdl.leesah.Personhendelse
import org.apache.kafka.common.serialization.Serdes

class AvroSerdes (
    config: ApplicationConfig,
) {
    val schemaRegistryUrl: String = config.property("kafka.schema-registry").getString()
    val schemaRegistryUser: String = config.property("kafka.schema-registry-user").getString()
    val schemaRegistryPassword: String = config.property("kafka.schema-registry-password").getString()
    val SCHEMA_MAP_CAPACITY: Int = 100
    private val schemaRegistryConfig: Map<String, Any> = mapOf(
        SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE to "USER_INFO",
        SchemaRegistryClientConfig.USER_INFO_CONFIG to String.format("%s:%s", schemaRegistryUser, schemaRegistryPassword),
        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl
    )
    val schemaRegistryClient: SchemaRegistryClient by lazy {
        CachedSchemaRegistryClient(schemaRegistryUrl, SCHEMA_MAP_CAPACITY, schemaRegistryConfig)
    }

    private val valueSerdeConfig = mapOf(
        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl,
        "specific.avro.reader" to true
    )
    val leesahValueAvroSerde: SpecificAvroSerde<Personhendelse> = SpecificAvroSerde<Personhendelse>(schemaRegistryClient)
        .apply {
            configure(
                valueSerdeConfig,
                false
            )
        }

    val aktorV2ValueAvroSerde: SpecificAvroSerde<Aktor> = SpecificAvroSerde<Aktor>(schemaRegistryClient)
        .apply {
            configure(
                valueSerdeConfig,
                false
            )
        }


    private val keySerializer = TypedKafkaAvroSerializer<String>()
        .apply {
            configure(schemaRegistryConfig, true)
        }
    private val keyDeserializer = TypedKafkaAvroDeserializer(String::class.java)
        .apply {
            configure(schemaRegistryConfig, true)
        }
    val leesahKeyAvroSerde = Serdes.serdeFrom(keySerializer, keyDeserializer)
    val aktorV2KeyAvroSerde = Serdes.serdeFrom(keySerializer, keyDeserializer)
}

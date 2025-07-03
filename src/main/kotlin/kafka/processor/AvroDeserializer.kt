package no.nav.kafka.processor

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.ktor.server.config.ApplicationConfig
import no.nav.person.pdl.leesah.Personhendelse
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes

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

    val serdeConfig = mapOf(
        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl,
    )

    val valueDeserializer: SpecificAvroSerde<Personhendelse> = SpecificAvroSerde<Personhendelse>(schemaRegistryClient)
        .apply {
            configure(
                mapOf(
                    "schema.registry.url" to schemaRegistryUrl,
                    "specific.avro.reader" to true
                ),
                false
            )
        }
    val keySerializer = KafkaAvroSerializer(schemaRegistryClient)
        .apply {
            configure(
                serdeConfig,
                true
            )
        }
    val keyDeserializer = KafkaAvroDeserializer(schemaRegistryClient)
        .apply {
            configure(
                serdeConfig,
                true
            )
        }
    val keyAvroSerde = Serdes.serdeFrom(keySerializer, keyDeserializer)
}

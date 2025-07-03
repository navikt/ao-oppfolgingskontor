package no.nav.kafka.processor

import io.confluent.kafka.serializers.KafkaAvroSerializer
import org.apache.kafka.common.serialization.Serializer

/**
 * En typesikker Kotlin-wrapper rundt KafkaAvroSerializer.
 * Den aksepterer en spesifikk type T og delegerer serialiseringen
 * til den underliggende, utypede serializeren.
 *
 * @param T Den spesifikke typen som skal serialiseres (f.eks. String, Long).
 */
class TypedKafkaAvroSerializer<T : Any> : Serializer<T> {

    // Intern instans av den originale serializeren fra Confluent
    private val inner = KafkaAvroSerializer()

    override fun configure(configs: Map<String, *>?, isKey: Boolean) {
        inner.configure(configs, isKey)
    }

    /**
     * Serialiserer data av typen T. Siden T alltid vil være en subklasse
     * av Object, er kallet til inner.serialize alltid trygt.
     */
    override fun serialize(topic: String, data: T?): ByteArray? {
        // Delegerer direkte. Kan trygt sende T til en metode som forventer Object.
        return inner.serialize(topic, data)
    }

    override fun close() {
        inner.close()
    }
}
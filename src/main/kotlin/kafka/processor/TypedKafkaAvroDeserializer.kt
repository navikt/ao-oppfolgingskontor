package no.nav.kafka.processor

import io.confluent.kafka.serializers.KafkaAvroDeserializer
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.Deserializer


/**
 * En typesikker wrapper rundt KafkaAvroDeserializer.
 * Den delegerer deserialiseringen og sørger for at resultatet
 * kan trygt castes til den forventede typen T.
 *
 * @param <T> Den forventede typen etter deserialisering (f.eks. String, Integer, etc.).
</T> */

class TypedKafkaAvroDeserializer<T>(private val targetType: Class<T>) :
    Deserializer<T?> {
    private val inner = KafkaAvroDeserializer()

    override fun configure(configs: Map<String, *>, isKey: Boolean) {
        inner.configure(configs, isKey)
    }

    override fun deserialize(topic: String, data: ByteArray?): T? {
        if (data == null) {
            return null
        }

        // Deleger til den underliggende, utypede deserializeren
        val deserializedObject = inner.deserialize(topic, data)

        // Utfør en trygg cast
        if (targetType.isInstance(deserializedObject)) {
            return targetType.cast(deserializedObject)
        }

        // Kast en meningsfull feilmelding hvis typen er feil
        throw SerializationException(
            "Feil under deserialisering. Forventet type " + targetType.name +
                    ", men fikk " + deserializedObject.javaClass.name
        )
    }

    override fun close() {
        inner.close()
    }
}
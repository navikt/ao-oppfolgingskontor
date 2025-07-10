package no.nav.kafka.retry.library

import org.apache.avro.io.DatumWriter
import org.apache.avro.io.EncoderFactory
import org.apache.avro.specific.SpecificDatumWriter
import org.apache.avro.specific.SpecificRecord
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Et singleton-objekt for å konvertere Avro-records til JSON-strenger.
 * Bruker Avros innebygde verktøy for en standard-korrekt konvertering.
 */
object AvroJsonConverter {
    /**
     * Konverterer et Avro SpecificRecord-objekt til en JSON-streng.
     *
     * @param T Typen til Avro-objektet, må arve fra SpecificRecord.
     * @param avroRecord Det nullable Avro-objektet som skal konverteres.
     * @return En JSON-representasjon av objektet, eller null hvis input er null.
     *         Returnerer en feil-JSON ved konverteringsfeil.
     */
    fun <T : SpecificRecord> convertAvroToJson(avroRecord: T?, pretty: Boolean? = true): String? {
        avroRecord ?: return null

         return ByteArrayOutputStream().use { outputStream ->
                val writer: DatumWriter<T> = SpecificDatumWriter(avroRecord.schema)
                val encoder = EncoderFactory.get().jsonEncoder(avroRecord.schema, outputStream, pretty ?: true)
                writer.write(avroRecord, encoder)
                encoder.flush()
                outputStream.toString(StandardCharsets.UTF_8.name())
         }
    }
}
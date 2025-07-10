package no.nav.kafka.retry.library

import org.apache.avro.io.DatumWriter
import org.apache.avro.io.EncoderFactory
import org.apache.avro.specific.SpecificDatumWriter
import org.apache.avro.specific.SpecificRecord
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        // Idiomatisk null-sjekk med "elvis operator". Returnerer null hvis avroRecord er null.
        avroRecord ?: return null

        return try {
            // .use håndterer automatisk lukking av streamen (tilsvarende try-with-resources)
            ByteArrayOutputStream().use { outputStream ->
                // 1. Bruk property-access (`.schema`) i stedet for `getSchema()`
                val writer: DatumWriter<T> = SpecificDatumWriter(avroRecord.schema)

                // 2. Opprett en JsonEncoder
                val encoder = EncoderFactory.get().jsonEncoder(avroRecord.schema, outputStream, pretty ?: true)

                // 3. Skriv dataen
                writer.write(avroRecord, encoder)
                encoder.flush()

                // 4. .use-blokken returnerer verdien av det siste uttrykket.
                // Bruk StandardCharsets for robusthet.
                outputStream.toString(StandardCharsets.UTF_8.name())
            }
        } catch (e: IOException) {
            // Loggfør feilen i en reell applikasjon (f.eks. med SLF4J)
            e.printStackTrace()
            // Returner en feilmelding i JSON-format
            """{"error": "Failed to convert Avro to JSON", "message": "${e.message}"}"""
        }
    }
}
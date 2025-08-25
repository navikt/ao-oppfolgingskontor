package no.nav.kafka.retry.library.internal

import java.time.OffsetDateTime

data class FailedMessage(
    val id: Long,
    val messageKeyText: String, // Lagres som String for enkel indeksering
    val messageKeyBytes: ByteArray?, // Den komplette nøkkelen som ByteArray.
    val messageValue: ByteArray?, // Rådata fra Kafka
    val queueTimestamp: OffsetDateTime,
    val retryCount: Int = 0,
    val lastAttemptTimestamp: OffsetDateTime? = null,
    val failureReason: String? = null
) {
    // ByteArray-equals er basert på referanse, så vi må overstyre for korrekt sammenligning
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FailedMessage
        if (id != other.id) return false
        if (!messageValue.contentEquals(other.messageValue)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + messageValue.contentHashCode()
        return result
    }
}
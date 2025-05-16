package no.nav.db.dto

import java.time.OffsetDateTime

/* Data carrier from DB to services or controllers. Not dto exposed through http-APIs */
sealed class DBKontor(
    val kontorId: String,
    val metadata: KontorMetadata
)

data class KontorMetadata(
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

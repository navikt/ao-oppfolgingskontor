package no.nav.db.dto

import java.time.OffsetDateTime

sealed class Kontor(
    val kontorId: String,
    val metadata: KontorMetadata
)

data class KontorMetadata(
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val endretAv: String,
    val endretAvType: String,
)

package no.nav.db.dto

import kotlinx.datetime.LocalDateTime

sealed class Kontor(
    val kontorId: String,
    val metadata: KontorMetadata
)

data class KontorMetadata(
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val endretAv: String,
    val endretAvType: String,
)

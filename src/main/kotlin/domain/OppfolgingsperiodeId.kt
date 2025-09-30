package no.nav.domain

import kotlinx.serialization.Serializable
import utils.UUIDSerializer
import java.util.UUID

@JvmInline
@Serializable
value class OppfolgingsperiodeId(
    @Serializable(with = UUIDSerializer::class)
    val value: UUID
)
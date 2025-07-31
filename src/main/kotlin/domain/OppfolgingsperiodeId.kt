package no.nav.domain

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.UUID

@JvmInline
@Serializable
value class OppfolgingsperiodeId(
    @Contextual
    val value: UUID
)
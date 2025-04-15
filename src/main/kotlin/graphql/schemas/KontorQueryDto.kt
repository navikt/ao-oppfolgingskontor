package no.nav.graphql.schemas

import kotlinx.serialization.Serializable
import no.nav.domain.KontorKilde

@Serializable
data class KontorQueryDto(
    val kontorId: String,
    val kilde: KontorKilde,
)

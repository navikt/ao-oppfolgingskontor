package no.nav.graphql.schemas

import kotlinx.serialization.Serializable
import no.nav.graphql.queries.KontorKilde

@Serializable
data class KontorQueryDto(
    val kontorId: String,
    val kilde: KontorKilde,
)

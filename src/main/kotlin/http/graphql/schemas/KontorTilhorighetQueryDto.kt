package no.nav.http.graphql.schemas

import kotlinx.serialization.Serializable
import no.nav.domain.KontorKilde

@Serializable
data class KontorTilhorighetQueryDto(
    val kontorId: String,
    val kilde: KontorKilde,
)

package no.nav.graphql.schemas

import kotlinx.serialization.Serializable
import no.nav.domain.KontorEndringsType
import no.nav.graphql.queries.KontorKilde

@Serializable
data class KontorHistorikkQueryDto(
    val kontorId: String,
    val kilde: KontorKilde,
    val endringsType: KontorEndringsType,
    val endretAv: String,
    val endretAvType: String,
    val endretTidspunkt: String,
)

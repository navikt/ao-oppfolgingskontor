package no.nav.http.graphql.schemas

import kotlinx.serialization.Serializable
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorType

@Serializable
data class KontorHistorikkQueryDto(
    val ident: String,
    val kontorId: String,
    val kontorType: KontorType,
    val endringsType: KontorEndringsType,
    val endretAv: String,
    val endretAvType: String,
    val endretTidspunkt: String,
    val kontorNavn: String?,
)

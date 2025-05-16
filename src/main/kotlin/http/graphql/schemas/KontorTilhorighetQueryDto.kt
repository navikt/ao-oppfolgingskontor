package no.nav.http.graphql.schemas

import kotlinx.serialization.Serializable
import no.nav.domain.KontorKilde

@Serializable
data class KontorTilhorighetQueryDto(
    val kontorId: String,
    val kilde: KontorKilde,
    val registrant: String,
    val registrantType: RegistrantTypeDto
)

enum class RegistrantTypeDto {
    ARENA, // I arena settes kontor noen ganger av veileder og noen ganger av system men det er vanskelig å se hvilken
    VEILEDER,
    SYSTEM, // Automatiske kontorsettinger, feks ved arbeidssøkerregistrering, endring i adressebeskyttelse eller skjermingstatus
}

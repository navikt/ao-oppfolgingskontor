package no.nav.http.graphql.schemas

import kotlinx.serialization.Serializable
import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.ArenaKontor
import no.nav.domain.GeografiskTilknyttetKontor

@Serializable
data class KontorTilhorigheterQueryDto(
    val arena: ArenaKontorDto?,
    val geografiskTilknytning: GeografiskTilknyttetKontorDto?,
    val arbeidsoppfolging: ArbeidsoppfolgingKontorDto?,
)

@Serializable
data class ArenaKontorDto(
    val kontorId: String,
    val kontorNavn: String,
)
fun ArenaKontor.toArenaKontorDto() = ArenaKontorDto(
    kontorId = this.kontorId.id,
    kontorNavn = this.kontorNavn.navn,
)

@Serializable
data class GeografiskTilknyttetKontorDto(
    val kontorId: String,
    val kontorNavn: String,
)
fun GeografiskTilknyttetKontor.toGeografiskTilknyttetKontorDto() = GeografiskTilknyttetKontorDto(
    kontorId = this.kontorId.id,
    kontorNavn = this.kontorNavn.navn,
)

@Serializable
data class ArbeidsoppfolgingKontorDto(
    val kontorId: String,
    val kontorNavn: String,
)
fun ArbeidsoppfolgingsKontor.toArbeidsoppfolgingKontorDto() = ArbeidsoppfolgingKontorDto(
    kontorId = this.kontorId.id,
    kontorNavn = this.kontorNavn.navn,
)


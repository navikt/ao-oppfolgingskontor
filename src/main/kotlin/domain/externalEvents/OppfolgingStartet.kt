package no.nav.domain.externalEvents

import kotlinx.serialization.Serializable
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.utils.OffsetDateTimeSerializer
import no.nav.utils.ZonedDateTimeSerializer
import java.time.OffsetDateTime
import java.time.ZonedDateTime

sealed class OppfolgingsperiodeEndret {
    abstract val fnr: Ident
    abstract val periodeId: OppfolgingsperiodeId
}

@Serializable
data class OppfolgingsperiodeStartet(
    override val fnr: IdentSomKanLagres,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val startDato: ZonedDateTime,
    override val periodeId: OppfolgingsperiodeId,
    val arenaKontorFraOppfolgingsbrukerTopic: TidligArenaKontor?,
    val erArbeidssøkerRegistrering: Boolean,
): OppfolgingsperiodeEndret()

class OppfolgingsperiodeAvsluttet(
    override val fnr: IdentSomKanLagres,
    val startDato: ZonedDateTime,
    override val periodeId: OppfolgingsperiodeId,
): OppfolgingsperiodeEndret() {
    override fun equals(other: Any?): Boolean {
        if (other !is OppfolgingsperiodeAvsluttet) return false
        if (fnr != other.fnr) return false
        if (startDato != other.startDato) return false
        if (periodeId != other.periodeId) return false
        return true
    }
}

@Serializable
data class TidligArenaKontor(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val sistEndretDato: OffsetDateTime,
    val kontor: KontorId,
)
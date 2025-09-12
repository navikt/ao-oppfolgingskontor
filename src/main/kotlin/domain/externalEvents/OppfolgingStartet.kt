package no.nav.domain.externalEvents

import db.entity.TidligArenaKontorEntity
import no.nav.db.Ident
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import java.time.OffsetDateTime
import java.time.ZonedDateTime

sealed class OppfolgingsperiodeEndret {
    abstract val fnr: Ident
    abstract val periodeId: OppfolgingsperiodeId
}

data class OppfolgingsperiodeStartet(
    override val fnr: Ident,
    val startDato: ZonedDateTime,
    override val periodeId: OppfolgingsperiodeId,
    val startetArenaKontor: KontorId? = null,
    val arenaKontorFraOppfolgingsbrukerTopic: TidligArenaKontor?
): OppfolgingsperiodeEndret()

class OppfolgingsperiodeAvsluttet(
    override val fnr: Ident,
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

data class TidligArenaKontor(
    val sistEndDato: OffsetDateTime,
    val kontor: KontorId,
)
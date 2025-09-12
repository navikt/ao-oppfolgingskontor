package no.nav.domain.externalEvents

import db.entity.TidligArenaKontorEntity
import no.nav.db.Ident
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import java.time.ZonedDateTime

sealed class OppfolgingsperiodeEndret {
    abstract val fnr: Ident
    abstract val periodeId: OppfolgingsperiodeId
}

class OppfolgingsperiodeStartet(
    override val fnr: Ident,
    val startDato: ZonedDateTime,
    override val periodeId: OppfolgingsperiodeId,
    val startetArenaKontor: KontorId? = null,
    val arenaKontorFraOppfolgingsbrukerTopic: TidligArenaKontorEntity?
): OppfolgingsperiodeEndret() {
    override fun equals(other: Any?): Boolean {
        if (other !is OppfolgingsperiodeStartet) return false
        if (fnr != other.fnr) return false
        if (startDato != other.startDato) return false
        if (periodeId != other.periodeId) return false
        return true
    }
}

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

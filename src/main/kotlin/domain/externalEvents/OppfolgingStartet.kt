package no.nav.domain.externalEvents

import kotlinx.serialization.Serializable
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.utils.ZonedDateTimeSerializer
import java.time.ZonedDateTime

@Serializable
sealed class OppfolgingsperiodeEndret {
    abstract val fnr: Ident
    abstract val periodeId: OppfolgingsperiodeId
}

@Serializable
class OppfolgingsperiodeStartet(
    override val fnr: Ident,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val startDato: ZonedDateTime,
    override val periodeId: OppfolgingsperiodeId,
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

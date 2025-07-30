package no.nav.domain.externalEvents

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import java.time.ZonedDateTime

@Serializable
sealed class OppfolgingsperiodeEndret(open val fnr: Ident, open val periodeId: OppfolgingsperiodeId) {}
@Serializable
class OppfolgingsperiodeStartet(
    override val fnr: Ident,
    @Contextual
    val startDato: ZonedDateTime,
    override val periodeId: OppfolgingsperiodeId,
): OppfolgingsperiodeEndret(fnr, periodeId)
class OppfolgingsperiodeAvsluttet(
    override val fnr: Ident,
    val startDato: ZonedDateTime,
    override val periodeId: OppfolgingsperiodeId,
): OppfolgingsperiodeEndret(fnr, periodeId)

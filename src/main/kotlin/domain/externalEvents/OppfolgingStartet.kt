package no.nav.domain.externalEvents

import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import java.time.ZonedDateTime

sealed class OppfolgingsperiodeEndret(val fnr: Ident, val oppfolgingsperiodeId: OppfolgingsperiodeId) {}
class OppfolgingsperiodeStartet(
    fnr: Ident,
    val startDato: ZonedDateTime,
    periodeId: OppfolgingsperiodeId,
): OppfolgingsperiodeEndret(fnr, periodeId)
class OppfolgingsperiodeAvsluttet(
    fnr: Ident,
    val startDato: ZonedDateTime,
    periodeId: OppfolgingsperiodeId,
): OppfolgingsperiodeEndret(fnr, periodeId)

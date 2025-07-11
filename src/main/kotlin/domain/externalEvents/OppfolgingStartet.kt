package no.nav.domain.externalEvents

import no.nav.db.Ident
import java.time.ZonedDateTime
import java.util.UUID

sealed class OppfolgingsperiodeEndret(val fnr: Ident)
class OppfolgingsperiodeStartet(
    fnr: Ident,
    val startDato: ZonedDateTime,
    val oppfolgingsperiodeId: UUID,
): OppfolgingsperiodeEndret(fnr)
class OppfolgingsperiodeAvsluttet(fnr: Ident): OppfolgingsperiodeEndret(fnr)

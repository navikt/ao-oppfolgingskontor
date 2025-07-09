package no.nav.domain.externalEvents

import no.nav.db.Fnr
import java.time.ZonedDateTime
import java.util.UUID

sealed class OppfolgingsperiodeEndret(val fnr: Fnr)
class OppfolgingsperiodeStartet(
    fnr: Fnr,
    val startDato: ZonedDateTime,
    val oppfolgingsperiodeId: UUID,
): OppfolgingsperiodeEndret(fnr)
class OppfolgingsperiodeAvsluttet(fnr: Fnr): OppfolgingsperiodeEndret(fnr)

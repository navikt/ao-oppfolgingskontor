package no.nav.domain.externalEvents

sealed class OppfolgingsperiodeEndret(val aktorId: String)
class OppfolgingperiodeStartet(aktorId: String): OppfolgingsperiodeEndret(aktorId)
class OppfolgingperiodeAvsluttet(aktorId: String): OppfolgingsperiodeEndret(aktorId)

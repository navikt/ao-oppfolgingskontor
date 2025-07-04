package no.nav.domain.externalEvents

sealed class OppfolgingsperiodeEndret(val aktorId: String)
class OppfolgingsperiodeStartet(aktorId: String): OppfolgingsperiodeEndret(aktorId)
class OppfolgingsperiodeAvsluttet(aktorId: String): OppfolgingsperiodeEndret(aktorId)

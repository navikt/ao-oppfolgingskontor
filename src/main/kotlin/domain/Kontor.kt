package no.nav.domain

sealed class Kontor(
    val kontorNavn: KontorNavn,
    val kontorId: KontorId
)

class ArbeidsoppfolgingsKontor(
    kontorNavn: KontorNavn,
    kontorId: KontorId
): Kontor(kontorNavn, kontorId)
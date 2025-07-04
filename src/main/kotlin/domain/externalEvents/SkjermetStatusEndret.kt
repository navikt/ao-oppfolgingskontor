package no.nav.domain.externalEvents

import no.nav.db.Fnr

class SkjermetStatusEndret(
    val fnr: Fnr,
    val erSkjermet: Boolean
)
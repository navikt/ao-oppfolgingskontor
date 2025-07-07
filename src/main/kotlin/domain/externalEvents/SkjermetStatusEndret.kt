package no.nav.domain.externalEvents

import no.nav.db.Fnr
import no.nav.domain.HarSkjerming

class SkjermetStatusEndret(
    val fnr: Fnr,
    val erSkjermet: HarSkjerming
)

package no.nav.domain.externalEvents

import no.nav.db.Ident
import no.nav.domain.HarSkjerming

class SkjermetStatusEndret(
    val fnr: Ident,
    val erSkjermet: HarSkjerming
)

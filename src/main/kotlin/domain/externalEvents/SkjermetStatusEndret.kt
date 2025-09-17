package no.nav.domain.externalEvents

import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming

class SkjermetStatusEndret(
    val fnr: IdentSomKanLagres,
    val erSkjermet: HarSkjerming
)

package no.nav.domain

import no.nav.db.Fnr

/* Skiller seg fra KontorTilordning som ikke har kontor-navn. Når vi leser ut kontor ønsker vi ha med navn */
class KontorTilhorighet(
    val fnr: Fnr,
    val kontor: Kontor
)

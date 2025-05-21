package no.nav.domain

import no.nav.db.Fnr

class KontorTilhorighet(
    val fnr: Fnr,
    val kontor: Kontor
) {
}
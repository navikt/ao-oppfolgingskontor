package no.nav.domain

import no.nav.db.Fnr

/* Tilordning må kunne gjøres uten å ha kontorNavn, brukes bare til skrive-operasjoner. Lese-operasjoner bruker KontorTilhørighet */
class KontorTilordning(
    val fnr: Fnr,
    val kontorId: KontorId
)

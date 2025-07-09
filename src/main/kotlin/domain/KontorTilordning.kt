package no.nav.domain

import no.nav.db.Fnr
import no.nav.db.Ident

/* Tilordning må kunne gjøres uten å ha kontorNavn, brukes bare til skrive-operasjoner. Lese-operasjoner bruker KontorTilhørighet */
data class KontorTilordning(
    val fnr: Ident,
    val kontorId: KontorId,
    val oppfolgingsperiodeId: OppfolgingsperiodeId
)

val INGEN_GT_KONTOR_FALLBACK = KontorId("2990")
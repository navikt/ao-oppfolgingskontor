package no.nav.domain

import no.nav.db.IdentSomKanLagres

/* Tilordning må kunne gjøres uten å ha kontorNavn, brukes bare til skrive-operasjoner. Lese-operasjoner bruker KontorTilhørighet */
data class KontorTilordning(
    val fnr: IdentSomKanLagres,
    val kontorId: KontorId,
    val oppfolgingsperiodeId: OppfolgingsperiodeId
)

val INGEN_GT_KONTOR_FALLBACK = KontorId("2990")
val GT_VAR_LAND_FALLBACK = KontorId("0393")
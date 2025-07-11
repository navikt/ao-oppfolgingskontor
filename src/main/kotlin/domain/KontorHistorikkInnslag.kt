package no.nav.domain

import no.nav.db.Ident

data class KontorHistorikkInnslag(
    val kontorId: KontorId,
    val ident: Ident,
    val registrant: Registrant,
    val kontorendringstype: KontorEndringsType,
    val kontorType: KontorType,
    val oppfolgingId: OppfolgingsperiodeId
)

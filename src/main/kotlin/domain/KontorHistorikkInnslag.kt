package no.nav.domain

import no.nav.db.Fnr

data class KontorHistorikkInnslag(
    val kontorId: KontorId,
    val fnr: Fnr,
    val registrant: Registrant,
    val kontorendringstype: KontorEndringsType,
    val kontorType: KontorType,
)

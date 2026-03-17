package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.Veileder
import org.slf4j.LoggerFactory

class `KontorEndretPgaAdminKontorSammenslåing`(
    tilordning: KontorTilordning,
    adminBruker: Veileder
): AOKontorEndret(
    tilordning,
    adminBruker,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    override fun kontorEndringsType(): KontorEndringsType = KontorEndringsType.KontorMergeViaAdmin
    override fun toHistorikkInnslag() = KontorHistorikkInnslag(
        kontorId = this.tilordning.kontorId,
        ident = this.tilordning.fnr,
        registrant = registrant,
        kontorendringstype = this.kontorEndringsType(),
        kontorType = KontorType.ARBEIDSOPPFOLGING,
        oppfolgingId = this.tilordning.oppfolgingsperiodeId
    )

    override fun logg() {
        log.info("")
    }
}
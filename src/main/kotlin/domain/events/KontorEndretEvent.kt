package no.nav.domain.events

import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.Registrant
import no.nav.domain.System
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

sealed class KontorEndretEvent(
    val tilordning: KontorTilordning
) {
    abstract fun toHistorikkInnslag(): KontorHistorikkInnslag
    abstract fun logg(): Unit

    override fun toString(): String {
        return "${this.javaClass.simpleName}(tilordning=$tilordning)"
    }
}

data class GTKontorEndret(val kontorTilordning: KontorTilordning, val kontorEndringsType: KontorEndringsType) : KontorEndretEvent(kontorTilordning) {
    val log = LoggerFactory.getLogger(this::class.java)

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = System(),
            kontorendringstype = kontorEndringsType,
            kontorType = KontorType.GEOGRAFISK_TILKNYTNING
        )
    }

    override fun logg() {
        log.info("GTKontorEndret: kontorId=${tilordning.kontorId}, kontorEndringsType=$kontorEndringsType")
    }

    companion object {
        fun endretPgaAdressebeskyttelseEndret(
            tilordning: KontorTilordning,
            erStrengtFortrolig: HarStrengtFortroligAdresse
        ) = GTKontorEndret(tilordning, if (erStrengtFortrolig.value) KontorEndringsType.FikkAddressebeskyttelse else KontorEndringsType.AddressebeskyttelseMistet)
        fun endretPgaSkjermingEndret(tilordning: KontorTilordning, erSkjermet: HarSkjerming) =
            GTKontorEndret(tilordning, if (erSkjermet.value) KontorEndringsType.FikkSkjerming else KontorEndringsType.MistetSkjerming)
        fun endretPgaBostedsadresseEndret(tilordning: KontorTilordning) = GTKontorEndret(tilordning, KontorEndringsType.EndretBostedsadresse)
    }
}
sealed class AOKontorEndret(tilordning: KontorTilordning, val registrant: Registrant) : KontorEndretEvent(tilordning)
sealed class ArenaKontorEndret(tilordning: KontorTilordning, val sistEndretDatoArena: OffsetDateTime, val offset: Long?, val partition: Int?) : KontorEndretEvent(tilordning)

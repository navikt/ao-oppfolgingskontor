package kafka.consumers

import no.nav.db.Fnr
import no.nav.domain.OppfolgingsperiodeId
import org.apache.kafka.streams.processor.api.Record
import java.time.ZonedDateTime

object TopicUtils {

    fun oppfolgingStartetMelding(bruker: Bruker): Record<String, String> {
        return Record(bruker.fnr.value, """
            {
                "hendelseType": "OPPFOLGING_STARTET",
                "oppfolgingsPeriodeId": "${bruker.oppfolgingsperiodeId.value}",
                "startetTidspunkt": "${bruker.periodeStart}",
                "startetAv": "G151415",
                "startetAvType": "VEILEDER",
                "startetBegrunnelse": "ARBEIDSSOKER_REGISTRERING",
                "arenaKontor": "4141",
                "foretrukketArbeidsoppfolgingskontor": null,
                "fnr": "${bruker.fnr.value}"
            }
        """, System.currentTimeMillis())
    }

    fun oppfolgingAvsluttetMelding(bruker: Bruker, sluttDato: ZonedDateTime): Record<String, String> {
        return Record(bruker.fnr.value, """
            {
                "fnr": "${bruker.fnr.value}",
                "hendelseType": "OPPFOLGING_AVSLUTTET",
                "oppfolgingsPeriodeId": "${bruker.oppfolgingsperiodeId.value}",
                "startetTidspunkt": "${bruker.periodeStart}",
                "avsluttetTidspunkt": "$sluttDato",
                "avsluttetAv": "G151415",
                "avsluttetAvType": "VEILEDER",
                "avregistreringsType": "UtmeldtEtter28Dager"
            }
        """.trimIndent(), System.currentTimeMillis())
    }

}

data class Bruker(
    val fnr: Fnr,
    val aktorId: String,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val periodeStart: ZonedDateTime
)
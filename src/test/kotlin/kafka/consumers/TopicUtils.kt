package kafka.consumers

import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.kafka.consumers.FormidlingsGruppe
import no.nav.kafka.consumers.Kvalifiseringsgruppe
import org.apache.kafka.streams.processor.api.Record
import java.time.OffsetDateTime
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

    fun endringPaaOppfolgingsBrukerMessage(
            ident: Ident,
            kontorId: String,
            sistEndretDato: OffsetDateTime,
            formidlingsGruppe: FormidlingsGruppe,
            kvalifiseringsgruppe: Kvalifiseringsgruppe): Record<String, String> {
        return Record(ident.value, """{
              "oppfolgingsenhet": "$kontorId",
              "sistEndretDato": "$sistEndretDato",
              "formidlingsgruppe": "${formidlingsGruppe.name}",
              "kvalifiseringsgruppe": "${kvalifiseringsgruppe.name}"
        }""".trimIndent(), System.currentTimeMillis())
    }

}

data class Bruker(
    val fnr: Fnr,
    val aktorId: String,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val periodeStart: ZonedDateTime
)
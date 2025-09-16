package kafka.consumers

import io.mockk.every
import io.mockk.mockk
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.kafka.consumers.FormidlingsGruppe
import no.nav.kafka.consumers.Kvalifiseringsgruppe
import no.nav.person.pdl.aktor.v2.Aktor
import no.nav.person.pdl.aktor.v2.Identifikator
import org.apache.kafka.streams.processor.api.Record
import java.time.OffsetDateTime
import java.time.ZonedDateTime

object TopicUtils {

    fun oppfolgingStartetMelding(bruker: Bruker): Record<String, String> {
        return Record(bruker.ident.value, """
            {
                "hendelseType": "OPPFOLGING_STARTET",
                "oppfolgingsPeriodeId": "${bruker.oppfolgingsperiodeId.value}",
                "startetTidspunkt": "${bruker.periodeStart}",
                "startetAv": "G151415",
                "startetAvType": "VEILEDER",
                "startetBegrunnelse": "ARBEIDSSOKER_REGISTRERING",
                "arenaKontor": "4141",
                "foretrukketArbeidsoppfolgingskontor": null,
                "fnr": "${bruker.ident.value}"
            }
        """, System.currentTimeMillis())
    }

    fun oppfolgingAvsluttetMelding(bruker: Bruker, sluttDato: ZonedDateTime): Record<String, String> {
        return Record(bruker.ident.value, """
            {
                "fnr": "${bruker.ident.value}",
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

    fun aktorV2Message(aktorId: String, identer: List<Ident>): Record<String, Aktor> {
        val value = mockk<Aktor> {
            every { identifikatorer } returns identer.map { ident ->
              mockk<Identifikator> {
                every { gjeldende } returns (ident.historisk == Ident.HistoriskStatus.AKTIV)
                every { idnummer } returns ident.value
              }
            }
        }
        return Record(aktorId, value, System.currentTimeMillis())
    }

}

data class Bruker(
    val ident: Ident,
    val aktorId: String,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val periodeStart: ZonedDateTime
)
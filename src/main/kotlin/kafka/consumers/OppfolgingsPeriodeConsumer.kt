package no.nav.kafka.consumers

import kotlinx.serialization.json.Json
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetTilordning
import no.nav.domain.events.RutingResultat
import no.nav.http.client.PoaoTilgangKtorHttpClient
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.services.KontorTilordningService
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import java.time.ZonedDateTime

class OppfolgingsPeriodeConsumer(
    private val client: PoaoTilgangKtorHttpClient,
    private val kontorTilordningService: KontorTilordningService
) {
    fun consume(record: Record<String, String>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        val aktorId = record.key()
        val oppfolgingsperiode = Json.decodeFromString<OppfolgingsperiodeDTO>(record.value())

        if (oppfolgingsperiode.sluttDato != null) {
            return RecordProcessingResult.SKIP
        }

        val fnr = hentFnrFraPDL(aktorId)
        val result = client.hentTilgangsattributter(aktorId)
        result.map {
            val kontorTilordning = hentTilordning(fnr, it.kontor, hentAlder(fnr), hentProfilering())
            kontorTilordningService.tilordneKontor(kontorTilordning)
        }

        return RecordProcessingResult.COMMIT
    }

    fun hentFnrFraPDL(aktorId: String): String {
        // Implement PDL call to get FNR from AKTOR_ID
        return "12345678901" // Placeholder for actual implementation
    }

    fun hentProfilering(): String {
        // Implement logic to fetch profilering
        return "Bra" // Placeholder for actual implementation
    }

    fun hentAlder(fnr: String): Int {
        // Implement logic to fetch age based on FNR
        return 35 // Placeholder for actual implementation
    }

    fun hentTilordning(
        fnr: String,
        gtKontor: String?,
        alder: Int,
        profilering: String?
    ): OppfolgingsPeriodeStartetTilordning {
        return when {
            profilering == "Bra" && alder > 30 -> {
                OppfolgingsPeriodeStartetTilordning(KontorTilordning(fnr, KontorId("4154")), RutingResultat.RutetTilNOE)
            }

            gtKontor == null -> {
                OppfolgingsPeriodeStartetTilordning(
                    KontorTilordning(fnr, KontorId("2990")),
                    RutingResultat.RutetTilLokalkontor
                )
            }

            else -> {
                OppfolgingsPeriodeStartetTilordning(
                    KontorTilordning(fnr, KontorId(gtKontor)),
                    RutingResultat.RutetTilLokalkontor
                )
            }
        }
    }

    data class OppfolgingsperiodeDTO(
        val uuid: String,
        val startDato: ZonedDateTime,
        val sluttDato: ZonedDateTime?,
        val aktorId: String
    )
}
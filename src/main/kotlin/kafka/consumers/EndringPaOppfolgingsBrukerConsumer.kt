package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.Fnr
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.table.ArenaKontorTable
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.EndringPaaOppfolgingsBrukerFraArena
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.KontorTilordningService
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeService
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

class EndringPaOppfolgingsBrukerConsumer() {
    val log = LoggerFactory.getLogger(EndringPaOppfolgingsBrukerConsumer::class.java)

    val json = Json { ignoreUnknownKeys = true }

    fun consume(record: Record<String, String>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult<Unit, Unit> {
        log.info("Consumed record")
        val fnrString = record.key()
        val endringPaOppfolgingsBruker = json.decodeFromString<EndringPaOppfolgingsBruker>(record.value())
        if (endringPaOppfolgingsBruker.oppfolgingsenhet.isNullOrBlank()) {
            log.warn("Mottok endring på oppfølgingsbruker uten gyldig kontorId")
            return Commit
        }

        val sistEndretKontorEntity = transaction {
            ArenaKontorEntity
                .find { ArenaKontorTable.id eq fnrString }
                .firstOrNull()
        }

        if(sistEndretKontorEntity != null && sistEndretKontorEntity.sistEndretDatoArena > endringPaOppfolgingsBruker.sistEndretDato.convertToOffsetDatetime()) {
            log.warn("Sist endret kontor er eldre enn endring på oppfølgingsbruker")
            return Skip
        }

        val fnr = Fnr(fnrString)
        val oppfolgingperiode = runBlocking { OppfolgingsperiodeService.getCurrentOppfolgingsperiode(fnr) }
        val oppfolgingsperiodeId = when (oppfolgingperiode) {
            is AktivOppfolgingsperiode -> oppfolgingperiode.periodeId
            NotUnderOppfolging -> {
                log.warn("Bruker er ikke under oppfølging, hopper over melding om endring på oppfølgingsbruker")
                return Skip
            }
            is OppfolgingperiodeOppslagFeil -> {
                log.error("Klarte ikke hente oppfølgingsperiode: ${oppfolgingperiode.message}")
                return Retry("Klarte ikke behandle melding om endring på oppfølgingsbruker, feil ved oppslag på oppfølgingsperiode: ${oppfolgingperiode.message}")
            }
        }


        KontorTilordningService.tilordneKontor(
            EndringPaaOppfolgingsBrukerFraArena(
                tilordning = KontorTilordning(
                    fnr = Fnr(fnrString),
                    kontorId = KontorId(endringPaOppfolgingsBruker.oppfolgingsenhet),
                    oppfolgingsperiodeId
                ),
                sistEndretDatoArena = endringPaOppfolgingsBruker.sistEndretDato.convertToOffsetDatetime(),
                offset = maybeRecordMetadata?.offset(),
                partition = maybeRecordMetadata?.partition(),
            )
        )
        return Commit
    }
}

// ""sistEndretDato":string"2025-04-10T13:01:14+02:00"

fun String.convertToOffsetDatetime(): OffsetDateTime {
    return OffsetDateTime.parse(this)
}

@Serializable
data class EndringPaOppfolgingsBruker(
    val oppfolgingsenhet: String?,
    val sistEndretDato: String
)
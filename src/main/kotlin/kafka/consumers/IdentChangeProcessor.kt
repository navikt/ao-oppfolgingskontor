package kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.person.pdl.aktor.v2.Aktor
import no.nav.person.pdl.aktor.v2.Identifikator
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import services.IdentService

class IdentChangeProcessor(
    val identService: IdentService
) {
    val log = LoggerFactory.getLogger(IdentChangeProcessor::class.java)

    fun process(record: Record<String, Aktor>): RecordProcessingResult<String, Aktor> {
        return runBlocking {
            runCatching { AktorId(record.key().replace("\u0000", ""), Ident.HistoriskStatus.UKJENT) }
                .mapCatching { aktorId ->
                    val payload = record.value()
                    if (payload == null) {
                        identService.markerAktorIdSomSlettet(aktorId)
                        Commit()
                    } else {
                        val nyeIdenter = payload.identifikatorer.map { it.toOppdatertIdent() }
                        identService.håndterEndringPåIdenter(aktorId, nyeIdenter)
                        Commit<String, Aktor>()
                    }
                }
                .getOrElse { error ->
                    val message = "Kunne ikke behandle endring i identer: ${error.message}"
                    log.error(message, error)
                    Retry(message)
                }
        }
    }
}

fun Identifikator.toOppdatertIdent(): OppdatertIdent {
    return OppdatertIdent(
        Ident.validateOrThrow(
        this.idnummer,
        if (!this.gjeldende) Ident.HistoriskStatus.HISTORISK else Ident.HistoriskStatus.AKTIV
        ),
    !this.gjeldende
    )
}

data class OppdatertIdent(
    val ident: Ident,
    val historisk: Boolean,
)
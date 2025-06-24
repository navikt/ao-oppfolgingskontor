package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import org.slf4j.LoggerFactory

class LeesahConsumer(
    private val automatiskKontorRutingService: AutomatiskKontorRutingService,
//    private val aoKontorService: AOKontorService
) {
    val log = LoggerFactory.getLogger(this::class.java)

    fun consume(record: Record<String, Personhendelse>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        log.info("Consumer leesah record ${record.value().opplysningstype} ${record.value().endringstype}")

        if (record.value().bostedsadresse != null) {
            log.info("Bostedsadresse endret")
            val fnr = record.value().personidenter.firstOrNull()
            if(fnr == null) {
                log.warn("Ingen personidenter funnet i record, kan ikke håndtere endring på bostedsadresse")
                return RecordProcessingResult.SKIP
            }

            runBlocking {
                automatiskKontorRutingService.handterEndringForBostedsadresse(fnr)
            }
        }

        if (record.value().adressebeskyttelse != null) {
            log.info("Adressebeskyttelse endret")
//            aoKontorService.oppdaterAOKontor(record.value().personidenter.firstOrNull())
        }

        return RecordProcessingResult.COMMIT
    }
}

class GTService {
    fun oppdaterGT(firstOrNull: String?): Unit {
        // Implementasjon for å oppdatere GT
        // Dette kan inkludere kall til eksterne tjenester eller databaseoperasjoner
    }
}

class AOKontorService {
    fun oppdaterAOKontor(firstOrNull: String?): Unit {
        // Implementasjon for å oppdatere AO-kontor
        // Dette kan inkludere kall til eksterne tjenester eller databaseoperasjoner
    }
}


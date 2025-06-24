package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.Fnr
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import org.slf4j.LoggerFactory

class LeesahConsumer(
    private val automatiskKontorRutingService: AutomatiskKontorRutingService,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    fun consume(record: Record<String, Personhendelse>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        log.info("Consumer Personhendelse record ${record.value().opplysningstype} ${record.value().endringstype}")

        record.value().toHendelse()
            .let { hendelse ->
                runBlocking {
                    when (hendelse) {
                        is BostedsadresseEndret -> automatiskKontorRutingService.handterEndringForBostedsadresse(hendelse)
                        is AddressebeskyttelseEndret -> automatiskKontorRutingService.handterEndringForAdressebeskyttelse(hendelse)
                        is IrrelevantHendelse -> {
                            log.info("Hendelse ${hendelse.opplysningstype} er irrelevant for kontor-ruting")
                            RecordProcessingResult.SKIP
                        }
                    }
                }
            }
        return RecordProcessingResult.COMMIT
    }
}

sealed class GrunnlagForKontorEndretHendelse(val fnr: Fnr)
class BostedsadresseEndret(fnr: Fnr): GrunnlagForKontorEndretHendelse(fnr)
class AddressebeskyttelseEndret(fnr: Fnr, gradering: Gradering): GrunnlagForKontorEndretHendelse(fnr)
class IrrelevantHendelse(fnr: Fnr, val opplysningstype: String): GrunnlagForKontorEndretHendelse(fnr)

fun Personhendelse.toHendelse(): GrunnlagForKontorEndretHendelse {
    if (this.personidenter.isEmpty()) {
        throw IllegalStateException("Personhendelse must have at least one personident")
    }
    val fnr = this.personidenter.first()

    if (this.bostedsadresse != null) return BostedsadresseEndret(fnr)
    if (this.adressebeskyttelse != null) return AddressebeskyttelseEndret(fnr, this.adressebeskyttelse.gradering)
    return IrrelevantHendelse(fnr, this.opplysningstype)
}

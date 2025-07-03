package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.Fnr
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
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

    fun consume(record: Record<Any, Personhendelse>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult<Unit, Unit> {
        log.info("Consumer Personhendelse record ${record.value().opplysningstype} ${record.value().endringstype}")
        return handterLeesahHendelse(record.value().toHendelse())
    }

    fun handterLeesahHendelse(hendelse: PersondataEndretHendelse): RecordProcessingResult<Unit, Unit> {
        val result = runBlocking {
            when (hendelse) {
                is BostedsadresseEndret -> automatiskKontorRutingService.handterEndringForBostedsadresse(hendelse)
                is AddressebeskyttelseEndret -> automatiskKontorRutingService.handterEndringForAdressebeskyttelse(hendelse)
                is IrrelevantHendelse -> {
                    log.info("Hendelse ${hendelse.opplysningstype} er irrelevant for kontor-ruting")
                    HåndterPersondataEndretSuccess
                }
            }
        }
        return when (result) {
            is HåndterPersondataEndretSuccess -> Commit
            is HåndterPersondataEndretFail -> {
                log.error(result.message, result.error)
                Retry(result.message)
            }
        }
    }
}

sealed class PersondataEndretHendelse(val fnr: Fnr)
class BostedsadresseEndret(fnr: Fnr): PersondataEndretHendelse(fnr)
class AddressebeskyttelseEndret(fnr: Fnr, val gradering: Gradering): PersondataEndretHendelse(fnr)
class IrrelevantHendelse(fnr: Fnr, val opplysningstype: String): PersondataEndretHendelse(fnr)

fun Personhendelse.toHendelse(): PersondataEndretHendelse {
    if (this.personidenter.isEmpty()) {
        throw IllegalStateException("Personhendelse must have at least one personident")
    }
    val fnrs = this.personidenter.filter { it.length == 11 }
    require(fnrs.size > 1) { "Must be at least one ident with size 11 but found: ${fnrs.map { it.length }.joinToString(",")}" }
    val fnr = fnrs.first()

    if (this.bostedsadresse != null) return BostedsadresseEndret(fnr)
    if (this.adressebeskyttelse != null) return AddressebeskyttelseEndret(fnr, this.adressebeskyttelse.gradering)
    return IrrelevantHendelse(fnr, this.opplysningstype)
}

sealed class HåndterPersondataEndretResultat()
object HåndterPersondataEndretSuccess: HåndterPersondataEndretResultat()
class HåndterPersondataEndretFail(val message: String, val error: Throwable? = null) : HåndterPersondataEndretResultat()


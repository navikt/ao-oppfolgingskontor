package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.Fnr
import no.nav.domain.events.KontorEndretEvent
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.domain.externalEvents.IrrelevantHendelse
import no.nav.domain.externalEvents.PersondataEndret
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrIkkeFunnet
import no.nav.http.client.FnrOppslagFeil
import no.nav.http.client.FnrResult
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import org.slf4j.LoggerFactory

class LeesahConsumer(
    private val automatiskKontorRutingService: AutomatiskKontorRutingService,
    private val fnrProvider: suspend (aktorId: String) -> FnrResult,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    fun consume(record: Record<String, Personhendelse>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult<Unit, Unit> {
        log.info("Consumer Personhendelse record ${record.value().opplysningstype} ${record.value().endringstype}")
        return record.value()
            .let { hendelse ->
                val fnrResult = runBlocking { finnFnr(hendelse) }
                when (fnrResult) {
                    is FnrFunnet -> fnrResult.fnr to hendelse
                    is FnrIkkeFunnet -> return Retry("Kunne ikke håndtere leesah melding: Fnr ikke funnet for bruker: ${fnrResult.message}")
                    is FnrOppslagFeil -> return Retry("Kunne ikke håndtere leesah melding: Feil ved oppslag på fnr:  ${fnrResult.message}")
                }
            }.toHendelse()
            .let { handterLeesahHendelse(it) }
    }

    fun handterLeesahHendelse(hendelse: PersondataEndret): RecordProcessingResult<Unit, Unit> {
        val result = runBlocking {
            when (hendelse) {
                is BostedsadresseEndret -> automatiskKontorRutingService.handterEndringForBostedsadresse(hendelse)
                is AdressebeskyttelseEndret -> automatiskKontorRutingService.handterEndringForAdressebeskyttelse(hendelse)
                is IrrelevantHendelse -> {
                    log.info("Hendelse ${hendelse.opplysningstype} er irrelevant for kontor-ruting")
                    HåndterPersondataEndretSuccess(emptyList())
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

    suspend fun finnFnr(hendelse: Personhendelse): FnrResult {
        if (hendelse.personidenter.isEmpty()) {
            throw IllegalStateException("Personhendelse must have at least one personident")
        }
        val fnrEllerAktorId: FnrEllerAktorId = hendelse.personidenter.first()
        return fnrProvider(fnrEllerAktorId)
    }
}

fun Pair<Fnr, Personhendelse>.toHendelse(): PersondataEndret {
    if (this.second.bostedsadresse != null) return BostedsadresseEndret(this.first)
    if (this.second.adressebeskyttelse != null) return AdressebeskyttelseEndret(
        this.first,
        this.second.adressebeskyttelse.gradering
    )
    return IrrelevantHendelse(this.first, this.second.opplysningstype)
}

sealed class HåndterPersondataEndretResultat()
data class HåndterPersondataEndretSuccess(val endringer: List<KontorEndretEvent>): HåndterPersondataEndretResultat()
class HåndterPersondataEndretFail(val message: String, val error: Throwable? = null) : HåndterPersondataEndretResultat()

typealias FnrEllerAktorId = String

package no.nav.kafka.consumers

import kafka.out.toRecord
import kotlinx.coroutines.runBlocking
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.db.finnForetrukketIdent
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.ArenaKontorEndret
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.domain.externalEvents.IrrelevantHendelse
import no.nav.domain.externalEvents.PersondataEndret
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.http.client.IdentResult
import no.nav.kafka.arbeidsoppfolgingkontorSinkName
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorTilordningService
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class LeesahProcessor(
    private val automatiskKontorRutingService: AutomatiskKontorRutingService,
    private val kontorTilordningService: KontorTilordningService,
    private val fnrProvider: suspend (ident: AktorId) -> IdentResult,
    private val isProduction: Boolean,
    private val brukAoRuting: Boolean
) {
    val log = LoggerFactory.getLogger(this::class.java)

    fun process(
            record: Record<String, Personhendelse>
    ): RecordProcessingResult<String, String> {
        log.info("Consumer Personhendelse record ${record.value().opplysningstype} ${record.value().endringstype}")
        return record.value()
                .let { hendelse ->
                    val fnrResult = runBlocking { finnIdent(hendelse) }
                    when (fnrResult) {
                        is IdentFunnet -> fnrResult.ident to hendelse
                        is IdentIkkeFunnet ->
                                return Retry("Kunne ikke håndtere leesah melding: Fnr ikke funnet for bruker: ${fnrResult.message}")
                        is IdentOppslagFeil ->
                                return Retry("Kunne ikke håndtere leesah melding: Feil ved oppslag på fnr:  ${fnrResult.message}")
                    }
                }
                .toHendelse()
                .let { handterLeesahHendelse(it) }
    }

    fun handterLeesahHendelse(hendelse: PersondataEndret): RecordProcessingResult<String, String> {
        val result = runBlocking {
            when (hendelse) {
                is BostedsadresseEndret -> automatiskKontorRutingService.handterEndringForBostedsadresse(hendelse)
                is AdressebeskyttelseEndret -> automatiskKontorRutingService.handterEndringForAdressebeskyttelse(hendelse)
                is IrrelevantHendelse -> {
                    log.info("Hendelse ${hendelse.opplysningstype} er irrelevant for kontor-ruting")
                    HåndterPersondataEndretSuccess(KontorEndringer())
                }
            }
        }
        return when (result) {
            is HåndterPersondataEndretSuccess -> {
                val aoKontorEndring = result.endringer.aoKontorEndret

                result.endringer.gtKontorEndret
                    ?.let { kontorTilordningService.tilordneKontor(it, brukAoRuting) }

                return when {
                    aoKontorEndring != null -> {
                        kontorTilordningService.tilordneKontor(aoKontorEndring, brukAoRuting)
                        if(isProduction) {
                            log.info("Hopper over forwarding av kontorendring for PROD")
                            Commit() // In production we do not forward kontorendringer, but we still commit the record
                        }
                        else {
                            Forward(aoKontorEndring.toRecord(), arbeidsoppfolgingkontorSinkName)
                        }
                    }
                    else -> Commit()
                }
            }
            is HåndterPersondataEndretFail -> {
                log.error(result.message, result.error)
                Retry(result.message)
            }
        }
    }

    suspend fun finnIdent(hendelse: Personhendelse): IdentResult {
        if (hendelse.personidenter.isEmpty()) {
            throw IllegalStateException("Personhendelse must have at least one personident")
        }

        // Antar alle identer i en Leesah hendelse AKTIV / ikke historiske
        val personIdenter = hendelse.personidenter.map { Ident.validateOrThrow(it, Ident.HistoriskStatus.AKTIV) }
        return personIdenter.finnForetrukketIdent()?.let { IdentFunnet(it) }
            ?: IdentIkkeFunnet("Fant ingen foretrukket ident i Leesah melding sitt 'personidenter' felt, ${personIdenter.joinToString(",") { it.javaClass.simpleName }}")
    }
}

fun Pair<IdentSomKanLagres, Personhendelse>.toHendelse(): PersondataEndret {
    if (this.second.bostedsadresse != null) return BostedsadresseEndret(this.first)
    if (this.second.adressebeskyttelse != null)
            return AdressebeskyttelseEndret(this.first, this.second.adressebeskyttelse.gradering)
    return IrrelevantHendelse(this.first, this.second.opplysningstype)
}

sealed class HåndterPersondataEndretResultat()
data class HåndterPersondataEndretSuccess(val endringer: KontorEndringer): HåndterPersondataEndretResultat()
class HåndterPersondataEndretFail(val message: String, val error: Throwable? = null): HåndterPersondataEndretResultat()

data class KontorEndringer(
    val arenaKontorEndret: ArenaKontorEndret? = null,
    val gtKontorEndret: GTKontorEndret? = null,
    val aoKontorEndret: AOKontorEndret? = null,
)
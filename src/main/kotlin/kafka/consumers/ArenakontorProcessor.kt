package kafka.consumers

import http.client.ArenakontorOppslagFeilet
import http.client.ArenakontorResult
import http.client.ArenakontorFunnet
import http.client.ArenakontorIkkeFunnet
import kotlinx.coroutines.runBlocking
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Npid
import no.nav.domain.KontorTilordning
import no.nav.domain.events.ArenaKontorVedOppfolgingStart
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterResult
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class ArenakontorProcessor(
    private val hentArenakontor: suspend (Ident) -> ArenakontorResult,
    private val lagreKontortilordning: (ArenaKontorVedOppfolgingStart) -> Unit,
    private val hentAlleIdenter: suspend (identInput: Ident) -> IdenterResult
) {
    companion object {
        const val processorName = "ArenakontorProcessor"

        val identSerde: Serde<Ident> = object : Serde<Ident> {
            override fun serializer(): Serializer<Ident> =
                Serializer<Ident> { topic, data -> data.toString().toByteArray() }

            override fun deserializer(): Deserializer<Ident> =
                Deserializer<Ident> { topic, data ->
                    Ident.validateOrThrow(
                        data.decodeToString(),
                        Ident.HistoriskStatus.UKJENT
                    )
                }
        }
        val oppfolgingsperiodeEndretSerde = jsonSerde<OppfolgingsperiodeEndret>()
    }

    val logger = LoggerFactory.getLogger(this::class.java)

    fun process(record: Record<Ident, OppfolgingsperiodeEndret>): RecordProcessingResult<String, String> {
        return runBlocking {
            when (record.value()) {
                is OppfolgingsperiodeAvsluttet -> Skip()
                is OppfolgingsperiodeStartet -> {
                    val oppfølgingsperiodeStartet = record.value() as OppfolgingsperiodeStartet
                    val fnr = oppfølgingsperiodeStartet.fnr
                    val arenakontorOppslag = hentArenakontor(fnr)

                    when (arenakontorOppslag) {
                        is ArenakontorOppslagFeilet -> Retry("Arenakontor-oppslag feilet, må prøve igjen")
                        is ArenakontorFunnet -> {
                            val kontorTilordning = ArenaKontorVedOppfolgingStart(
                                kontorTilordning = KontorTilordning(
                                    fnr = fnr,
                                    kontorId = arenakontorOppslag.kontorId,
                                    oppfolgingsperiodeId = oppfølgingsperiodeStartet.periodeId
                                ),
                                sistEndretIArena = arenakontorOppslag.sistEndret.toOffsetDateTime()
                            )
                            lagreKontortilordning(kontorTilordning)
                            Commit()
                        }

                        is ArenakontorIkkeFunnet -> {
                            val identOppslag = hentAlleIdenter(fnr)
                            if (identOppslag !is IdenterFunnet) return@runBlocking Retry<String, String>("Fant ingen identer på oppslag")
                            val identerSomOppslagKanGjøresPå =
                                (identOppslag as IdenterFunnet).identer
                                    .filter { it.historisk == Ident.HistoriskStatus.AKTIV }
                                    .filter {
                                        it is Dnr || it is Fnr || it is Npid
                                    }.filter { it.value != fnr.value }

                            val oppslagsresultater = identerSomOppslagKanGjøresPå.map { hentArenakontor(fnr) }

                            if (oppslagsresultater.all { it is ArenakontorOppslagFeilet }) {
                                Retry<String, String>("Alle oppslag på identer feilet")
                            }

                            val arenakontorResultat = oppslagsresultater.filter { it is ArenakontorFunnet }
                                .maxByOrNull { (it as ArenakontorFunnet).sistEndret }

                            if (arenakontorResultat == null) {
                                logger.info("Person hadde ingen kontor i Arena ved oppslag på alle identer")
                            } else {
                                val kontorTilordning = ArenaKontorVedOppfolgingStart(
                                    kontorTilordning = KontorTilordning(
                                        fnr = fnr,
                                        kontorId = (arenakontorResultat as ArenakontorFunnet).kontorId,
                                        oppfolgingsperiodeId = oppfølgingsperiodeStartet.periodeId
                                    ),
                                    sistEndretIArena = arenakontorResultat.sistEndret.toOffsetDateTime()
                                )
                                lagreKontortilordning(kontorTilordning)
                            }
                            Commit()
                        }
                    }
                }
            }
        }
    }
}

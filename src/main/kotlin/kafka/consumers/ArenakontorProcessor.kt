package kafka.consumers

import domain.ArenaKontorUtvidet
import http.client.ArenakontorOppslagFeilet
import http.client.ArenakontorResult
import http.client.ArenakontorFunnet
import http.client.ArenakontorIkkeFunnet
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorTilordning
import no.nav.domain.events.ArenaKontorHentetSynkrontVedOppfolgingStart
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
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
    private val lagreKontortilordning: (ArenaKontorHentetSynkrontVedOppfolgingStart) -> Unit,
    val arenaKontorProvider: suspend (IdentSomKanLagres) -> ArenaKontorUtvidet?,
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
                        is ArenakontorOppslagFeilet -> {
                            logger.error("Arenakontor-oppslag feilet", arenakontorOppslag.e)
                            Retry("Arenakontor-oppslag feilet, må prøve igjen")
                        }

                        is ArenakontorFunnet -> {
                            val kontorTilordning = ArenaKontorHentetSynkrontVedOppfolgingStart(
                                kontorTilordning = KontorTilordning(
                                    fnr = fnr,
                                    kontorId = arenakontorOppslag.kontorId,
                                    oppfolgingsperiodeId = oppfølgingsperiodeStartet.periodeId
                                ),
                                sistEndretIArena = arenakontorOppslag.sistEndret.toOffsetDateTime()
                            )

                            val alleredeLagretArenaKontor = arenaKontorProvider(fnr)
                            val lagretArenakontorErNyest =
                                if (alleredeLagretArenaKontor?.sistEndretDatoArena == null) false
                                else alleredeLagretArenaKontor.sistEndretDatoArena > kontorTilordning.sistEndretDatoArena

                            val kontorIdErLik = alleredeLagretArenaKontor?.kontorId == kontorTilordning.tilordning.kontorId
                            if (lagretArenakontorErNyest || kontorIdErLik) {
                                Skip<String, String>()
                            } else {
                                logger.info("Lagrer funnet arenakontor")
                                lagreKontortilordning(kontorTilordning)
                                Commit()
                            }
                        }

                        is ArenakontorIkkeFunnet -> {
                            logger.info("Fant ikke arena-kontor for mottatt ident - gjør ikke oppslag på andre identer")
                            Commit()
                        }
                    }
                }
            }
        }
    }
}

package kafka.consumers

import http.client.ArenakontorOppslagFeilet
import http.client.ArenakontorResult
import http.client.FantArenakontor
import http.client.FantIkkeArenakontor
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.domain.KontorTilordning
import no.nav.domain.events.ArenaKontorVedOppfolgingStart
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

class ArenakontorProcessor(
    private val hentArenakontor: suspend (Ident) -> ArenakontorResult,
    private val lagreKontortilordning: (ArenaKontorVedOppfolgingStart) -> Unit
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
                        is FantArenakontor -> {
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
                        is FantIkkeArenakontor -> {
                            // TODO: Forsøk på annen ident?
                            Commit()
                        }
                    }
                }
            }
        }
    }
}
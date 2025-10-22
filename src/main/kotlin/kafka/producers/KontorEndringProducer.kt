package kafka.producers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.KontorSattAvVeileder
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import kotlin.String

class KontorEndringProducer(
    val producer: Producer<String, String?>,
    val kontorTopicNavn: String,
    val kontorNavnProvider: suspend (kontorId: KontorId) -> KontorNavn,
    val aktorIdProvider: suspend (ident: IdentSomKanLagres) -> AktorId?,
) {

    suspend fun publiserEndringPåKontor(event: KontorSattAvVeileder): Result<Unit> {
        return runCatching {
            val value = event.toKontorTilordningMeldingDto(
                aktorIdProvider(event.tilordning.fnr)
                    ?: throw RuntimeException("Finner ikke aktorId for ident ${event.tilordning.fnr.value}"),
                kontorNavnProvider(event.tilordning.kontorId)
            )
            publiserEndringPåKontor(value)
        }
    }

    suspend fun publiserEndringPåKontor(event: KontorTilordningMelding): Result<Unit> {
        return runCatching {
            val ident = Ident.validateOrThrow(event.ident, Ident.HistoriskStatus.UKJENT) as? IdentSomKanLagres
                ?: throw IllegalArgumentException("Kan ikke publisere kontor-endring på aktørid, trenger annen ident")
            publiserEndringPåKontor(
                KontorTilordningMeldingDto(
                    kontorId = event.kontorId,
                    kontorNavn = kontorNavnProvider(KontorId(event.kontorId)).navn,
                    oppfolgingsperiodeId = event.oppfolgingsperiodeId,
                    aktorId = aktorIdProvider(ident)?.value
                        ?: throw RuntimeException("Finner ikke aktorId for ident ${event.ident}"),
                    ident = event.ident
                )
            )
        }
    }

    private fun publiserEndringPåKontor(event: KontorTilordningMeldingDto): Result<Unit> {
        return runCatching {
            val record = ProducerRecord(
                kontorTopicNavn,
                event.oppfolgingsperiodeId,
                Json.encodeToString(event)
            )
            producer.send(record)
        }
    }

    fun publiserTombstone(oppfolgingPeriodeId: OppfolgingsperiodeId): Result<Unit> {
        return runCatching {
            val record: ProducerRecord<String,String?> = ProducerRecord(
                kontorTopicNavn,
                oppfolgingPeriodeId.value.toString(),
                null
            )
            producer.send(record)
        }
    }
}

fun AOKontorEndret.toKontorTilordningMeldingDto(
    aktorId: AktorId,
    kontorNavn: KontorNavn
): KontorTilordningMeldingDto {
    return KontorTilordningMeldingDto(
        kontorId = this.tilordning.kontorId.id,
        kontorNavn = kontorNavn.navn,
        oppfolgingsperiodeId = this.tilordning.oppfolgingsperiodeId.value.toString(),
        aktorId = aktorId.value,
        ident = this.tilordning.fnr.value
    )
}

fun AOKontorEndret.toKontorTilordningMelding(): KontorTilordningMelding {
    return KontorTilordningMelding(
        kontorId = this.tilordning.kontorId.id,
        oppfolgingsperiodeId = this.tilordning.oppfolgingsperiodeId.value.toString(),
        ident = this.tilordning.fnr.value
    )
}

@Serializable
data class KontorTilordningMeldingDto(
    val kontorId: String,
    val kontorNavn: String,
    val oppfolgingsperiodeId: String,
    val aktorId: String,
    val ident: String
)

/**
 * Same as KontorTilordningMeldingDto but without AktorId and kontorNavn.
 * Needed to avoid fetching aktorId and kontorNavn in KontortilordningsProcessor
 * but still have a serializable data-transfer-object to pass it to the next processing step
 * */
@Serializable
data class KontorTilordningMelding(
    val kontorId: String,
    val oppfolgingsperiodeId: String,
    val ident: String
)
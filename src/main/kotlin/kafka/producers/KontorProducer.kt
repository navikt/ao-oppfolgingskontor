package kafka.producers

import Topic
import Topics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.AktorId
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.events.KontorSattAvVeileder
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import kotlin.String

class KontorProducer(
    val producer: Producer<String, String>,
    val kontorTopicNavn: String,
    val kontorNavnProvider: suspend (kontorId: KontorId) -> KontorNavn,
    val aktorIdProvider: suspend (ident: IdentSomKanLagres) -> AktorId?,
) {

    suspend fun publiserEndringPåKontor(event: KontorSattAvVeileder): Result<Unit> {
        return runCatching {
            val key = event.tilordning.fnr
            val value = event.toKontorTilordningMeldingDto(
                aktorIdProvider(event.tilordning.fnr) ?: throw RuntimeException("Finner ikke aktorId for ident ${event.tilordning.fnr.value}"),
                kontorNavnProvider(event.tilordning.kontorId)
            )
            val record = ProducerRecord(
                kontorTopicNavn,
                key.value,
                Json.encodeToString(value)
            )
            producer.send(record)
        }
    }
}

fun KontorSattAvVeileder.toKontorTilordningMeldingDto(
    aktorId: AktorId,
    kontorNavn: KontorNavn
): KontorTilordningMeldingDto {
    return KontorTilordningMeldingDto(
        kontorId = this.tilordning.kontorId.id,
        kontorNavn = kontorNavn.navn,
        oppfolgingsPeriodeId = this.tilordning.oppfolgingsperiodeId.value.toString(),
        aktorId = aktorId.value,
        ident = this.tilordning.fnr.value
    )
}

@Serializable
data class KontorTilordningMeldingDto(
    val kontorId: String,
    val kontorNavn: String,
    val oppfolgingsPeriodeId: String,
    val aktorId: String,
    val ident: String
)
package kafka.producers

import Topics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.AktorId
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.events.KontorSattAvVeileder
import no.nav.kafka.config.NaisKafkaEnv
import no.nav.kafka.config.createKafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import kotlin.String

class KontorProducer(
    config: NaisKafkaEnv,
    topics: Topics,
    val kontorNavnProvider: (kontorId: KontorId) -> KontorNavn,
    val aktorIdProvider: (ident: IdentSomKanLagres) -> AktorId
) {

    val kontorTopic = topics.ut.arbeidsoppfolgingskontortilordninger
    val producer = createKafkaProducer(config)

    fun publiserEndringPåKontor(event: KontorSattAvVeileder) {
        val key = event.tilordning.fnr
        val value = event.toKontorTilordningMeldingDto(
            aktorIdProvider(event.tilordning.fnr),
            kontorNavnProvider(event.tilordning.kontorId)
        )
        val record = ProducerRecord(
            kontorTopic.name,
            key.value,
            Json.encodeToString(value)
        )
        producer.send(record)
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
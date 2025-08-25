import io.ktor.server.application.ApplicationEnvironment
import no.nav.kafka.processor.AvroSerdes
import no.nav.person.pdl.aktor.v2.Aktor
import no.nav.person.pdl.leesah.Personhendelse
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.kstream.Consumed
import kotlin.String

class Topic<KIn, VIn>(
    val name: String,
    val keySerde: Serde<KIn>,
    val valSerde: Serde<VIn>,
) {
    fun consumedWith(): Consumed<KIn, VIn>   {
        return Consumed.with(keySerde, valSerde)
    }
}

class Topics(
    val inn: Inn,
    val ut: Ut,
) {
    class Inn(
        val endringPaOppfolgingsbruker: Topic<String, String>,
        val sisteOppfolgingsperiodeV1: Topic<String, String>,
        val pdlLeesah: Topic<String, Personhendelse>,
        val skjerming: Topic<String, String>,
        val aktorV2: Topic<String, Aktor>,
    )
    class Ut(
        val arbeidsoppfolgingskontortilordninger: String,
    )
}

private fun getInnTopicsWithSerde(
    endringPaOppfolgingsbrukerName: String,
    sisteOppfolgingsperiodeV1Name: String,
    pdlLeesahName: String,
    skjermingName: String,
    avroSerdes: AvroSerdes,
    aktorV2Name: String,
    ): Topics.Inn {
    return Topics.Inn(
        Topic(endringPaOppfolgingsbrukerName, Serdes.String(), Serdes.String()),
        Topic(sisteOppfolgingsperiodeV1Name, Serdes.String(), Serdes.String()),
        Topic(pdlLeesahName,  avroSerdes.leesahKeyAvroSerde, avroSerdes.leesahValueAvroSerde),
        Topic(skjermingName, Serdes.String(), Serdes.String()),
        Topic(aktorV2Name, avroSerdes.aktorV2KeyAvroSerde, avroSerdes.aktorV2ValueAvroSerde)
    )
}

fun ApplicationEnvironment.topics(): Topics {
    val avroSerdes = AvroSerdes(this.config)
    return Topics(
        getInnTopicsWithSerde(
            this.config.property("topics.inn.endringPaOppfolgingsbruker").getString(),
            this.config.property("topics.inn.sisteOppfolgingsperiodeV1").getString(),
            this.config.property("topics.inn.pdlLeesah").getString(),
            this.config.property("topics.inn.skjerming").getString(),
            avroSerdes,
            aktorV2Name = this.config.property("topics.inn.aktor-v2").getString()
        ),
        Topics.Ut(
            this.config.property("topics.ut.arbeidsoppfolgingskontortilordninger").getString()
        )
    )
}
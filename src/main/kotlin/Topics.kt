import io.ktor.server.application.ApplicationEnvironment
import no.nav.kafka.config.processorName

class Topics(
    val inn: Inn,
    val ut: Ut,
) {
    class Inn(
        val endringPaOppfolgingsbruker: String,
        val oppfolgingsperiodeV1: String,
        val pdlLeesah: String,
        val skjerming: String,
    ) {
        fun processorNames(): List<String> {
            return listOf(
                processorName(endringPaOppfolgingsbruker),
                processorName(oppfolgingsperiodeV1),
                processorName(pdlLeesah),
                processorName(skjerming),
            )
        }
    }
    class Ut(
        val endringPaArbeidsoppfolgingskontor: String,
    )
}

fun ApplicationEnvironment.topics(): Topics {
    return Topics(
        Topics.Inn(
        this.config.property("topics.inn.endringPaOppfolgingsbruker").getString(),
        this.config.property("topics.inn.oppfolgingsperiodeV1").getString(),
        this.config.property("topics.inn.pdlLeesah").getString(),
        this.config.property("topics.inn.skjerming").getString(),
            ),
        Topics.Ut(
            this.config.property("topics.inn.endringPaArbeidsoppfolgingskontor").getString()
        )
    )
}
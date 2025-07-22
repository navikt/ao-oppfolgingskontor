import io.ktor.server.application.ApplicationEnvironment

class Topics(
    val inn: Inn,
    val ut: Ut,
) {
    class Inn(
        val endringPaOppfolgingsbruker: String,
        val oppfolgingsperiodeV1: String,
        val pdlLeesah: String,
        val skjerming: String,
    )
    class Ut(
        val arbeidsoppfolgingskontortilordninger: String,
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
            this.config.property("topics.ut.arbeidsoppfolgingskontortilordninger").getString()
        )
    )
}
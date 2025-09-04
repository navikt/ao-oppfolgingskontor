package kafka.consumers.oppfolgingsHendelser

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import no.nav.utils.ZonedDateTimeSerializer
import java.time.ZonedDateTime

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("hendelseType")
@Serializable
sealed class OppfolgingsHendelseDto(
    val hendelseType: HendelseType,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val producerTimestamp: ZonedDateTime = ZonedDateTime.now(),
    val producerAppName: HendelseProducerAppName = HendelseProducerAppName.AO_OPPFOLGINGSKONTOR,
)

enum class HendelseProducerAppName {
    VEILARBOPPFOLGING,
    AO_OPPFOLGINGSKONTOR
}

enum class HendelseType {
    OPPFOLGING_STARTET,
    OPPFOLGING_AVSLUTTET,
}


val module = SerializersModule {
    polymorphic(OppfolgingsHendelseDto::class) {
        subclass(OppfolgingStartetHendelseDto::class)
        subclass(OppfolgingsAvsluttetHendelseDto::class)
    }
}

val oppfolgingsHendelseJson = Json {
    serializersModule = module
    prettyPrint = true
    ignoreUnknownKeys = false
    encodeDefaults = true
}
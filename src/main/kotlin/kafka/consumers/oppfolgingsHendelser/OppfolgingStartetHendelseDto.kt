package kafka.consumers.oppfolgingsHendelser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.utils.ZonedDateTimeSerializer
import java.time.ZonedDateTime

@SerialName("OPPFOLGING_STARTET")
@Serializable
class OppfolgingStartetHendelseDto(
    val oppfolgingsPeriodeId: String,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val startetTidspunkt: ZonedDateTime,
    val startetAv: String,
    val startetAvType: StartetAvType,
    val startetBegrunnelse: OppfolgingStartBegrunnelse,
    val arenaKontor: String?,
    val foretrukketArbeidsoppfolgingskontor: String?,
    val fnr: String,
): OppfolgingsHendelseDto(HendelseType.OPPFOLGING_STARTET)

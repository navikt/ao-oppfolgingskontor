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
    val startetAvType: String,
    val startetBegrunnelse: String,
    val arenaKontor: String?,
    val arbeidsoppfolgingsKontorSattAvVeileder: String?,
    val fnr: String,
): OppfolgingsHendelseDto(HendelseType.OPPFOLGING_STARTET)
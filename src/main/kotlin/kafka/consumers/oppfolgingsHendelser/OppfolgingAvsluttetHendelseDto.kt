package kafka.consumers.oppfolgingsHendelser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.utils.ZonedDateTimeSerializer
import java.time.ZonedDateTime

@SerialName("OPPFOLGING_AVSLUTTET")
@Serializable
class OppfolgingsAvsluttetHendelseDto(
    val fnr: String,
    val oppfolgingsPeriodeId: String,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val startetTidspunkt: ZonedDateTime,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val avsluttetTidspunkt: ZonedDateTime,
    val avsluttetAv: String,
    val avsluttetAvType: AvsluttetAvType,
    val avregistreringsType: AvregistreringsType
): OppfolgingsHendelseDto(HendelseType.OPPFOLGING_AVSLUTTET)

enum class AvregistreringsType {
    UtmeldtEtter28Dager,
    ManuellAvregistrering,
    ArenaIservKanIkkeReaktiveres,
    AdminAvregistrering,
}
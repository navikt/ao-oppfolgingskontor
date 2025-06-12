package no.nav.http.client.arbeidssogerregisteret
import kotlinx.serialization.Serializable

/**
 * Request body for å hente aggregerte arbeidssøkerperioder.
 */
@Serializable
data class ArbeidssoekerperiodeRequest(
    val identitetsnummer: String
)

@Serializable
data class ArbeidssoekerperiodeAggregertResponse(
    val periodeId: String,
    val fom: String,
    val tom: String? = null,
    val status: Status,
    val registrertDato: String,
    val antallDager: Int,
    val stilling: String?,
    val profilering: Profilering?,
    val arbeidsforhold: List<Arbeidsforhold> = emptyList(),
    val fraværsperioder: List<Fravaersperiode> = emptyList(),
    val tiltak: List<Tiltak> = emptyList(),
    val kompetanse: List<Kompetanse> = emptyList(),
    val registrering: Registrering?
)

@Serializable
data class Profilering(
    val fravaer: String? = null,
    val yrkesrettetArbeid: Boolean = false,
    val behovForVeiledning: Boolean = false,
    val oppfølgingsplan: Boolean = false,
    val andreOpplysninger: String? = null,
    val profilertTil: ProfileringEnum
)

@Serializable
data class Tiltak(
    val tiltakId: String,
    val navn: String,
    val startDato: String,
    val sluttDato: String? = null,
    val status: Status
)

@Serializable
data class Kompetanse(
    val navn: String,
    val beskrivelse: String? = null,
    val sertifisert: Boolean = false
)

@Serializable
data class Registrering(
    val registrertAv: String,
    val registrertTidspunkt: String
)

@Serializable
enum class Status {
    AKTIV,
    AVSLUTTET,
    PENDING
}

@Serializable
enum class ProfileringEnum {
    UKJENT_VERDI, UDEFINERT, ANTATT_GODE_MULIGHETER, ANTATT_BEHOV_FOR_VEILEDNING, OPPGITT_HINDRINGER
}

@Serializable
data class Arbeidsforhold(
    val arbeidsgiverId: String?,
    val arbeidsgiverNavn: String?,
    val stillingsprosent: Double?,
    val startdato: String,
    val sluttdato: String? = null
)

@Serializable
data class Fravaersperiode(
    val fom: String,
    val tom: String,
    val årsak: String? = null
)

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
class MetadataResponse(
    val tidspunkt: String,
    val utfoertAv: BrukerResponse? = null,
    val kilde: String? = null,
    val aarsak: String? = null,
    val tidspunktFraKilde: TidspunktFraKildeResponse? = null,
)

@Serializable
data class TidspunktFraKildeResponse(
    val tidspunkt: String,
    val avviksType: AvviksTypeResponse
)

enum class AvviksTypeResponse {
    UKJENT_VERDI,
    FORSINKELSE,
    RETTING,
    SLETTET,
    TIDSPUNKT_KORRIGERT
}

@Serializable
data class BrukerResponse(
    val type: BrukerType,
    val id: String
)

enum class BrukerType {
    UKJENT_VERDI,
    UDEFINERT,
    VEILEDER,
    SYSTEM,
    SLUTTBRUKER
}

enum class Bekreftelsesloesning {
    UKJENT_VERDI, ARBEIDSSOEKERREGISTERET, FRISKMELDT_TIL_ARBEIDSFORMIDLING, DAGPENGER
}

@Serializable
data class BekreftelseSvarResponse(
    val sendtInnAv:	MetadataResponse,
    val gjelderFra: String,
    val gjelderTil:	String,
    val harJobbetIDennePerioden: Boolean,
    val vilFortsetteSomArbeidssoeker: Boolean
)

@Serializable
data class BekreftelseResponse(
    val periodeId: String,
    val bekreftelsesloesning:	List<Bekreftelsesloesning>,
    val svar: BekreftelseSvarResponse,
)

@Serializable
data class OpplysningerOmArbeidssoekerAggregertResponse(
    val opplysningerOmArbeidssoekerId: String,
    val periodeId: String,
    val sendtInnAv: MetadataResponse,
    val jobbsituasjon: List<BeskrivelseMedDetaljerResponse>,
    val utdanning: UtdanningResponse? = null,
    val helse: HelseResponse? = null,
    val annet: AnnetResponse? = null,
    val profilering: ProfileringAggregertResponse? = null
)

@Serializable
data class BeskrivelseMedDetaljerResponse(
    val beskrivelse: JobbSituasjonBeskrivelse,
    val detaljer: Map<String, String>
)

@Serializable
data class ProfileringAggregertResponse(
    val profileringId: String,
    val periodeId: String,
    val opplysningerOmArbeidssoekerId: String,
    val sendtInnAv: MetadataResponse,
    val profilertTil: ProfileringsResultat,
    val jobbetSammenhengendeSeksAvTolvSisteManeder: Boolean? = null,
    val alder: Int? = null,
    val egenvurdering: List<EgenvurderingResponse>? = null
)

@Serializable
data class EgenvurderingResponse(
    val profileringId: String,
    val periodeId: String,
    val egenvurdering: Egenvurdering,
    val sendtInnAv: MetadataResponse
)

enum class Egenvurdering {
    ANTATT_GODE_MULIGHETER,
    ANTATT_BEHOV_FOR_VEILEDNING
}

@Serializable
enum class ProfileringsResultat {
    UKJENT_VERDI,
    UDEFINERT,
    ANTATT_GODE_MULIGHETER,
    ANTATT_BEHOV_FOR_VEILEDNING,
    OPPGITT_HINDRINGER
}


@Serializable
data class HelseResponse(
    val helsetilstandHindrerArbeid: JaNeiVetIkke
)

@Serializable
data class AnnetResponse(
    val andreForholdHindrerArbeid: JaNeiVetIkke
)

@Serializable
enum class JaNeiVetIkke {
    JA,
    NEI,
    VET_IKKE
}

@Serializable
data class UtdanningResponse(
    val nus: String,
    val bestaatt: JaNeiVetIkke? = null,
    val godkjent: JaNeiVetIkke? = null
)

enum class JobbSituasjonBeskrivelse {
    UKJENT_VERDI,
    UDEFINERT,
    HAR_SAGT_OPP,
    HAR_BLITT_SAGT_OPP,
    ER_PERMITTERT,
    ALDRI_HATT_JOBB,
    IKKE_VAERT_I_JOBB_SISTE_2_AAR,
    AKKURAT_FULLFORT_UTDANNING,
    VIL_BYTTE_JOBB,
    USIKKER_JOBBSITUASJON,
    MIDLERTIDIG_JOBB,
    DELTIDSJOBB_VIL_MER,
    NY_JOBB,
    KONKURS,
    ANNET
}

@Serializable
data class ArbeidssoekerperiodeAggregertResponse(
    val periodeId: String,
    val startet: MetadataResponse,
    val avsluttet: MetadataResponse? = null,
    val opplysningerOmArbeidssoeker: List<OpplysningerOmArbeidssoekerAggregertResponse>,
    val bekreftelser: List<BekreftelseResponse> = emptyList(),
)

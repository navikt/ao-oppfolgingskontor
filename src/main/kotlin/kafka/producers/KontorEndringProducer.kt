package kafka.producers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.KontorSattAvVeileder
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import services.KontorSomSkalRepubliseres
import kotlin.String

class KontorEndringProducer(
    val producer: Producer<String, String?>,
    val kontorTopicNavn: String,
    val kontorNavnProvider: suspend (kontorId: KontorId) -> KontorNavn,
    val aktorIdProvider: suspend (ident: IdentSomKanLagres) -> AktorId?,
) {

    suspend fun publiserEndringPåKontor(event: KontorSattAvVeileder): Result<Unit> {
        return runCatching {
            val value = event.toKontorTilordningMeldingDto(
                aktorIdProvider(event.tilordning.fnr)
                    ?: throw RuntimeException("Finner ikke aktorId for ident ${event.tilordning.fnr.value}"),
                kontorNavnProvider(event.tilordning.kontorId)
            )
            publiserEndringPåKontor(value)
        }
    }

    suspend fun publiserEndringPåKontor(event: OppfolgingEndretTilordningMelding): Result<Unit> {
        return runCatching {
            val ident = Ident.validateOrThrow(event.ident, Ident.HistoriskStatus.UKJENT) as? IdentSomKanLagres
                ?: throw IllegalArgumentException("Kan ikke publisere kontor-endring på aktørid, trenger annen ident")
            publiserEndringPåKontor(
                KontorTilordningMeldingDto(
                    kontorId = event.kontorId,
                    kontorNavn = kontorNavnProvider(KontorId(event.kontorId)).navn,
                    oppfolgingsperiodeId = event.oppfolgingsperiodeId,
                    aktorId = aktorIdProvider(ident)?.value
                        ?: throw RuntimeException("Finner ikke aktorId for ident ${event.ident}"),
                    ident = event.ident,
                    tilordningstype = Tilordningstype.fraKontorEndringsType(event.kontorEndringsType)
                )
            )
        }
    }

    fun republiserKontor(kontorSomSkalRepubliseres: KontorSomSkalRepubliseres): Result<Unit> {
        return runCatching {
            val value = kontorSomSkalRepubliseres.toKontorTilordningMeldingDto()
            publiserEndringPåKontor(value)
        }
    }

    private fun publiserEndringPåKontor(event: KontorTilordningMeldingDto): Result<Unit> {
        return runCatching {
            val record = ProducerRecord(
                kontorTopicNavn,
                event.oppfolgingsperiodeId,
                Json.encodeToString(event)
            )
            producer.send(record)
        }
    }

    fun publiserTombstone(oppfolgingPeriodeId: OppfolgingsperiodeId): Result<Unit> {
        return runCatching {
            val record: ProducerRecord<String,String?> = ProducerRecord(
                kontorTopicNavn,
                oppfolgingPeriodeId.value.toString(),
                null
            )
            producer.send(record)
        }
    }
}

fun AOKontorEndret.toKontorTilordningMeldingDto(
    aktorId: AktorId,
    kontorNavn: KontorNavn
): KontorTilordningMeldingDto {
    return KontorTilordningMeldingDto(
        kontorId = this.tilordning.kontorId.id,
        kontorNavn = kontorNavn.navn,
        oppfolgingsperiodeId = this.tilordning.oppfolgingsperiodeId.value.toString(),
        aktorId = aktorId.value,
        ident = this.tilordning.fnr.value,
        tilordningstype = Tilordningstype.fraKontorEndringsType(this.kontorEndringsType())
    )
}
fun KontorSomSkalRepubliseres.toKontorTilordningMeldingDto(): KontorTilordningMeldingDto {
    return KontorTilordningMeldingDto(
        kontorId = this.kontorId.id,
        kontorNavn = this.kontorNavn.navn,
        oppfolgingsperiodeId = this.oppfolgingsperiodeId.value.toString(),
        aktorId = this.aktorId.value,
        ident = this.ident.value,
        tilordningstype = Tilordningstype.fraKontorEndringsType(this.kontorEndringsType)
    )
}


fun AOKontorEndret.toKontorTilordningMelding(): OppfolgingEndretTilordningMelding {
    return OppfolgingEndretTilordningMelding(
        kontorId = this.tilordning.kontorId.id,
        oppfolgingsperiodeId = this.tilordning.oppfolgingsperiodeId.value.toString(),
        ident = this.tilordning.fnr.value,
        kontorEndringsType = this.kontorEndringsType()
    )
}

@Serializable
data class KontorTilordningMeldingDto(
    val kontorId: String,
    val kontorNavn: String,
    val oppfolgingsperiodeId: String,
    val aktorId: String,
    val ident: String,
    val tilordningstype: Tilordningstype,
)

enum class Tilordningstype {
    KONTOR_VED_OPPFOLGINGSPERIODE_START,
    ENDRET_KONTOR;

    companion object {
        fun fraKontorEndringsType(kontorEndringsType: KontorEndringsType): Tilordningstype {
            return when (kontorEndringsType) {
                KontorEndringsType.AutomatiskRutetTilNOE,
                KontorEndringsType.AutomatiskNorgRuting,
                KontorEndringsType.AutomatiskNorgRutingFallback,
                KontorEndringsType.AutomatiskRutetTilNavItManglerGt,
                KontorEndringsType.AutomatiskRutetTilNavItGtErLand,
                KontorEndringsType.AutomatiskRutetTilNavItIngenKontorFunnetForGt -> KONTOR_VED_OPPFOLGINGSPERIODE_START

                KontorEndringsType.FikkAddressebeskyttelse,
                KontorEndringsType.AddressebeskyttelseMistet,
                KontorEndringsType.FikkSkjerming,
                KontorEndringsType.MistetSkjerming,
                KontorEndringsType.FlyttetAvVeileder -> ENDRET_KONTOR

                /* Endringer som bare skal skje på GT-kontor eller Arena-kontor */
                KontorEndringsType.GTKontorVedOppfolgingStart,
                KontorEndringsType.EndretBostedsadresse,
                KontorEndringsType.EndretIArena,
                KontorEndringsType.ArenaKontorVedOppfolgingsStart,
                KontorEndringsType.TidligArenaKontorVedOppfolgingStart,
                KontorEndringsType.ArenaKontorVedOppfolgingStartMedEtterslep,
                KontorEndringsType.MIGRERING,
                KontorEndringsType.PATCH,
                KontorEndringsType.ArenaMigrering -> {
                    throw RuntimeException("Vi skal ikke publisere kontorendringer på kontor-endring av type $kontorEndringsType")
                }
            }
        }
    }
}

/**
 * Same as KontorTilordningMeldingDto but without AktorId and kontorNavn.
 * Needed to avoid fetching aktorId and kontorNavn in KontortilordningsProcessor
 * but still have a serializable data-transfer-object to pass it to the next processing step
 * */
@Serializable
data class OppfolgingEndretTilordningMelding(
    val kontorId: String,
    val oppfolgingsperiodeId: String,
    val ident: String,
    val kontorEndringsType: KontorEndringsType
)
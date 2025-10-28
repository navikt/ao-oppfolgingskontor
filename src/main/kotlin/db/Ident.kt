package no.nav.db

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

sealed class MaybeValidIdent(val value: String)
class ValidIdent(val ident: Ident): MaybeValidIdent(ident.value)
class InvalidIdent(value: String, val message: String = "Ugyldig ident") : MaybeValidIdent(value)

@Serializable(with = ValueSerializer::class)
sealed class Ident {
    abstract val value: String
    abstract val historisk: HistoriskStatus

    companion object {
        val isDev = System.getenv("NAIS_CLUSTER_NAME")?.contains("dev") ?: false

        fun validate(value: String, historisk: HistoriskStatus): MaybeValidIdent {
            if (value.isBlank()) return InvalidIdent(value, "Ident cannot be blank")
            if (value.any { !it.isDigit() }) return InvalidIdent(value,"Ident must contain only digits")
            val length = value.length
            if (length != 11 && length != 13) return InvalidIdent(value,"Ident must have length 11 or 13 but had $length")

            val digitNumber3and4 by lazy { value.substring(2,4).toInt() }
            val firstDigit by lazy { value[0].digitToInt() }
            val lengthIs13 by lazy { length == 13 }
            val monthIsValidMonth by lazy { digitNumber3and4 in 1..12 }
            val monthIsTenorMonth by lazy { digitNumber3and4 in 81..92 }
            val monthIsDollyMonth by lazy { digitNumber3and4 in 41..80 }
            val monthIsBostMonth by lazy { digitNumber3and4 in 61..72 }
            val lengthIs11 by lazy { length == 11 }
            val isValidDate by lazy { value.take(2).toInt() in 1..31 }

            return when {
                lengthIs13 -> AktorId(value, historisk)
                firstDigit in gyldigeDnrStart && (monthIsValidMonth || monthIsTenorMonth || monthIsDollyMonth) -> Dnr(value, historisk)
                digitNumber3and4 in 21..32 -> Npid(value, historisk) // NPID er måned + 20
                lengthIs11 && monthIsValidMonth && isValidDate -> Fnr(value, historisk)
                isDev && lengthIs11 && isValidDate && (monthIsTenorMonth || monthIsDollyMonth || monthIsBostMonth) -> Fnr(value, historisk)
                else -> return InvalidIdent(value)
            }.let { ValidIdent(it) }
        }

        fun validateOrThrow(value: String, historisk: HistoriskStatus): Ident {
            return when (val res = validate(value, historisk)) {
                is InvalidIdent -> throw Exception(res.message)
                is ValidIdent -> res.ident
            }
        }

        fun validateIdentSomKanLagres(value: String, historisk: HistoriskStatus): IdentSomKanLagres {
            val ident = validateOrThrow(value, historisk)
            return when (ident) {
                is AktorId -> throw Exception("Forventet IdentSomKanLagres, men fikk AktorId")
                is Dnr, is Fnr, is Npid -> ident
            }
        }
    }


    override fun equals(other: Any?): Boolean {
        if (other !is Ident) return false
        return other.value == value
    }

    override fun toString() = value

    override fun hashCode(): Int {
        return value.hashCode()
    }

    enum class HistoriskStatus {
        HISTORISK,
        AKTIV,
        UKJENT
    }
}

/* Identer som kan lagres data på, feks oppfolgingsperiode, kontor etc.
* Alle identer utenom AKtorId støttes */
@Serializable(with = IdentSomKanLagresSerializer::class)
sealed class IdentSomKanLagres(): Ident()

/*
* Kan innholde fnr, dnr eller npid
* */
class Fnr(override val value: String, override val historisk: HistoriskStatus): IdentSomKanLagres() {
    init {
        require(value.isNotBlank()) { "Fnr cannot be blank" }
        require(value.length == 11) { "Fnr $value must be 11 characters long but was ${value.length}" }
        require(value.all { it.isDigit() }) { "Fnr must contain only digits" }
    }

    override fun toString(): String = value
}

val gyldigeDnrStart = listOf(4,5,6,7)
class Dnr(override val value: String, override val historisk: HistoriskStatus): IdentSomKanLagres() {
    init {
        require(value.isNotBlank()) { "Dnr cannot be blank" }
        require(value.length == 11) { "Dnr $value must be 11 characters long but was ${value.length}" }
        require(value.all { it.isDigit() }) { "Dnr must contain only digits" }
        require(gyldigeDnrStart.contains(value[0].digitToInt()) ) { "Dnr must start with 4, 5, 6, or 7" }
    }

    override fun toString(): String = value
}

class Npid(override val value: String, override val historisk: HistoriskStatus): IdentSomKanLagres() {
    init {
        require(value.isNotBlank()) { "Npid cannot be blank" }
        require(value.length == 11) { "Npid must be 11 characters long but was ${value.length}" }
        require(value.all { it.isDigit() }) { "Npid must contain only digits" }
    }

    override fun toString(): String = value
}

class AktorId(override val value: String, override val historisk: HistoriskStatus): Ident() {
    init {
        require(value.isNotBlank()) { "AktorId cannot be blank" }
        require(value.length == 13) { "AktorId must be 13 characters long but was ${value.length}" }
        require(value.all { it.isDigit() }) { "AktorId must contain only digits" }
    }
}

object ValueSerializer : KSerializer<Ident> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Ident", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Ident) = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder) = Ident.validateOrThrow(decoder.decodeString(), Ident.HistoriskStatus.UKJENT)
}

object IdentSomKanLagresSerializer : KSerializer<IdentSomKanLagres> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IdentSomKanLagres", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: IdentSomKanLagres) =
        ValueSerializer.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): IdentSomKanLagres =
        ValueSerializer.deserialize(decoder) as? IdentSomKanLagres
            ?: throw Exception("Deserialisert ident er ikke en ident som kan lagres")
}

fun List<Ident>.finnForetrukketIdent(): IdentSomKanLagres? {
    return this
        .filter { it.historisk == Ident.HistoriskStatus.AKTIV }
        .mapNotNull { ident -> ident as? IdentSomKanLagres }
        .minByOrNull {
            when (it) {
                is Fnr -> 1
                is Dnr -> 2
                is Npid -> 3
            }
        }
}

fun List<Ident>.finnForetrukketIdentRelaxed(): Ident? {
    return this
        .minByOrNull {
            when (it) {
                is Fnr -> 1
                is Dnr -> 2
                is Npid -> 3
                is AktorId -> 4
            }
        }
}
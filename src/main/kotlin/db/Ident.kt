package no.nav.db

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ValueSerializer::class)
sealed class Ident {
    abstract val value: String

    companion object {
        fun of(value: String): Ident {
            require(value.isNotBlank())
            require(value.all { it.isDigit() }) { "Ident must contain only digits" }

            val digitNumber3and4 by lazy { value.substring(2,4).toInt() }
            val firstDigit by lazy { value[0].digitToInt() }
            val lengthIs13 by lazy { value.length == 13 }
            val monthIsValidMonth by lazy { digitNumber3and4 in 1..12 }
            val monthIsTenorMonth by lazy { digitNumber3and4 in 81..92 }
            val monthIsDollyMonth by lazy { digitNumber3and4 in 41..62 }
            val lengthIs11 by lazy { value.length == 11 }
            val isValidDate by lazy { value.substring(0, 2).toInt() in 1..31 }

            return when {
                lengthIs13 -> AktorId(value)
                firstDigit in gyldigeDnrStart && (monthIsValidMonth || monthIsTenorMonth || monthIsDollyMonth) -> Dnr(value)
                digitNumber3and4 in 21..32 -> Npid(value) // NPID er måned + 20
                lengthIs11 && isValidDate && (monthIsValidMonth || monthIsTenorMonth || monthIsDollyMonth) -> Fnr(value)
                else -> { throw Exception("Ugyldig Ident: $value")
                }
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
}

/*
* Kan innholde fnr, dnr eller npid
* */
class Fnr(override val value: String): Ident() {
    init {
        require(value.isNotBlank()) { "Fnr cannot be blank" }
        require(value.length == 11) { "Fnr $value must be 11 characters long but was ${value.length}" }
        require(value.all { it.isDigit() }) { "Fnr must contain only digits" }
    }

    override fun toString(): String = value
}

val gyldigeDnrStart = listOf(4,5,6,7)
class Dnr(override val value: String): Ident() {
    init {
        require(value.isNotBlank()) { "Dnr cannot be blank" }
        require(value.length == 11) { "Dnr $value must be 11 characters long but was ${value.length}" }
        require(value.all { it.isDigit() }) { "Dnr must contain only digits" }
        require(gyldigeDnrStart.contains(value[0].digitToInt()) ) { "Dnr must start with 4, 5, 6, or 7" }
    }

    override fun toString(): String = value
}

class Npid(override val value: String): Ident() {
    init {
        require(value.isNotBlank()) { "Npid cannot be blank" }
        require(value.length == 11) { "Npid must be 11 characters long but was ${value.length}" }
        require(value.all { it.isDigit() }) { "Npid must contain only digits" }
    }

    override fun toString(): String = value
}

class AktorId(override val value: String): Ident() {
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
    override fun deserialize(decoder: Decoder) = Ident.of(decoder.decodeString())
}
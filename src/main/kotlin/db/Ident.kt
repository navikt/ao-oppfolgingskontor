package no.nav.db

import kotlinx.serialization.Serializable

@Serializable
sealed class Ident {
    abstract val value: String

    companion object {
        fun of(value: String): Ident {
            require(value.isNotBlank())
            require(value.all { it.isDigit() }) { "Ident must contain only digits" }

            val digitNumber3and4 by lazy { value.substring(2,3).toInt() }
            val firstDigit by lazy { value[0].digitToInt() }
            val lengthIs14 by lazy { value.length == 14 }

            return when {
                lengthIs14 -> AktorId(value)
                firstDigit in gyldigeDnrStart -> Dnr(value)
                digitNumber3and4 in 21..32 -> Npid(value)
                else -> Fnr(value)
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
        require(gyldigeDnrStart.contains(value[0].digitToInt()) ) { "Dnr must contain only digits" }
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
        require(value.length == 14) { "AktorId must be 14 characters long but was ${value.length}" }
        require(value.all { it.isDigit() }) { "AktorId must contain only digits" }
    }
}
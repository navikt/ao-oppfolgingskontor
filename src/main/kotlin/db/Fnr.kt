package no.nav.db

import kotlinx.serialization.Serializable

@Serializable
sealed class Ident {
    abstract val value: String

    companion object {
        fun of(value: String): Ident {
            require(value.isNotBlank())
            require(value.all { it.isDigit() }) { "Ident must contain only digits" }
            val digitNumber3and4 = value.substring(2,3).toInt()
            return if (digitNumber3and4 in 21..32) {
                Npid(value)
            } else {
                Fnr(value)
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

class Npid(override val value: String): Ident() {
    init {
        require(value.isNotBlank()) { "Npid cannot be blank" }
        require(value.length == 11) { "Npi $value must be 11 characters long" }
        require(value.all { it.isDigit() }) { "Npid must contain only digits" }
    }

    override fun toString(): String = value
}

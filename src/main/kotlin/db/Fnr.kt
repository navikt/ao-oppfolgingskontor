package no.nav.db

/*
* Kan innholde fnr, dnr eller npid
* */
@JvmInline
value class Fnr(val value: String) {
    init {
        require(value.isNotBlank()) { "Fnr cannot be blank" }
        require(value.length == 11) { "Fnr $value must be 11 characters long" }
        require(value.all { it.isDigit() }) { "Fnr must contain only digits" }
    }

    override fun toString(): String = value
}

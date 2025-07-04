package no.nav.domain

@JvmInline
value class HarSkjerming(val value: Boolean)
@JvmInline
value class HarStrengtFortroligAdresse(val value: Boolean)

data class Sensitivitet(
    val skjermet: HarSkjerming,
    val strengtFortroligAdresse: HarStrengtFortroligAdresse,
) {
    fun erSensitiv(): Boolean {
        return skjermet.value || strengtFortroligAdresse.value
    }
}

package no.nav.domain

enum class KontorEndringsType {
    AutomatiskRutetTilNOE,
    FlyttetAvVeileder,
    BleSkjermet,
    FikkAddressebeskyttelse,
}

fun KontorEndringsType.getKilde(): KontorKilde {
    return when (this) {
        KontorEndringsType.AutomatiskRutetTilNOE -> KontorKilde.ARBEIDSOPPFOLGING
        KontorEndringsType.FlyttetAvVeileder -> KontorKilde.ARBEIDSOPPFOLGING
        KontorEndringsType.BleSkjermet -> KontorKilde.GEOGRAFISK_TILKNYTNING
        KontorEndringsType.FikkAddressebeskyttelse -> KontorKilde.GEOGRAFISK_TILKNYTNING
    }
}
package no.nav.domain

enum class KontorEndringsType {
    AutomatiskRutetTilNOE,
    AutomatiskRutetTilLokalkontor,
    FlyttetAvVeileder,
    FikkSkjerming,
    MistetSkjerming,
    FikkAddressebeskyttelse,
    AddressebeskyttelseMistet,
    EndretIArena,
    EndretBostedsadresse,
}

fun KontorEndringsType.getKilde(): KontorKilde {
    return when (this) {
        KontorEndringsType.AutomatiskRutetTilNOE -> KontorKilde.ARBEIDSOPPFOLGING
        KontorEndringsType.FlyttetAvVeileder -> KontorKilde.ARBEIDSOPPFOLGING
        KontorEndringsType.FikkSkjerming -> KontorKilde.GEOGRAFISK_TILKNYTNING
        KontorEndringsType.FikkAddressebeskyttelse -> KontorKilde.GEOGRAFISK_TILKNYTNING
        KontorEndringsType.AutomatiskRutetTilLokalkontor -> KontorKilde.ARBEIDSOPPFOLGING
        KontorEndringsType.EndretIArena -> KontorKilde.ARENA
        KontorEndringsType.EndretBostedsadresse -> KontorKilde.GEOGRAFISK_TILKNYTNING
        KontorEndringsType.MistetSkjerming -> KontorKilde.GEOGRAFISK_TILKNYTNING
        KontorEndringsType.AddressebeskyttelseMistet -> KontorKilde.GEOGRAFISK_TILKNYTNING
    }
}

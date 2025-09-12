package no.nav.domain

enum class KontorEndringsType {
    AutomatiskRutetTilNOE,
    AutomatiskRutetTilLokalkontor,
    AutomatiskRutetTilLokalkontorFallback,
    AutomatiskRutetTilNavItManglerGt,
    FlyttetAvVeileder,
    FikkSkjerming,
    MistetSkjerming,
    FikkAddressebeskyttelse,
    AddressebeskyttelseMistet,
    EndretIArena,
    ArenaKontorVedOppfolgingsStart,
    ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart,
    EndretBostedsadresse,
    ArenaMigrering
}
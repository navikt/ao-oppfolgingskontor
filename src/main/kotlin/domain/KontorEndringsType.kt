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
    @Deprecated("Alle kontor bør taes fra topic, ikke fra veilarboppfolging")
    ArenaKontorVedOppfolgingsStart,
    TidligArenaKontorVedOppfolgingStart,
    ArenaKontorVedOppfolgingStartMedEtterslep,
    EndretBostedsadresse,
    GTKontorVedOppfolgingStart,
    MIGRERING,
    ArenaMigrering
}
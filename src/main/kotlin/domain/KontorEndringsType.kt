package no.nav.domain

enum class KontorEndringsType {
    AutomatiskRutetTilNOE,
    AutomatiskNorgRuting, // navkontor/{{geografisk-tilhørighet}}
    AutomatiskNorgRutingFallback, // /arbeidsfordeling/bestmatch
    AutomatiskRutetTilNavItManglerGt,
    AutomatiskRutetTilNavItGtErLand,
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
    ArenaMigrering,
}
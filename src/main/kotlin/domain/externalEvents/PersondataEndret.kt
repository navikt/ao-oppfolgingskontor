package no.nav.domain.externalEvents

import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering

sealed class PersondataEndret(val ident: IdentSomKanLagres)
class BostedsadresseEndret(ident: IdentSomKanLagres): PersondataEndret(ident)
class AdressebeskyttelseEndret(ident: IdentSomKanLagres, val gradering: Gradering): PersondataEndret(ident)
class IrrelevantHendelse(ident: IdentSomKanLagres, val opplysningstype: String): PersondataEndret(ident)

fun AdressebeskyttelseEndret.erGradert(): Boolean {
    return this.gradering == Gradering.STRENGT_FORTROLIG
            || this.gradering == Gradering.FORTROLIG
            || this.gradering == Gradering.STRENGT_FORTROLIG_UTLAND
}

fun AdressebeskyttelseEndret.erStrengtFortrolig(): HarStrengtFortroligAdresse {
    return HarStrengtFortroligAdresse(
        this.gradering == Gradering.STRENGT_FORTROLIG || this.gradering == Gradering.STRENGT_FORTROLIG_UTLAND
    )
}

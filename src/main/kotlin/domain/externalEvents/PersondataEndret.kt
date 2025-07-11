package no.nav.domain.externalEvents

import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering

sealed class PersondataEndret(val ident: Ident)
class BostedsadresseEndret(ident: Ident): PersondataEndret(ident)
class AdressebeskyttelseEndret(ident: Ident, val gradering: Gradering): PersondataEndret(ident)
class IrrelevantHendelse(ident: Ident, val opplysningstype: String): PersondataEndret(ident)

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

package no.nav.domain.externalEvents

import no.nav.db.Fnr
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering

sealed class PersondataEndret(val fnr: Fnr)
class BostedsadresseEndret(fnr: Fnr): PersondataEndret(fnr)
class AdressebeskyttelseEndret(fnr: Fnr, val gradering: Gradering): PersondataEndret(fnr)
class IrrelevantHendelse(fnr: Fnr, val opplysningstype: String): PersondataEndret(fnr)

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
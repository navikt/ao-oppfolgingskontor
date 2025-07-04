package no.nav.domain.externalEvents

import no.nav.db.Fnr
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering

sealed class PersondataEndret(val fnr: Fnr)
class BostedsadresseEndret(fnr: Fnr): PersondataEndret(fnr)
class AddressebeskyttelseEndret(fnr: Fnr, val gradering: Gradering): PersondataEndret(fnr)
class IrrelevantHendelse(fnr: Fnr, val opplysningstype: String): PersondataEndret(fnr)

fun AddressebeskyttelseEndret.erGradert(): Boolean {
    return this.gradering == Gradering.STRENGT_FORTROLIG
            || this.gradering == Gradering.FORTROLIG
            || this.gradering == Gradering.STRENGT_FORTROLIG_UTLAND
}

fun AddressebeskyttelseEndret.erStrengtFortrolig(): Boolean {
    return this.gradering == Gradering.STRENGT_FORTROLIG || this.gradering == Gradering.STRENGT_FORTROLIG_UTLAND
}
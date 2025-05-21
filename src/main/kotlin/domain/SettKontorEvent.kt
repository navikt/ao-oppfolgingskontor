package no.nav.domain

sealed class SettKontorEvent(
    val kontorTilhorighet: KontorTilhorighet
) {
}

class SettArbeidsoppfolgingskontorEvent(
    kontorTilhorighet: KontorTilhorighet

) : SettKontorEvent(kontorTilhorighet) {}

class SettArenakontorEvent(kontorTilhorighet: KontorTilhorighet) : SettKontorEvent(kontorTilhorighet)

class SettGeografiskTilknytningKontorEvent(kontorTilhorighet: KontorTilhorighet) : SettKontorEvent(kontorTilhorighet)
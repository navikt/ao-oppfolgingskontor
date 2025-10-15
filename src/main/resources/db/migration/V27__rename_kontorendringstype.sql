UPDATE kontorhistorikk
SET kontorendringstype = 'AutomatiskRutetTilLokalkontor'
WHERE kontorendringstype = 'AutomatiskNorgRuting';

UPDATE kontorhistorikk
SET kontorendringstype = 'AutomatiskRutetTilLokalkontorFallback'
WHERE kontorendringstype = 'AutomatiskNorgRutingFallback';

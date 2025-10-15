UPDATE kontorhistorikk
SET kontorendringstype = 'AutomatiskNorgRuting'
WHERE kontorendringstype = ' AutomatiskRutetTilLokalkontor';

UPDATE kontorhistorikk
SET kontorendringstype = 'AutomatiskNorgRutingFallback'
WHERE kontorendringstype = 'AutomatiskRutetTilLokalkontorFallback';

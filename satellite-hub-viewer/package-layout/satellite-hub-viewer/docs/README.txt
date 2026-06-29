Satellite Hub Viewer — InfiniteStack Agent App
==========================================

Visualização de áreas de interesse e do histórico temporal de imagens de
satélite (Sentinel-2) sincronizadas pelo sistema Satellite Sync. Mapa
interativo MapLibre, timeline por data de aquisição, thumbnails e metadados.

Acesso somente-leitura — não consulta a API STAC nem baixa imagens.

Instalação:
  plugininstall -path satellite-hub-viewer-1.0.0.ispz

Fonte de dados (PostgreSQL):
  satellite_hub_areas   (id, name, geom [PostGIS], ...)
  satellite_hub_images  (id, area_id, acquisition_date, cloud_cover, collection,
                         image_id, thumbnail_url, metadata, created_at, ...)

Banco suportado: postgres

Observações:
  - Os nomes de tabela podem ser sobrescritos por variáveis de ambiente
    SAT_AREAS_TABLE / SAT_IMAGES_TABLE.
  - A geometria das áreas é lida via ST_AsGeoJSON (requer extensão PostGIS).
  - Assets MapLibre são servidos localmente (sem CDN); os tiles de base
    (OpenStreetMap / Esri) são carregados pelo navegador em tempo de execução.

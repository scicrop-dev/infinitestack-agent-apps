package com.infinitestack.satellitehubviewer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.satellitehubviewer.dto.DashboardStats;
import com.infinitestack.satellitehubviewer.dto.ImageDetail;
import com.infinitestack.satellitehubviewer.dto.ImageSummary;
import com.infinitestack.satellitehubviewer.dto.PagedImages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acesso somente-leitura às tabelas do Satellite Sync.
 *
 * Todo o SQL fica concentrado aqui. Os nomes de tabela vêm de application.properties
 * (satellite.areas.table / satellite.images.table / satellite.images_areas.table) e os
 * nomes de coluna estão nas constantes COL_* abaixo — basta ajustá-los se a DDL real divergir.
 *
 * Schema (DDL real):
 *   gold.satellite_hub_areas(id PK, farm_name, block_name, parcel_name,
 *                            geometry[jsonb GeoJSON], hash[unique], created_at, last_synced)
 *   gold.satellite_hub_images(id PK, image_id[unique], collection_name, acquisition_date,
 *                             cloud_cover, thumbnail_url, visual_url, metadata[jsonb], created_at)
 *   gold.satellite_hub_images_areas(id PK, area_id -> areas.id, image_id -> images.id)
 *
 * O vínculo área↔imagem é N:N pela tabela de junção satellite_hub_images_areas
 * (area_id e image_id referenciam os PKs inteiros `id` de cada tabela).
 *
 * A geometria é GeoJSON em coluna JSONB — lida diretamente (sem PostGIS/ST_AsGeoJSON).
 */
@Service
public class SatelliteDataService {

    private static final Logger log = LoggerFactory.getLogger(SatelliteDataService.class);

    // ── Colunas de satellite_hub_areas ────────────────────────────────────────
    private static final String A_ID = "id";
    private static final String A_FARM = "farm_name";
    private static final String A_BLOCK = "block_name";
    private static final String A_PARCEL = "parcel_name";
    private static final String A_GEOM = "geometry";      // JSONB com GeoJSON
    private static final String A_LAST_SYNCED = "last_synced";

    // ── Colunas de satellite_hub_images ───────────────────────────────────────
    private static final String I_ID = "id";
    private static final String I_ACQUISITION_DATE = "acquisition_date";
    private static final String I_CLOUD_COVER = "cloud_cover";
    private static final String I_COLLECTION = "collection_name";
    private static final String I_IMAGE_ID = "image_id";
    private static final String I_THUMBNAIL_URL = "thumbnail_url";
    private static final String I_VISUAL_URL = "visual_url";
    private static final String I_METADATA = "metadata";

    // ── Colunas de satellite_hub_images_areas (junção N:N) ─────────────────────
    private static final String LA_AREA_ID = "area_id";   // -> areas.id
    private static final String LA_IMAGE_ID = "image_id";  // -> images.id

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${satellite.areas.table:gold.satellite_hub_areas}")
    private String areasTable;

    @Value("${satellite.images.table:gold.satellite_hub_images}")
    private String imagesTable;

    @Value("${satellite.images_areas.table:gold.satellite_hub_images_areas}")
    private String imagesAreasTable;

    public SatelliteDataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Health / diagnóstico ──────────────────────────────────────────────────

    public Map<String, Object> healthCheck() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("areasTable", areasTable);
        out.put("imagesTable", imagesTable);
        out.put("imagesAreasTable", imagesAreasTable);
        out.put("areasReadable", safeCount("SELECT COUNT(*) FROM " + areasTable) >= 0);
        out.put("imagesReadable", safeCount("SELECT COUNT(*) FROM " + imagesTable) >= 0);
        out.put("imagesAreasReadable", safeCount("SELECT COUNT(*) FROM " + imagesAreasTable) >= 0);
        return out;
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    public DashboardStats getDashboard() {
        long totalAreas = safeCount("SELECT COUNT(*) FROM " + areasTable);
        long totalImages = safeCount("SELECT COUNT(*) FROM " + imagesTable);
        String lastSync = safeScalarDate("SELECT MAX(" + A_LAST_SYNCED + ") FROM " + areasTable);
        String lastImage = safeScalarDate("SELECT MAX(" + I_ACQUISITION_DATE + ") FROM " + imagesTable);
        return new DashboardStats(Math.max(totalAreas, 0), Math.max(totalImages, 0), lastSync, lastImage);
    }

    // ── Áreas (GeoJSON FeatureCollection, carregadas uma vez) ──────────────────

    public Map<String, Object> getAreasFeatureCollection() {
        String sql = "SELECT a." + A_ID + " AS id, " +
                "a." + A_FARM + " AS farm_name, a." + A_BLOCK + " AS block_name, a." + A_PARCEL + " AS parcel_name, " +
                "a." + A_GEOM + "::text AS geojson, " +
                "COUNT(i." + I_ID + ") AS image_count, " +
                "MAX(i." + I_ACQUISITION_DATE + ") AS last_image_date, " +
                "AVG(i." + I_CLOUD_COVER + ") AS avg_cloud_cover " +
                "FROM " + areasTable + " a " +
                "LEFT JOIN " + imagesAreasTable + " ia ON ia." + LA_AREA_ID + " = a." + A_ID + " " +
                "LEFT JOIN " + imagesTable + " i ON i." + I_ID + " = ia." + LA_IMAGE_ID + " " +
                "GROUP BY a." + A_ID + " " +
                "ORDER BY a." + A_FARM + ", a." + A_BLOCK + ", a." + A_PARCEL;

        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        List<Object> features = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            features.add(toFeature(row));
        }
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        return fc;
    }

    public Map<String, Object> getAreaFeature(long id) {
        String sql = "SELECT a." + A_ID + " AS id, " +
                "a." + A_FARM + " AS farm_name, a." + A_BLOCK + " AS block_name, a." + A_PARCEL + " AS parcel_name, " +
                "a." + A_GEOM + "::text AS geojson, " +
                "COUNT(i." + I_ID + ") AS image_count, " +
                "MAX(i." + I_ACQUISITION_DATE + ") AS last_image_date, " +
                "AVG(i." + I_CLOUD_COVER + ") AS avg_cloud_cover " +
                "FROM " + areasTable + " a " +
                "LEFT JOIN " + imagesAreasTable + " ia ON ia." + LA_AREA_ID + " = a." + A_ID + " " +
                "LEFT JOIN " + imagesTable + " i ON i." + I_ID + " = ia." + LA_IMAGE_ID + " " +
                "WHERE a." + A_ID + " = ? " +
                "GROUP BY a." + A_ID;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, id);
        if (rows.isEmpty()) return null;
        return toFeature(rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toFeature(Map<String, Object> row) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        Object id = row.get("id");
        feature.put("id", id);

        Object geometry = null;
        String geojson = str(row.get("geojson"));
        if (geojson != null && !geojson.isBlank()) {
            try {
                geometry = unwrapGeometry(mapper.readValue(geojson, Map.class));
            } catch (Exception e) {
                log.warn("[SatelliteDataService] GeoJSON inválido para área {}: {}", id, e.getMessage());
            }
        }
        feature.put("geometry", geometry);

        String farm = str(row.get("farm_name"));
        String block = str(row.get("block_name"));
        String parcel = str(row.get("parcel_name"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", id);
        props.put("name", displayName(farm, block, parcel));
        props.put("farmName", farm);
        props.put("blockName", block);
        props.put("parcelName", parcel);
        props.put("imageCount", toLong(row.get("image_count")));
        props.put("lastImageDate", dateStr(row.get("last_image_date")));
        props.put("avgCloudCover", toDouble(row.get("avg_cloud_cover")));
        feature.put("properties", props);
        return feature;
    }

    /** Combina fazenda/talhão/parcela em um rótulo único "Fazenda · Talhão · Parcela". */
    static String displayName(String farm, String block, String parcel) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{farm, block, parcel}) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(part.trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Aceita geometry pura, Feature ou FeatureCollection e devolve sempre a geometry. */
    @SuppressWarnings("unchecked")
    private Object unwrapGeometry(Map<String, Object> json) {
        if (json == null) return null;
        Object type = json.get("type");
        if ("Feature".equals(type)) {
            return json.get("geometry");
        }
        if ("FeatureCollection".equals(type)) {
            Object feats = json.get("features");
            if (feats instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> f) {
                return ((Map<String, Object>) f).get("geometry");
            }
            return null;
        }
        return json; // já é uma geometry (Polygon/MultiPolygon/...)
    }

    // ── Imagens de uma área (paginadas, filtradas no SQL, via junção N:N) ──────

    public PagedImages getAreaImages(long areaId, String startDate, String endDate,
                                     Double maxCloudCover, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE ia." + LA_AREA_ID + " = ?");
        List<Object> params = new ArrayList<>();
        params.add(areaId);
        if (startDate != null && !startDate.isBlank()) {
            where.append(" AND i.").append(I_ACQUISITION_DATE).append(" >= ?::timestamp");
            params.add(startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            where.append(" AND i.").append(I_ACQUISITION_DATE).append(" <= ?::timestamp");
            params.add(endDate);
        }
        if (maxCloudCover != null) {
            where.append(" AND i.").append(I_CLOUD_COVER).append(" <= ?");
            params.add(maxCloudCover);
        }

        String from = " FROM " + imagesTable + " i " +
                "JOIN " + imagesAreasTable + " ia ON ia." + LA_IMAGE_ID + " = i." + I_ID;

        long total = safeCount("SELECT COUNT(*)" + from + where, params.toArray());

        String sql = "SELECT i." + I_ID + ", i." + I_ACQUISITION_DATE + ", i." + I_CLOUD_COVER + ", " +
                "i." + I_COLLECTION + ", i." + I_IMAGE_ID + ", i." + I_THUMBNAIL_URL +
                from + where +
                " ORDER BY i." + I_ACQUISITION_DATE + " DESC NULLS LAST LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);

        List<ImageSummary> items = jdbc.query(sql, pageParams.toArray(), (rs, n) -> new ImageSummary(
                rs.getLong(I_ID),
                dateStr(rs.getObject(I_ACQUISITION_DATE)),
                (Double) toDouble(rs.getObject(I_CLOUD_COVER)),
                rs.getString(I_COLLECTION),
                rs.getString(I_IMAGE_ID),
                rs.getString(I_THUMBNAIL_URL)
        ));
        return new PagedImages(page, size, total, items);
    }

    // ── Imagens mais recentes (todas as áreas) — exibidas ao abrir o app ────────

    public List<ImageSummary> getRecentImages(int limit) {
        String sql = "SELECT " + I_ID + ", " + I_ACQUISITION_DATE + ", " + I_CLOUD_COVER + ", " +
                I_COLLECTION + ", " + I_IMAGE_ID + ", " + I_THUMBNAIL_URL +
                " FROM " + imagesTable +
                " ORDER BY " + I_ACQUISITION_DATE + " DESC NULLS LAST LIMIT ?";
        return jdbc.query(sql, new Object[]{limit}, (rs, n) -> new ImageSummary(
                rs.getLong(I_ID),
                dateStr(rs.getObject(I_ACQUISITION_DATE)),
                (Double) toDouble(rs.getObject(I_CLOUD_COVER)),
                rs.getString(I_COLLECTION),
                rs.getString(I_IMAGE_ID),
                rs.getString(I_THUMBNAIL_URL)
        ));
    }

    // ── Imagem individual (metadados completos) ────────────────────────────────

    public ImageDetail getImage(long id) {
        String sql = "SELECT i." + I_ID + ", i." + I_ACQUISITION_DATE + ", i." +
                I_CLOUD_COVER + ", i." + I_COLLECTION + ", i." + I_IMAGE_ID + ", " +
                "i." + I_THUMBNAIL_URL + ", i." + I_VISUAL_URL + ", i." + I_METADATA + "::text AS metadata_text, " +
                "(SELECT ia." + LA_AREA_ID + " FROM " + imagesAreasTable + " ia " +
                "WHERE ia." + LA_IMAGE_ID + " = i." + I_ID + " LIMIT 1) AS area_id" +
                " FROM " + imagesTable + " i WHERE i." + I_ID + " = ?";
        List<ImageDetail> rows = jdbc.query(sql, new Object[]{id}, (rs, n) -> new ImageDetail(
                rs.getLong(I_ID),
                (Long) toLong(rs.getObject("area_id")),
                dateStr(rs.getObject(I_ACQUISITION_DATE)),
                (Double) toDouble(rs.getObject(I_CLOUD_COVER)),
                rs.getString(I_COLLECTION),
                rs.getString(I_IMAGE_ID),
                rs.getString(I_THUMBNAIL_URL),
                rs.getString(I_VISUAL_URL),
                rs.getString("metadata_text")
        ));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private long safeCount(String sql, Object... args) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class, args);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("[SatelliteDataService] count falhou ({}): {}", sql, e.getMessage());
            return -1;
        }
    }

    private String safeScalarDate(String sql) {
        try {
            return dateStr(jdbc.queryForObject(sql, Object.class));
        } catch (Exception e) {
            log.warn("[SatelliteDataService] scalar date falhou ({}): {}", sql, e.getMessage());
            return null;
        }
    }

    static String str(Object v) {
        return v == null ? null : v.toString();
    }

    static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    /** Normaliza Date/Timestamp/texto para ISO-8601 (data ou data-hora). */
    static String dateStr(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.time.temporal.TemporalAccessor) return v.toString();
        return v.toString();
    }
}

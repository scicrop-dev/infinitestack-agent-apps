package com.infinitestack.recomendador.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Service
public class DataService {

    private static final Logger log = LoggerFactory.getLogger(DataService.class);

    private static final String DEFAULT_QUERY = """
        SELECT "QT_TCH_REAL", "GDD_CICLO", "DIAS_CICLO", "PLUVIOMETRIA_CICLO",
               "PLUVIOMETRIA_PREV_1M", "DE_VARIED", "CD_ESTAGIO", "MES_COLHEITA",
               "MES_PLANTIO", "QT_POTASSIO_M3", "DS_TERRA", "CD_FORNEC",
               "QT_AREA_PROD", "CD_AMBIENTE_PROD"
        FROM gold.sugarcane_productivity
        WHERE "QT_TCH_REAL" IS NOT NULL
          AND "QT_TCH_REAL" > 0
        """;

    private final JdbcTemplate jdbc;

    private String currentQuery = DEFAULT_QUERY;
    private List<Map<String, Object>> rawData = new ArrayList<>();
    private List<String> distinctVarieties = new ArrayList<>();
    private List<Integer> distinctEstagios = new ArrayList<>();
    private List<Integer> distinctAmbientes = new ArrayList<>();
    private int medianFornec = 0;
    private double meanAreaProd = 0.0;
    private double meanPotassio = 0.0;
    private double meanDsTerraEnc = 0.0;

    // Percentile climate values for the 5 scenarios
    private final double[] gddPercentiles = new double[5];
    private final double[] pluvCicloPercentiles = new double[5];
    private final double[] pluvPrev1mPercentiles = new double[5];
    private final double[] potassioPercentiles = new double[5];

    // Encoding maps for categorical features
    private final Map<String, Integer> varietyEncoding = new LinkedHashMap<>();
    private final Map<String, Integer> dsTerraEncoding = new HashMap<>();

    public DataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void load() {
        reload(currentQuery);
    }

    public synchronized void reload(String sql) {
        try {
            currentQuery = sql;
            rawData = jdbc.queryForList(sql);
            log.info("[DataService] Loaded {} rows", rawData.size());
            buildEncodings();
            computeStats();
        } catch (Exception e) {
            log.error("[DataService] Failed to load data: {}", e.getMessage());
        }
    }

    public String getQuery() { return currentQuery; }
    public void setQuery(String sql) { currentQuery = sql; }

    private void buildEncodings() {
        Set<String> varieties = new LinkedHashSet<>();
        Set<String> terrains = new LinkedHashSet<>();
        Set<Integer> estSet = new TreeSet<>();
        Set<Integer> ambSet = new TreeSet<>();

        for (Map<String, Object> row : rawData) {
            String v = str(row.get("DE_VARIED"));
            if (!v.isEmpty()) varieties.add(v);
            String t = str(row.get("DS_TERRA"));
            if (!t.isEmpty()) terrains.add(t);
            Integer e = toInt(row.get("CD_ESTAGIO"));
            if (e != null) estSet.add(e);
            Integer a = toInt(row.get("CD_AMBIENTE_PROD"));
            if (a != null) ambSet.add(a);
        }

        int idx = 0;
        for (String v : varieties) varietyEncoding.put(v, idx++);
        idx = 0;
        for (String t : terrains) dsTerraEncoding.put(t, idx++);

        distinctVarieties = new ArrayList<>(varieties);
        distinctEstagios = new ArrayList<>(estSet);
        distinctAmbientes = new ArrayList<>(ambSet);
    }

    private void computeStats() {
        List<Double> gdds = new ArrayList<>();
        List<Double> pluvCiclos = new ArrayList<>();
        List<Double> pluvPrevs = new ArrayList<>();
        List<Double> potassios = new ArrayList<>();
        List<Integer> fornecs = new ArrayList<>();
        List<Double> areas = new ArrayList<>();
        List<Integer> dsTerraEncs = new ArrayList<>();

        for (Map<String, Object> row : rawData) {
            Double gdd = toDouble(row.get("GDD_CICLO"));
            if (gdd != null) gdds.add(gdd);
            Double pluvC = toDouble(row.get("PLUVIOMETRIA_CICLO"));
            if (pluvC != null) pluvCiclos.add(pluvC);
            Double pluvP = toDouble(row.get("PLUVIOMETRIA_PREV_1M"));
            if (pluvP != null) pluvPrevs.add(pluvP);
            Double pot = toDouble(row.get("QT_POTASSIO_M3"));
            if (pot != null) potassios.add(pot);
            Integer f = toInt(row.get("CD_FORNEC"));
            if (f != null) fornecs.add(f);
            Double area = toDouble(row.get("QT_AREA_PROD"));
            if (area != null) areas.add(area);
            String terra = str(row.get("DS_TERRA"));
            Integer enc = dsTerraEncoding.get(terra);
            if (enc != null) dsTerraEncs.add(enc);
        }

        Collections.sort(gdds);
        Collections.sort(pluvCiclos);
        Collections.sort(pluvPrevs);
        Collections.sort(potassios);
        Collections.sort(fornecs);

        int[] pctIdx = {4, 24, 49, 74, 94}; // p5, p25, p50, p75, p95

        for (int i = 0; i < 5; i++) {
            gddPercentiles[i] = percentile(gdds, pctIdx[i]);
            pluvCicloPercentiles[i] = percentile(pluvCiclos, pctIdx[i]);
            pluvPrev1mPercentiles[i] = percentile(pluvPrevs, pctIdx[i]);
            potassioPercentiles[i] = percentile(potassios, pctIdx[i]);
        }

        medianFornec = fornecs.isEmpty() ? 0 : fornecs.get(fornecs.size() / 2);
        meanAreaProd = areas.stream().mapToDouble(d -> d).average().orElse(0.0);
        meanPotassio = potassios.stream().mapToDouble(d -> d).average().orElse(0.0);
        meanDsTerraEnc = dsTerraEncs.stream().mapToInt(i -> i).average().orElse(0.0);

        log.info("[DataService] Stats computed. GDD range [{:.1f}, {:.1f}], Fornec median {}",
                gdds.isEmpty() ? 0 : gdds.get(0),
                gdds.isEmpty() ? 0 : gdds.get(gdds.size() - 1),
                medianFornec);
    }

    private double percentile(List<Double> sorted, int pctIndex) {
        if (sorted.isEmpty()) return 0.0;
        int idx = (int) Math.round(sorted.size() * (pctIndex + 1) / 100.0) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    // ── public getters ──────────────────────────────────────────────────────

    public List<Map<String, Object>> getRawData() { return rawData; }
    public List<String> getDistinctVarieties() { return distinctVarieties; }
    public List<Integer> getDistinctEstagios() { return distinctEstagios; }
    public List<Integer> getDistinctAmbientes() { return distinctAmbientes; }
    public int getMedianFornec() { return medianFornec; }
    public double getMeanAreaProd() { return meanAreaProd; }
    public double getMeanPotassio() { return meanPotassio; }
    public double getMeanDsTerraEnc() { return meanDsTerraEnc; }
    public double[] getGddPercentiles() { return gddPercentiles; }
    public double[] getPluvCicloPercentiles() { return pluvCicloPercentiles; }
    public double[] getPluvPrev1mPercentiles() { return pluvPrev1mPercentiles; }
    public double[] getPotassioPercentiles() { return potassioPercentiles; }
    public Map<String, Integer> getVarietyEncoding() { return varietyEncoding; }
    public Map<String, Integer> getDsTerraEncoding() { return dsTerraEncoding; }

    // ── helpers ─────────────────────────────────────────────────────────────

    static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    static Double toDouble(Object v) {
        if (v == null) return null;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    static Integer toInt(Object v) {
        if (v == null) return null;
        try { return (int) Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}

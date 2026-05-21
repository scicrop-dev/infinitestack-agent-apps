package com.infinitestack.recomendador.service;

import com.infinitestack.recomendador.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.regression.GradientTreeBoost;
import smile.regression.RandomForest;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    private static final String[] COL_NAMES = {
        "QT_TCH_REAL", "GDD_CICLO", "DIAS_CICLO", "PLUVIOMETRIA_CICLO",
        "PLUVIOMETRIA_PREV_1M", "DE_VARIED_ENC", "CD_ESTAGIO",
        "MES_COLHEITA", "MES_PLANTIO", "QT_POTASSIO_M3",
        "DS_TERRA_ENC", "CD_FORNEC", "QT_AREA_PROD", "CD_AMBIENTE_PROD"
    };

    private static final String[] SCENARIO_NAMES = {"pessimo", "ruim", "medio", "bom", "excelente"};
    private static final String[] SCENARIO_LABELS = {"Péssimo", "Ruim", "Médio", "Bom", "Excelente"};

    private final DataService dataService;
    private RandomForest rf;
    private GradientTreeBoost gbm;
    private volatile boolean trained = false;
    private volatile boolean training = false;
    private int sampleCount = 0;

    public ModelService(DataService dataService) {
        this.dataService = dataService;
    }

    @PostConstruct
    public void scheduleTraining() {
        CompletableFuture.runAsync(this::train);
    }

    public void retrain(String sql) {
        if (training) return;
        CompletableFuture.runAsync(() -> {
            trained = false;
            training = true;
            try {
                dataService.reload(sql);
                train();
            } finally {
                training = false;
            }
        });
    }

    private void train() {
        List<Map<String, Object>> rows = dataService.getRawData();
        if (rows.isEmpty()) {
            log.warn("[ModelService] No data to train on");
            return;
        }

        Map<String, Integer> vEnc = dataService.getVarietyEncoding();
        Map<String, Integer> tEnc = dataService.getDsTerraEncoding();

        List<double[]> validRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double[] r = buildFeatureRow(row, vEnc, tEnc);
            if (r != null) validRows.add(r);
        }

        if (validRows.size() < 10) {
            log.warn("[ModelService] Only {} valid rows — insufficient for training", validRows.size());
            return;
        }

        sampleCount = validRows.size();
        double[][] matrix = validRows.toArray(new double[0][]);

        try {
            DataFrame df = DataFrame.of(matrix, COL_NAMES);
            Formula formula = Formula.lhs("QT_TCH_REAL");

            log.info("[ModelService] Training Random Forest on {} samples...", sampleCount);
            Properties rfProps = new Properties();
            rfProps.setProperty("smile.random.forest.trees", "200");
            rf = RandomForest.fit(formula, df, rfProps);

            log.info("[ModelService] Training Gradient Boosted Trees (XGBoost equiv.)...");
            Properties gbmProps = new Properties();
            gbmProps.setProperty("smile.gbt.trees", "200");
            gbmProps.setProperty("smile.gbt.max.depth", "5");
            gbmProps.setProperty("smile.gbt.shrinkage", "0.05");
            gbm = GradientTreeBoost.fit(formula, df, gbmProps);

            trained = true;
            log.info("[ModelService] Models trained. RF OOB error: {}", rf.metrics());
        } catch (Exception e) {
            log.error("[ModelService] Training failed: {}", e.getMessage(), e);
        }
    }

    public SimulationResponse simulate(SimulationRequest req) {
        if (!trained) throw new IllegalStateException("Modelos ainda em treinamento ou sem dados");

        int diasCiclo = calcDiasCiclo(req.getMesPlantio(), req.getMesColheita());
        int cdFornec = dataService.getMedianFornec();

        Map<String, Integer> vEnc = dataService.getVarietyEncoding();
        Map<String, Integer> tEnc = dataService.getDsTerraEncoding();

        int variedEnc = vEnc.getOrDefault(req.getDeVaried(), 0);
        int dsTerraEnc = (int) Math.round(dataService.getMeanDsTerraEnc());

        // 5 climate scenarios
        double[] gdds = dataService.getGddPercentiles();
        double[] pluv = dataService.getPluvCicloPercentiles();
        double[] pluvP = dataService.getPluvPrev1mPercentiles();
        double[] pot = dataService.getPotassioPercentiles();
        double meanArea = dataService.getMeanAreaProd();

        List<ScenarioResult> scenarios = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double[] feat = buildPredRow(
                diasCiclo, gdds[i], pluv[i], pluvP[i], pot[i],
                variedEnc, req.getCdEstagio(), req.getMesColheita(),
                req.getMesPlantio(), dsTerraEnc, cdFornec, meanArea, req.getCdAmbienteProd()
            );
            DataFrame pdf = DataFrame.of(new double[][]{feat}, COL_NAMES);

            ScenarioResult s = new ScenarioResult();
            s.setName(SCENARIO_NAMES[i]);
            s.setLabel(SCENARIO_LABELS[i]);
            s.setGddCiclo(round2(gdds[i]));
            s.setPluviometriaCiclo(round2(pluv[i]));
            s.setPluviometriaPrev1m(round2(pluvP[i]));
            s.setQtPotassioM3(round2(pot[i]));
            s.setDiasCiclo(diasCiclo);
            s.setCdFornec(cdFornec);
            s.setRfPrediction(round2(rf.predict(pdf.get(0))));
            s.setGbmPrediction(round2(gbm.predict(pdf.get(0))));
            scenarios.add(s);
        }

        // Variations: top 4 other varieties + ±1 month combinations (median climate)
        double gddMed = gdds[2];
        double pluvMed = pluv[2];
        double pluvPMed = pluvP[2];
        double potMed = pot[2];

        List<VariationResult> variations = new ArrayList<>();

        // Alt varieties (up to 4 different from selected)
        List<String> altVarieties = new ArrayList<>();
        for (String v : dataService.getDistinctVarieties()) {
            if (!v.equals(req.getDeVaried())) altVarieties.add(v);
            if (altVarieties.size() == 4) break;
        }
        for (String v : altVarieties) {
            int enc = vEnc.getOrDefault(v, 0);
            double[] feat = buildPredRow(diasCiclo, gddMed, pluvMed, pluvPMed, potMed,
                    enc, req.getCdEstagio(), req.getMesColheita(),
                    req.getMesPlantio(), dsTerraEnc, cdFornec, meanArea, req.getCdAmbienteProd());
            DataFrame pdf = DataFrame.of(new double[][]{feat}, COL_NAMES);
            VariationResult vr = new VariationResult();
            vr.setDeVaried(v);
            vr.setMesPlantio(req.getMesPlantio());
            vr.setMesColheita(req.getMesColheita());
            vr.setDiasCiclo(diasCiclo);
            vr.setRfPrediction(round2(rf.predict(pdf.get(0))));
            vr.setGbmPrediction(round2(gbm.predict(pdf.get(0))));
            variations.add(vr);
        }

        // Alt month shifts (+1, +2, -1 on harvest month, same variety)
        int[] monthShifts = {1, 2, -1};
        for (int shift : monthShifts) {
            int altColheita = ((req.getMesColheita() - 1 + shift + 12) % 12) + 1;
            int altDias = calcDiasCiclo(req.getMesPlantio(), altColheita);
            double[] feat = buildPredRow(altDias, gddMed, pluvMed, pluvPMed, potMed,
                    variedEnc, req.getCdEstagio(), altColheita,
                    req.getMesPlantio(), dsTerraEnc, cdFornec, meanArea, req.getCdAmbienteProd());
            DataFrame pdf = DataFrame.of(new double[][]{feat}, COL_NAMES);
            VariationResult vr = new VariationResult();
            vr.setDeVaried(req.getDeVaried());
            vr.setMesPlantio(req.getMesPlantio());
            vr.setMesColheita(altColheita);
            vr.setDiasCiclo(altDias);
            vr.setRfPrediction(round2(rf.predict(pdf.get(0))));
            vr.setGbmPrediction(round2(gbm.predict(pdf.get(0))));
            variations.add(vr);
        }

        SimulationResponse resp = new SimulationResponse();
        resp.setScenarios(scenarios);
        resp.setVariations(variations);
        resp.setDiasCiclo(diasCiclo);
        resp.setCdFornec(cdFornec);
        return resp;
    }

    private double[] buildFeatureRow(Map<String, Object> row,
                                     Map<String, Integer> vEnc,
                                     Map<String, Integer> tEnc) {
        Double tch = DataService.toDouble(row.get("QT_TCH_REAL"));
        Double gdd = DataService.toDouble(row.get("GDD_CICLO"));
        Double dias = DataService.toDouble(row.get("DIAS_CICLO"));
        Double pluvC = DataService.toDouble(row.get("PLUVIOMETRIA_CICLO"));
        Double pluvP = DataService.toDouble(row.get("PLUVIOMETRIA_PREV_1M"));
        String varied = DataService.str(row.get("DE_VARIED"));
        Integer est = DataService.toInt(row.get("CD_ESTAGIO"));
        Integer mesC = DataService.toInt(row.get("MES_COLHEITA"));
        Integer mesP = DataService.toInt(row.get("MES_PLANTIO"));
        Double pot = DataService.toDouble(row.get("QT_POTASSIO_M3"));
        String terra = DataService.str(row.get("DS_TERRA"));
        Integer fornec = DataService.toInt(row.get("CD_FORNEC"));
        Double area = DataService.toDouble(row.get("QT_AREA_PROD"));
        Integer amb = DataService.toInt(row.get("CD_AMBIENTE_PROD"));

        if (tch == null || gdd == null || dias == null || pluvC == null || est == null
                || mesC == null || mesP == null || amb == null) return null;

        return new double[]{
            tch,
            gdd,
            dias,
            pluvC,
            pluvP != null ? pluvP : pluvC * 0.08,
            vEnc.getOrDefault(varied, 0),
            est,
            mesC,
            mesP,
            pot != null ? pot : 0.0,
            tEnc.getOrDefault(terra, 0),
            fornec != null ? fornec : 0,
            area != null ? area : 0.0,
            amb
        };
    }

    private double[] buildPredRow(int diasCiclo, double gdd, double pluvCiclo,
                                   double pluvPrev, double potassio, int variedEnc,
                                   int cdEstagio, int mesColheita, int mesPlantio,
                                   int dsTerraEnc, int cdFornec, double areaMedia, int cdAmbiente) {
        return new double[]{
            0.0, // dummy QT_TCH_REAL
            gdd,
            diasCiclo,
            pluvCiclo,
            pluvPrev,
            variedEnc,
            cdEstagio,
            mesColheita,
            mesPlantio,
            potassio,
            dsTerraEnc,
            cdFornec,
            areaMedia,
            cdAmbiente
        };
    }

    private static int calcDiasCiclo(int mesPlantio, int mesColheita) {
        int diff = mesColheita - mesPlantio;
        if (diff <= 0) diff += 12;
        int dias = diff * 30;
        // sugarcane grows minimum ~12 months; shorter apparent cycles are multi-year
        if (dias < 300) dias += 365;
        return dias;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public boolean isTrained() { return trained; }
    public boolean isTraining() { return training; }
    public int getSampleCount() { return sampleCount; }
}

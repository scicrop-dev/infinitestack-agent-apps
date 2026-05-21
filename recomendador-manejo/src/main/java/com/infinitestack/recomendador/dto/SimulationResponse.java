package com.infinitestack.recomendador.dto;

import java.util.List;

public class SimulationResponse {
    private List<ScenarioResult> scenarios;
    private List<VariationResult> variations;
    private int diasCiclo;
    private int cdFornec;

    public List<ScenarioResult> getScenarios() { return scenarios; }
    public void setScenarios(List<ScenarioResult> scenarios) { this.scenarios = scenarios; }
    public List<VariationResult> getVariations() { return variations; }
    public void setVariations(List<VariationResult> variations) { this.variations = variations; }
    public int getDiasCiclo() { return diasCiclo; }
    public void setDiasCiclo(int diasCiclo) { this.diasCiclo = diasCiclo; }
    public int getCdFornec() { return cdFornec; }
    public void setCdFornec(int cdFornec) { this.cdFornec = cdFornec; }
}

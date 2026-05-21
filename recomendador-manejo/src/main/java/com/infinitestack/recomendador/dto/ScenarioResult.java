package com.infinitestack.recomendador.dto;

public class ScenarioResult {
    private String name;
    private String label;
    private double gddCiclo;
    private double pluviometriaCiclo;
    private double pluviometriaPrev1m;
    private double qtPotassioM3;
    private double rfPrediction;
    private double gbmPrediction;
    private int diasCiclo;
    private int cdFornec;

    public ScenarioResult() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public double getGddCiclo() { return gddCiclo; }
    public void setGddCiclo(double gddCiclo) { this.gddCiclo = gddCiclo; }
    public double getPluviometriaCiclo() { return pluviometriaCiclo; }
    public void setPluviometriaCiclo(double v) { this.pluviometriaCiclo = v; }
    public double getPluviometriaPrev1m() { return pluviometriaPrev1m; }
    public void setPluviometriaPrev1m(double v) { this.pluviometriaPrev1m = v; }
    public double getQtPotassioM3() { return qtPotassioM3; }
    public void setQtPotassioM3(double v) { this.qtPotassioM3 = v; }
    public double getRfPrediction() { return rfPrediction; }
    public void setRfPrediction(double rfPrediction) { this.rfPrediction = rfPrediction; }
    public double getGbmPrediction() { return gbmPrediction; }
    public void setGbmPrediction(double gbmPrediction) { this.gbmPrediction = gbmPrediction; }
    public int getDiasCiclo() { return diasCiclo; }
    public void setDiasCiclo(int diasCiclo) { this.diasCiclo = diasCiclo; }
    public int getCdFornec() { return cdFornec; }
    public void setCdFornec(int cdFornec) { this.cdFornec = cdFornec; }
}

package com.infinitestack.recomendador.dto;

public class VariationResult {
    private String deVaried;
    private int mesPlantio;
    private int mesColheita;
    private int diasCiclo;
    private double rfPrediction;
    private double gbmPrediction;

    public String getDeVaried() { return deVaried; }
    public void setDeVaried(String deVaried) { this.deVaried = deVaried; }
    public int getMesPlantio() { return mesPlantio; }
    public void setMesPlantio(int mesPlantio) { this.mesPlantio = mesPlantio; }
    public int getMesColheita() { return mesColheita; }
    public void setMesColheita(int mesColheita) { this.mesColheita = mesColheita; }
    public int getDiasCiclo() { return diasCiclo; }
    public void setDiasCiclo(int diasCiclo) { this.diasCiclo = diasCiclo; }
    public double getRfPrediction() { return rfPrediction; }
    public void setRfPrediction(double rfPrediction) { this.rfPrediction = rfPrediction; }
    public double getGbmPrediction() { return gbmPrediction; }
    public void setGbmPrediction(double gbmPrediction) { this.gbmPrediction = gbmPrediction; }
}

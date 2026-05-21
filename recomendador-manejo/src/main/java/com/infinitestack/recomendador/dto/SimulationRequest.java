package com.infinitestack.recomendador.dto;

public class SimulationRequest {
    private String deVaried;
    private Integer cdEstagio;
    private Integer mesColheita;
    private Integer mesPlantio;
    private Integer cdAmbienteProd;

    public String getDeVaried() { return deVaried; }
    public void setDeVaried(String deVaried) { this.deVaried = deVaried; }
    public Integer getCdEstagio() { return cdEstagio; }
    public void setCdEstagio(Integer cdEstagio) { this.cdEstagio = cdEstagio; }
    public Integer getMesColheita() { return mesColheita; }
    public void setMesColheita(Integer mesColheita) { this.mesColheita = mesColheita; }
    public Integer getMesPlantio() { return mesPlantio; }
    public void setMesPlantio(Integer mesPlantio) { this.mesPlantio = mesPlantio; }
    public Integer getCdAmbienteProd() { return cdAmbienteProd; }
    public void setCdAmbienteProd(Integer cdAmbienteProd) { this.cdAmbienteProd = cdAmbienteProd; }
}

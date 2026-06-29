package com.infinitestack.satellitehubviewer.dto;

/** Resumo exibido no dashboard inicial. */
public record DashboardStats(
        long totalAreas,
        long totalImages,
        String lastSync,
        String lastImageDate
) {}

package com.infinitestack.satellitehubviewer.dto;

import java.util.List;

/** Página de imagens (paginação sob demanda da timeline). */
public record PagedImages(
        int page,
        int size,
        long total,
        List<ImageSummary> items
) {}

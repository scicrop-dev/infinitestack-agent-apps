package com.infinitestack.satellitehubviewer.dto;

/** Detalhe completo de uma imagem, incluindo metadata (JSON cru) e thumbnail. */
public record ImageDetail(
        long id,
        Long areaId,
        String acquisitionDate,
        Double cloudCover,
        String collection,
        String imageId,
        String thumbnailUrl,
        String visualUrl,
        String metadata
) {}

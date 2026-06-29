package com.infinitestack.satellitehubviewer.dto;

/** Item da timeline de imagens de uma área. */
public record ImageSummary(
        long id,
        String acquisitionDate,
        Double cloudCover,
        String collection,
        String imageId,
        String thumbnailUrl
) {}

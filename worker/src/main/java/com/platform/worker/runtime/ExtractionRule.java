package com.platform.worker.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;

/** BODY selects jsonPath; HEADER selects headerName. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractionRule(String variableName, String source, String jsonPath,
                             String headerName, String description) {
}
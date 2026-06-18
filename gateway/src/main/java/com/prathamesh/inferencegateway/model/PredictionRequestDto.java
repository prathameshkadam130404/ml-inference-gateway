package com.prathamesh.inferencegateway.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public class PredictionRequestDto {

    @NotEmpty(message = "Features array cannot be empty")
    @Size(min = 54, max = 54, message = "Features array must be exactly 54 elements long (Covtype requirement)")
    private List<Float> features;

    public List<Float> getFeatures() {
        return features;
    }

    public void setFeatures(List<Float> features) {
        this.features = features;
    }
}

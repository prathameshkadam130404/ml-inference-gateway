package com.prathamesh.inferencegateway.model;

import java.util.List;

public class PredictionResponseDto {

    private String requestId;
    private List<Float> classProbabilities;
    private int predictedClassIndex;
    private long inferenceLatencyMicros;

    public PredictionResponseDto() {}

    public PredictionResponseDto(String requestId, List<Float> classProbabilities, int predictedClassIndex, long inferenceLatencyMicros) {
        this.requestId = requestId;
        this.classProbabilities = classProbabilities;
        this.predictedClassIndex = predictedClassIndex;
        this.inferenceLatencyMicros = inferenceLatencyMicros;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public List<Float> getClassProbabilities() {
        return classProbabilities;
    }

    public void setClassProbabilities(List<Float> classProbabilities) {
        this.classProbabilities = classProbabilities;
    }

    public int getPredictedClassIndex() {
        return predictedClassIndex;
    }

    public void setPredictedClassIndex(int predictedClassIndex) {
        this.predictedClassIndex = predictedClassIndex;
    }

    public long getInferenceLatencyMicros() {
        return inferenceLatencyMicros;
    }

    public void setInferenceLatencyMicros(long inferenceLatencyMicros) {
        this.inferenceLatencyMicros = inferenceLatencyMicros;
    }
}

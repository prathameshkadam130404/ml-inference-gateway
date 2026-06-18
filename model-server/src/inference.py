import time
import logging
import numpy as np
import grpc
import inference_pb2
import inference_pb2_grpc
from grpc import StatusCode
from fault_injection import injector
from model_loader import loader

logger = logging.getLogger(__name__)

class InferenceServiceServicer(inference_pb2_grpc.InferenceServiceServicer):
    
    async def PredictBatch(self, request, context):
        # 1. Fault Injection (must happen before processing)
        await injector.maybe_inject_faults(context)
        
        # 2. Validation
        if not loader.is_ready():
            context.abort(StatusCode.UNAVAILABLE, "Model not loaded or warmed up.")
            
        items = request.items
        if not items:
            context.abort(StatusCode.INVALID_ARGUMENT, "Batch is empty.")
            
        batch_size = len(items)
        features = []
        request_ids = []
        
        # We expect a flat float vector of 54 features for Covtype
        EXPECTED_DIM = 54
        
        for item in items:
            if len(item.values) != EXPECTED_DIM:
                context.abort(
                    StatusCode.INVALID_ARGUMENT, 
                    f"Invalid feature dimension for request_id {item.request_id}. Expected {EXPECTED_DIM}, got {len(item.values)}"
                )
            features.append(item.values)
            request_ids.append(item.request_id)
            
        # 3. Prepare Input Tensor
        # Shape: [batch_size, 54]
        input_tensor = np.array(features, dtype=np.float32)
        
        session = loader.get_session()
        input_name = session.get_inputs()[0].name
        
        # 4. Inference and Latency Measurement
        start_time = time.perf_counter()
        
        try:
            # Run inference. outputs[0] will be [batch_size, 7] float array of probabilities
            outputs = session.run(None, {input_name: input_tensor})
            probabilities = outputs[0]
        except Exception as e:
            logger.error(f"Inference failed: {e}")
            context.abort(StatusCode.INTERNAL, "Model inference failed internally.")
            
        latency_micros = int((time.perf_counter() - start_time) * 1_000_000)
        
        # 5. Build Response
        response = inference_pb2.PredictBatchResponse()
        for i in range(batch_size):
            pred = inference_pb2.Prediction()
            pred.request_id = request_ids[i]
            pred.class_probabilities.extend(probabilities[i].tolist())
            pred.predicted_class_index = int(np.argmax(probabilities[i]))
            # Per the specification, latency is populated per prediction. We divide the batch latency roughly or just report total inference latency.
            # Usually inference latency is reported as the time the batch took, meaning all items in the batch experienced this inference time.
            pred.inference_latency_micros = latency_micros
            
            response.predictions.append(pred)
            
        return response

    async def CheckReadiness(self, request, context):
        response = inference_pb2.ReadinessResponse()
        response.ready = loader.is_ready()
        response.model_version = loader.get_model_version()
        return response

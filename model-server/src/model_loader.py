import os
import onnxruntime as ort
import numpy as np
import time
import logging

logger = logging.getLogger(__name__)

class ModelLoader:
    def __init__(self):
        self._ready = False
        self._session = None
        self._model_version = "v1.0"
        
    def load_and_warmup(self):
        model_path = os.environ.get("MODEL_PATH")
        if not model_path:
            logger.error("MODEL_PATH environment variable not set.")
            return False
            
        if not os.path.exists(model_path):
            logger.error(f"Model file not found at {model_path}")
            return False
            
        try:
            # Load model
            logger.info(f"Loading ONNX model from {model_path}...")
            # We explicitly use CPUExecutionProvider per the spec
            self._session = ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])
            
            # Warmup inference
            logger.info("Performing warmup inference...")
            # Shape matches our Covtype dummy input: [Batch, 54]
            # Since dynamic axes were used for batch size, we'll warm up with a batch of 1
            dummy_input = np.random.randn(1, 54).astype(np.float32)
            input_name = self._session.get_inputs()[0].name
            
            start_time = time.perf_counter()
            self._session.run(None, {input_name: dummy_input})
            warmup_latency_ms = (time.perf_counter() - start_time) * 1000
            
            logger.info(f"Warmup complete in {warmup_latency_ms:.2f} ms")
            self._ready = True
            return True
            
        except Exception as e:
            logger.error(f"Failed to load or warmup model: {e}")
            return False

    def is_ready(self) -> bool:
        return self._ready

    def get_session(self):
        return self._session

    def get_model_version(self) -> str:
        return self._model_version

# Global singleton loader
loader = ModelLoader()

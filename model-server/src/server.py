import os
import asyncio
import logging
import grpc
import inference_pb2_grpc
from grpc_health.v1 import health
from grpc_health.v1 import health_pb2
from grpc_health.v1 import health_pb2_grpc

from inference import InferenceServiceServicer
from model_loader import loader

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

async def serve():
    # Attempt to load model and warmup
    if not loader.load_and_warmup():
        logger.error("Critical failure: Model could not be loaded. Server will not be marked as ready.")
        # We continue starting the server so the readiness probe fails correctly 
        # instead of the container crash-looping blindly, allowing k8s/compose to see the NOT_READY state.
    
    server = grpc.aio.server()
    
    # Register our inference service
    inference_pb2_grpc.add_InferenceServiceServicer_to_server(InferenceServiceServicer(), server)
    
    # Register standard health check service
    health_servicer = health.HealthServicer(experimental_non_blocking=True, experimental_thread_pool=None)
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    
    # We can mark the InferenceService health based on model load status
    status = health_pb2.HealthCheckResponse.SERVING if loader.is_ready() else health_pb2.HealthCheckResponse.NOT_SERVING
    health_servicer.set("inference.InferenceService", status)
    
    port = os.environ.get("PORT", "50051")
    listen_addr = f"[::]:{port}"
    server.add_insecure_port(listen_addr)
    
    logger.info(f"Starting async gRPC server on {listen_addr}")
    await server.start()
    
    # Wait for termination
    try:
        await server.wait_for_termination()
    except KeyboardInterrupt:
        logger.info("Shutting down server...")
        await server.stop(grace=5)

if __name__ == "__main__":
    asyncio.run(serve())

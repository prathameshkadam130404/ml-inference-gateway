import pytest
import numpy as np
import os
import sys
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '../src')))
from unittest.mock import patch, MagicMock
from grpc import StatusCode
import inference_pb2

from inference import InferenceServiceServicer

@pytest.fixture
def servicer():
    return InferenceServiceServicer()

@pytest.mark.asyncio
@patch('src.inference.loader.is_ready')
async def test_check_readiness(mock_is_ready, servicer):
    mock_is_ready.return_value = True
    with patch('src.inference.loader.get_model_version', return_value="v1.0"):
        request = inference_pb2.ReadinessRequest()
        context = MagicMock()
        response = await servicer.CheckReadiness(request, context)
        assert response.ready is True
        assert response.model_version == "v1.0"

@pytest.mark.asyncio
@patch('inference.loader.is_ready')
@patch('inference.injector.maybe_inject_faults')
async def test_predict_batch_not_ready(mock_inject, mock_is_ready, servicer):
    mock_is_ready.return_value = False
    request = inference_pb2.PredictBatchRequest()
    context = MagicMock()
    context.abort.side_effect = Exception("Abort")
    
    with pytest.raises(Exception):
        await servicer.PredictBatch(request, context)
    context.abort.assert_called_once_with(StatusCode.UNAVAILABLE, "Model not loaded or warmed up.")

@pytest.mark.asyncio
@patch('inference.loader.is_ready')
@patch('inference.injector.maybe_inject_faults')
async def test_predict_batch_empty(mock_inject, mock_is_ready, servicer):
    mock_is_ready.return_value = True
    request = inference_pb2.PredictBatchRequest()
    context = MagicMock()
    context.abort.side_effect = Exception("Abort")
    
    with pytest.raises(Exception):
        await servicer.PredictBatch(request, context)
    context.abort.assert_called_once_with(StatusCode.INVALID_ARGUMENT, "Batch is empty.")

@pytest.mark.asyncio
@patch('inference.loader.is_ready')
@patch('inference.injector.maybe_inject_faults')
@patch('inference.loader.get_session')
async def test_predict_batch_success(mock_get_session, mock_inject, mock_is_ready, servicer):
    mock_is_ready.return_value = True
    
    # Mock session
    mock_session = MagicMock()
    mock_input = MagicMock()
    mock_input.name = "input"
    mock_session.get_inputs.return_value = [mock_input]
    
    # Mock inference result for a batch of 2
    mock_outputs = [np.array([
        [0.1, 0.2, 0.7, 0.0, 0.0, 0.0, 0.0], # class 2
        [0.8, 0.1, 0.05, 0.05, 0.0, 0.0, 0.0] # class 0
    ])]
    mock_session.run.return_value = mock_outputs
    mock_get_session.return_value = mock_session
    
    request = inference_pb2.PredictBatchRequest()
    fv1 = request.items.add()
    fv1.request_id = "req-1"
    fv1.values.extend([0.5] * 54)
    
    fv2 = request.items.add()
    fv2.request_id = "req-2"
    fv2.values.extend([0.1] * 54)
    
    context = MagicMock()
    
    response = await servicer.PredictBatch(request, context)
    
    assert len(response.predictions) == 2
    assert response.predictions[0].request_id == "req-1"
    assert response.predictions[0].predicted_class_index == 2
    assert response.predictions[0].inference_latency_micros > 0
    
    assert response.predictions[1].request_id == "req-2"
    assert response.predictions[1].predicted_class_index == 0

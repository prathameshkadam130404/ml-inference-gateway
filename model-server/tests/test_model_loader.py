import pytest
import os
import sys
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '../src')))
import numpy as np
from unittest.mock import patch, MagicMock
from model_loader import ModelLoader

@pytest.fixture
def loader():
    return ModelLoader()

def test_initial_state(loader):
    assert not loader.is_ready()
    assert loader.get_session() is None

@patch('src.model_loader.ort.InferenceSession')
@patch('os.path.exists')
@patch('os.environ.get')
def test_load_and_warmup_success(mock_env_get, mock_exists, mock_ort_session, loader):
    mock_env_get.return_value = "dummy.onnx"
    mock_exists.return_value = True
    
    mock_session_instance = MagicMock()
    mock_input = MagicMock()
    mock_input.name = "input"
    mock_session_instance.get_inputs.return_value = [mock_input]
    mock_ort_session.return_value = mock_session_instance
    
    assert loader.load_and_warmup() is True
    assert loader.is_ready() is True
    assert loader.get_session() == mock_session_instance
    mock_session_instance.run.assert_called_once()

@patch('os.environ.get')
def test_load_and_warmup_missing_env(mock_env_get, loader):
    mock_env_get.return_value = None
    assert loader.load_and_warmup() is False
    assert loader.is_ready() is False

@patch('src.model_loader.ort.InferenceSession')
@patch('os.path.exists')
@patch('os.environ.get')
def test_load_and_warmup_exception(mock_env_get, mock_exists, mock_ort_session, loader):
    mock_env_get.return_value = "dummy.onnx"
    mock_exists.return_value = True
    mock_ort_session.side_effect = Exception("Failed to load")
    
    assert loader.load_and_warmup() is False
    assert loader.is_ready() is False

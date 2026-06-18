import ssl
ssl._create_default_https_context = ssl._create_unverified_context

import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader, TensorDataset
from sklearn.datasets import fetch_covtype
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
import os
import time

# 1. Define the simple PyTorch MLP matching the Covtype features (54) and classes (7)
class CovtypeMLP(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(54, 128),
            nn.ReLU(),
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 7)
        )

    def forward(self, x):
        # The inference.proto expects probabilities, so we output softmax if not using CrossEntropyLoss internally,
        # but for PyTorch training, we output raw logits and use CrossEntropyLoss.
        # We will add the Softmax layer right before exporting to ONNX.
        return self.net(x)

def main():
    print("Fetching Forest Covertype dataset (this may take a few seconds on first run)...")
    data = fetch_covtype()
    X = data.data
    # Covtype targets are 1-7. PyTorch CrossEntropyLoss expects 0-6.
    y = data.target - 1 

    print(f"Dataset loaded. Shape: {X.shape}. Splitting and scaling...")
    
    # We use a subset (e.g. 100k samples) to keep the training super light and fast
    # while still having enough data to learn something real.
    subset_size = 100000
    if X.shape[0] > subset_size:
        X, _, y, _ = train_test_split(X, y, train_size=subset_size, stratify=y, random_state=42)

    X_train, X_val, y_train, y_val = train_test_split(X, y, test_size=0.2, random_state=42)

    scaler = StandardScaler()
    X_train = scaler.fit_transform(X_train)
    X_val = scaler.transform(X_val)

    # Convert to PyTorch tensors
    X_train_t = torch.tensor(X_train, dtype=torch.float32)
    y_train_t = torch.tensor(y_train, dtype=torch.long)
    X_val_t = torch.tensor(X_val, dtype=torch.float32)
    y_val_t = torch.tensor(y_val, dtype=torch.long)

    train_loader = DataLoader(TensorDataset(X_train_t, y_train_t), batch_size=1024, shuffle=True)
    val_loader = DataLoader(TensorDataset(X_val_t, y_val_t), batch_size=1024, shuffle=False)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Training on device: {device}")

    model = CovtypeMLP().to(device)
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=0.01)

    epochs = 5
    print("Starting training...")
    for epoch in range(epochs):
        model.train()
        start_time = time.time()
        train_loss = 0.0
        
        for inputs, targets in train_loader:
            inputs, targets = inputs.to(device), targets.to(device)
            optimizer.zero_grad()
            outputs = model(inputs)
            loss = criterion(outputs, targets)
            loss.backward()
            optimizer.step()
            train_loss += loss.item() * inputs.size(0)
            
        train_loss /= len(train_loader.dataset)
        
        model.eval()
        val_loss = 0.0
        correct = 0
        with torch.no_grad():
            for inputs, targets in val_loader:
                inputs, targets = inputs.to(device), targets.to(device)
                outputs = model(inputs)
                loss = criterion(outputs, targets)
                val_loss += loss.item() * inputs.size(0)
                _, predicted = torch.max(outputs, 1)
                correct += (predicted == targets).sum().item()
                
        val_loss /= len(val_loader.dataset)
        accuracy = 100.0 * correct / len(val_loader.dataset)
        elapsed = time.time() - start_time
        print(f"Epoch {epoch+1}/{epochs} | Train Loss: {train_loss:.4f} | Val Loss: {val_loss:.4f} | Val Acc: {accuracy:.2f}% | Time: {elapsed:.2f}s")

    print("\nTraining complete. Preparing for ONNX export...")
    
    # Create the final model that includes Softmax, as our inference gateway expects class probabilities.
    class InferenceModel(nn.Module):
        def __init__(self, base_model):
            super().__init__()
            self.base_model = base_model
            self.softmax = nn.Softmax(dim=1)
            
        def forward(self, x):
            return self.softmax(self.base_model(x))
            
    inference_model = InferenceModel(model).to("cpu").eval()
    
    # Create models directory if it doesn't exist
    os.makedirs("../models", exist_ok=True)
    onnx_path = "../models/covtype_mlp.onnx"
    
    # Export the model
    dummy_input = torch.randn(1, 54, dtype=torch.float32)
    torch.onnx.export(
        inference_model,
        dummy_input,
        onnx_path,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes={'input': {0: 'batch_size'}, 'output': {0: 'batch_size'}}
    )
    
    print(f"Model successfully exported to {onnx_path}")
    
    # Verify the exported model
    import onnx
    onnx_model = onnx.load(onnx_path)
    onnx.checker.check_model(onnx_model)
    print("ONNX model verified successfully!")

if __name__ == "__main__":
    main()

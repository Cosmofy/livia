#!/bin/bash

# Update system and install Python 3.11
sudo apt update
sudo apt install -y python3.11 python3.11-venv python3.11-dev
sudo apt install -y python3-pip

# Create and activate virtual environment
python3.11 -m venv venv
source venv/bin/activate

# Install dependencies
pip install requests

# Download and run the test file
curl -o test.py https://raw.githubusercontent.com/Cosmofy/livia/main/research/test.py
python test.py
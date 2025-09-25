#!/bin/bash

# Update system and install Python 3.13
sudo apt update
sudo apt install -y python3-full python3-venv python3-dev
sudo apt install -y python3-pip

# Create and activate virtual environment using python3 (which is 3.13)
python3 -m venv venv
source venv/bin/activate

# Install dependencies
pip install requests

# Download and run the test file
curl -o test.py https://raw.githubusercontent.com/Cosmofy/livia/main/research/test.py
python test.py
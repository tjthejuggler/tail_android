#!/bin/bash
# Setup script for Garmin Proxy
# Creates the virtual environment and installs Python dependencies.

set -e

echo "Setting up Garmin Proxy..."

# Check if venv exists, if not create it
if [ ! -d "venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv venv
fi

# Activate virtual environment
echo "Activating virtual environment..."
. venv/bin/activate

# Install Python dependencies
echo "Installing Python dependencies..."
pip install -r requirements.txt

echo ""
echo "Setup complete!"
echo ""
echo "Next steps:"
echo "1. Set your environment variables:"
echo "   export GARMIN_EMAIL=your.garmin.email@example.com"
echo "   export GARMIN_PASSWORD=your_garmin_password"
echo "   export ANDROID_PROXY_KEY=your_secure_secret_token_here"
echo ""
echo "2. Run the authentication bridge ONCE to generate OAuth tokens:"
echo "   . venv/bin/activate && python3 auth_bridge.py"
echo "   (Tokens are saved to ~/.garminconnect and reused; no repeated logins.)"
echo ""
echo "3. Fetch initial data:"
echo "   . venv/bin/activate && python3 fetch_data.py"
echo ""
echo "4. Start the API server:"
echo "   . venv/bin/activate && uvicorn app:app --host 0.0.0.0 --port 8000"
echo ""
echo "For automated data refresh, add this to your crontab:"
echo "*/30 * * * * cd $(pwd) && . venv/bin/activate && python3 fetch_data.py >> fetch.log 2>&1"
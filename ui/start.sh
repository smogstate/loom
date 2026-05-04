#!/usr/bin/env bash
# Start the Loom KG Traversal UI server
# Uses locally installed packages (no system pip required)
#
# First-time setup (React app):
#   cd ui/react-app && npm install && npm run build
#
# Then run this script to start the server on http://localhost:8765

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGES="$SCRIPT_DIR/.packages"

export PYTHONPATH="$PACKAGES:$PYTHONPATH"

# Auto-build React app if dist/ is missing
if [ ! -d "$SCRIPT_DIR/dist" ]; then
  echo "Building React app (first run)…"
  cd "$SCRIPT_DIR/react-app" && npm install && npm run build && cd "$SCRIPT_DIR"
fi

echo "Starting Loom KG Traversal UI on http://localhost:8765"
echo ""

python3 -c "
import sys
sys.path.insert(0, '$PACKAGES')
import uvicorn
uvicorn.run('server:app', host='0.0.0.0', port=8765, reload=False)
" 

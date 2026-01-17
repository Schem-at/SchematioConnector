#!/bin/bash
# Reload the SchematioConnector plugin on the dev server
# Called automatically by gradle deploy task

SESSION_NAME="minecraft-dev"
PLUGIN_NAME="SchematioConnector"

# Check if session exists
if ! tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
    echo "⚠️  Server not running (session '$SESSION_NAME' not found)"
    echo "   Start it with: ./scripts/start-server.sh"
    exit 0  # Don't fail the build
fi

echo "🔄 Reloading $PLUGIN_NAME..."

# Notify players
tmux send-keys -t "$SESSION_NAME" "say §e[Deploy] §fReloading $PLUGIN_NAME..." Enter

# Small delay to let the file system settle
sleep 0.5

# Reload the plugin
tmux send-keys -t "$SESSION_NAME" "plugman reload $PLUGIN_NAME" Enter

# Wait a moment then notify success
sleep 1
tmux send-keys -t "$SESSION_NAME" "say §a[Deploy] §f$PLUGIN_NAME reloaded!" Enter

echo "✅ Reload command sent!"

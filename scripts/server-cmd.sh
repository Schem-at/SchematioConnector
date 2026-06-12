#!/bin/bash
# Send a command to the Minecraft server running in tmux
# Usage: ./server-cmd.sh <command>
# Example: ./server-cmd.sh "say Hello World"

SESSION_NAME="minecraft-dev"

if [ -z "$1" ]; then
    echo "Usage: $0 <command>"
    echo "Example: $0 'say Hello from script!'"
    exit 1
fi

# Check if session exists
if ! tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
    echo "❌ Server session '$SESSION_NAME' not found."
    echo "   Start it with: ./scripts/start-server.sh"
    exit 1
fi

# Send the command to the tmux session
tmux send-keys -t "$SESSION_NAME" "$1" Enter

echo "✅ Sent: $1"

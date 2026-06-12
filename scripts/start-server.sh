#!/bin/bash
# Start the Minecraft dev server in a tmux session
# Usage: ./start-server.sh

SERVER_DIR="$HOME/Desktop/mc_dev_server_1.21.8"
SESSION_NAME="minecraft-dev"
JAR_NAME="purpur-1.21.8-2497.jar"

# Check if tmux is installed
if ! command -v tmux &> /dev/null; then
    echo "❌ tmux is not installed. Install it with: brew install tmux"
    exit 1
fi

# Check if session already exists
if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
    echo "⚠️  Session '$SESSION_NAME' already exists."
    echo "   To attach: tmux attach -t $SESSION_NAME"
    echo "   To kill:   tmux kill-session -t $SESSION_NAME"
    exit 0
fi

# Check if server directory exists
if [ ! -d "$SERVER_DIR" ]; then
    echo "❌ Server directory not found: $SERVER_DIR"
    exit 1
fi

# Create new tmux session and start server
echo "🚀 Starting Minecraft server in tmux session '$SESSION_NAME'..."
tmux new-session -d -s "$SESSION_NAME" -c "$SERVER_DIR" "java -Xms1G -Xmx2G -jar $JAR_NAME nogui"

echo "✅ Server started!"
echo ""
echo "📋 Commands:"
echo "   Attach to console:  tmux attach -t $SESSION_NAME"
echo "   Detach from console: Ctrl+B, then D"
echo "   Send command:       ./scripts/server-cmd.sh <command>"
echo "   Stop server:        ./scripts/server-cmd.sh stop"
echo ""

#!/usr/bin/env bash
# Voice in the browser: starts the voice server on ws://127.0.0.1:8082/voice for the demo screen
# (demo-ui, "Voix" tab: http://127.0.0.1:8090, tab 3).
# The agent runs in this process; the Knowledge Base and the guardrail are the demo 1 ones.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEMO1_OUTPUTS="${DEMO1_OUTPUTS:-$ROOT/../bedrock-managed-kb-rag-java/.deploy/outputs.sh}"
if [ -f "$DEMO1_OUTPUTS" ]; then
  set +u; source "$DEMO1_OUTPUTS"; set -u
fi
: "${KNOWLEDGE_BASE_ID:?Set KNOWLEDGE_BASE_ID or deploy demo 1 (bedrock-managed-kb-rag-java)}"
: "${GUARDRAIL_ID:?Set GUARDRAIL_ID or deploy demo 1}"
: "${GUARDRAIL_VERSION:?Set GUARDRAIL_VERSION or deploy demo 1}"
export KNOWLEDGE_BASE_ID GUARDRAIL_ID GUARDRAIL_VERSION

JAR="$ROOT/target/agentcore-managed-kb-voice-agent.jar"
[ -f "$JAR" ] || mvn -q -f "$ROOT/pom.xml" package -DskipTests
echo "Voice server on ws://127.0.0.1:${VOICE_WEB_PORT:-8082}/voice. Demo screen: http://127.0.0.1:8090 (tab 3)"
exec java -jar "$JAR" --spring.profiles.active=voice-web "$@"

#!/usr/bin/env bash
# Talks to the technician agent with your voice (Nova 2 Sonic), from the command line.
# The agent runs locally in the same process; the Knowledge Base and the guardrail are the
# demo 1 ones (bedrock-managed-kb-rag-java), read from its .deploy/outputs.sh.
#
# Usage: ./scripts/voice.sh [INT-2026-0412]                     microphone, answer on the speakers
#        ./scripts/voice.sh INT-2026-0412 --input=q1.wav,q2.wav  recorded questions, answers in answers.wav
# Use a headset with the microphone: otherwise the assistant hears itself.
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

INTERVENTION=""
if [ $# -gt 0 ] && [[ "$1" != --* ]]; then INTERVENTION="$1"; shift; fi

JAR="$ROOT/target/agentcore-managed-kb-voice-agent.jar"
[ -f "$JAR" ] || mvn -q -f "$ROOT/pom.xml" package -DskipTests
exec java -jar "$JAR" --spring.profiles.active=voice --intervention="$INTERVENTION" "$@"

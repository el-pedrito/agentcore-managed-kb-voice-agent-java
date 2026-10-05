#!/usr/bin/env bash
# Records two test questions with Amazon Polly (French neural voice), as raw 16 kHz PCM:
# a repeatable way to test the voice agent without a microphone.
# Usage: ./scripts/make-test-audio.sh [output-dir]
#        ./scripts/voice.sh INT-2026-0412 --input=test-audio/q1.pcm,test-audio/q2.pcm
set -euo pipefail
OUT="${1:-test-audio}"
mkdir -p "$OUT"
synth() {
  aws polly synthesize-speech --region "${POLLY_REGION:-eu-west-1}" --engine neural --voice-id Remi \
    --language-code fr-FR --output-format pcm --sample-rate 16000 --text "$2" "$OUT/$1.pcm" > /dev/null
  echo "$OUT/$1.pcm"
}
synth q1 "Bonjour, j'ai un code défaut F28 sur la chaudière, qu'est-ce que je dois faire ?"
synth q2 "D'accord. Et le vase d'expansion, il est en stock ?"

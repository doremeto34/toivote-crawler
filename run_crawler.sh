#!/bin/bash
# run_crawler.sh
# Runs the movie crawler JAR every 5 seconds indefinitely.
# Usage: bash run_crawler.sh

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# Auto-detect Java in common container paths if not in PATH
if ! command -v java &> /dev/null; then
    if [ -x "/opt/java/openjdk/bin/java" ]; then
        export JAVA_HOME="/opt/java/openjdk"
        export PATH="$JAVA_HOME/bin:$PATH"
    elif [ -d "/opt/java" ]; then
        FOUND_JAVA=$(find /opt/java -name java -type f -executable | head -n 1)
        if [ -n "$FOUND_JAVA" ]; then
            export PATH="$(dirname "$FOUND_JAVA"):$PATH"
        fi
    fi
fi

JAR_FILE="crawl.jar"
LOG_FILE="crawler.log"

echo "========================================"
echo " ToiVote Movie Crawler – Auto Runner"
echo " Java : $(java -version 2>&1 | head -n 1)"
echo " JAR  : $JAR_FILE"
echo " Log  : $LOG_FILE"
echo " Press Ctrl+C to stop."
echo "========================================"

while true; do
    echo ""
    echo "--- $(date '+%Y-%m-%d %H:%M:%S') : Starting crawl run ---" | tee -a "$LOG_FILE"
    java -Dfile.encoding=UTF-8 -jar "$JAR_FILE" 2>&1 | tee -a "$LOG_FILE"
    echo "--- Run finished. Waiting 5 seconds... ---" | tee -a "$LOG_FILE"
    sleep 5
done

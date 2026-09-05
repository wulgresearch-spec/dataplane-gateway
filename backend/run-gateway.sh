#!/usr/bin/env bash
# Builds the data plane and runs one node in the foreground.
#
#   GATEWAY_PROVIDER_API_KEY=sk-... \
#   GATEWAY_PROVIDER_BASE_URI=https://api.example.com \
#   GATEWAY_MODEL=some-model \
#   GATEWAY_API_KEY=choose-a-secret \
#   ./run-gateway.sh
#
# Run with no environment at all to see the full list of settings and their defaults.
# Set REBUILD=1 to force a rebuild after changing code.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app="$here/dataplane/gateway-dp-app"
mvn="${MAVEN:-mvn}"

if [ ! -f "$app/cp.txt" ] || [ "${REBUILD:-0}" = "1" ]; then
  echo "building..."
  "$mvn" -o -q -f "$here/pom.xml" -pl :gateway-dp-app -am install \
    -DskipTests -Dspotbugs.skip=true -Dforbiddenapis.skip=true \
    -Djacoco.skip=true -Dcheckstyle.skip=true -Dspotless.check.skip=true
  "$mvn" -o -q -f "$app/pom.xml" dependency:build-classpath \
    -Dmdep.outputFile=cp.txt -Dmdep.includeScope=runtime
fi

# Windows JVMs split the classpath on ';' even under Git Bash; everything else uses ':'.
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) sep=';' ;;
  *) sep=':' ;;
esac

exec java -cp "$app/target/classes${sep}$(cat "$app/cp.txt")" \
  io.reliabilityai.gateway.dataplane.app.launch.GatewayMain

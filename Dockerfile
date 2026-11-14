# Single-tenant DEVELOPMENT PILOT only. Do not commit API credentials.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/ ./backend/
# CI runs its gates separately; this stage packages the deployable app.
RUN mvn -B -ntp -f backend/pom.xml -pl :gateway-dp-app -am install \
      -DskipTests -Dspotbugs.skip=true -Dforbiddenapis.skip=true \
      -Djacoco.skip=true -Dcheckstyle.skip=true -Dspotless.check.skip=true
RUN mkdir -p /dist/lib && \
    mvn -B -ntp -f backend/dataplane/gateway-dp-app/pom.xml \
      org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies \
      -DincludeScope=runtime -Dmdep.prependGroupId=true \
      -DoutputDirectory=/dist/lib

FROM eclipse-temurin:21-jre-jammy
WORKDIR /opt/gateway
COPY --from=build /src/backend/dataplane/gateway-dp-app/target/classes/ /opt/gateway/classes/
COPY --from=build /dist/lib/ /opt/gateway/lib/
ENV GATEWAY_HOST=0.0.0.0 \
    GATEWAY_DATA_DIR=/tmp/gateway-data \
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 10000
# Render sets PORT; Java entry point expects GATEWAY_PORT.
CMD ["/bin/sh", "-c", "export GATEWAY_PORT=$PORT; exec java -cp '/opt/gateway/classes:/opt/gateway/lib/*' io.reliabilityai.gateway.dataplane.app.launch.GatewayMain"]

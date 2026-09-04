FROM gradle:9.3.0-jdk21 AS build

WORKDIR /workspace
COPY --chown=gradle:gradle . .

RUN ./gradlew -q extendedAgent \
    && mkdir -p /out/extensions \
    && cp build/otel/opentelemetry-javaagent.jar /out/javaagent.jar \
    && cp build/libs/*-all.jar /out/extensions/otel-custom-agent.jar

FROM busybox:1.37

COPY --from=build /out/javaagent.jar /javaagent.jar
COPY --from=build /out/extensions/ /extensions/

RUN chmod go+r /javaagent.jar /extensions/*.jar

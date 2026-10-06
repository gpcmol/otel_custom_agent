FROM gradle:9.3.1-jdk21 AS build

WORKDIR /workspace
COPY --chown=gradle:gradle . .

RUN ./gradlew -q shadowJar \
    && mkdir -p /out/extensions \
    && cp build/libs/*-all.jar /out/extensions/otel-custom-agent.jar

FROM busybox:1.38

COPY --from=build /out/extensions/ /extensions/

RUN chmod go+r /extensions/*.jar

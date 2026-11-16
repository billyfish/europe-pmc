# Must be declared before the first FROM so it can be used in a FROM line.
ARG TOMCAT_TAG=11.0-jre21-temurin

# ---- Build stage: compile and package the WAR with Maven ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B package

# ---- Runtime stage: Apache Tomcat 11 on Eclipse Temurin JRE 21 ----
FROM tomcat:${TOMCAT_TAG}

ENV CATALINA_HOME=/usr/local/tomcat \
    CATALINA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom" \
    TZ=UTC

# curl for the healthcheck; create an unprivileged user to run Tomcat
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 tomcat \
 && useradd --system --uid 1001 --gid tomcat --home-dir "$CATALINA_HOME" --shell /usr/sbin/nologin tomcat \
 && rm -rf "$CATALINA_HOME"/webapps/*

# Optional config overrides (anything placed in ./conf is copied over the defaults)
COPY conf/ ${CATALINA_HOME}/conf/

# Deploy the app at the root context: http://host:8080/search?...
COPY --from=build /build/target/pmc.war ${CATALINA_HOME}/webapps/ROOT.war

RUN chown -R tomcat:tomcat "$CATALINA_HOME"

USER tomcat
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8080/ >/dev/null || exit 1

CMD ["catalina.sh", "run"]

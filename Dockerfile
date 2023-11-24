# syntax=docker/dockerfile:1
#FROM eclipse-temurin:17-jdk-jammy
#FROM maven:3.9.5-eclipse-temurin-17-alpine as maven
# FROM maven:3-eclipse-temurin-17 as maven

FROM docker:24.0-dind as dind

#COPY --from=maven /usr/share/maven /usr/share/maven
#COPY --from=maven /usr/bin/mvn /usr/bin/mvn
#COPY --from=maven /opt/java/openjdk /opt/java/openjdk
#COPY --from=maven /usr/local/bin/mvn-entrypoint.sh /usr/local/bin/mvn-entrypoint.sh

#RUN apt-get update && apt-get install -y \
RUN apk add --no-cache \
    bash \
    coreutils \
    curl \
    git \
    helm \
    make \
    maven \
    openjdk17 \
    sudo \
    shellcheck \
    wget \
    yq \
    zip

RUN VERSION=2.1.20 \
    OS=linux  # or "darwin" for OSX, "windows" for Windows. \
    ARCH=amd64  # or "386" for 32-bit OSs \
    curl -fsSL "https://github.com/GoogleCloudPlatform/docker-credential-gcr/releases/download/v${VERSION}/docker-credential-gcr_${OS}_${ARCH}-${VERSION}.tar.gz" \
    | tar xz docker-credential-gcr \
    && chmod +x docker-credential-gcr && sudo mv docker-credential-gcr /usr/bin/ \
    && docker-credential-gcr configure-docker --registries=europe-west1-docker.pkg.dev


ENV MVN_ARGS="-DskipTests -X" \
    DOCKER_REGISTRY=europe-west1-docker.pkg.dev/vivacity-infrastructure \
    DOCKER_ORG=kafka-strimzi

    #PATH=/opt/java/openjdk/bin:${PATH} \
    #MAVEN_HOME=/usr/share/maven \
    #JAVA_VERSION=jdk-17.0.9+9 \
    #JAVA_HOME=/opt/java/openjdk \
    #CLASSWORLDS_JAR=/usr/share/maven/boot/plexus-classworlds-2.7.0.jar \
    #M2_HOME=/usr/share/maven \
    #M3_HOME=/usr/share/maven


#    M3_HOME=/usr/share/maven \
#    M2_HOME=/usr/share/maven \

WORKDIR /var/tmp/strimzi

COPY . .

#RUN ls -al /opt/java/openjdk/bin
#RUN pwd ; ls -al ; which java ; ls -al /opt/java/openjdk/bin/java ; /opt/java/openjdk/bin/java --version
#bin/java ; /opt/java/openjdk/bin/java --version

#RUN make all


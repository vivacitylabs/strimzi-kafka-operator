# syntax=docker/dockerfile:1
#FROM eclipse-temurin:17-jdk-jammy
#FROM maven:3.9.5-eclipse-temurin-17-alpine as maven
# FROM maven:3-eclipse-temurin-17 as maven

FROM docker:24.0-dind-rootless as dind
#FROM docker:24.0-dind as dind

USER root

#COPY --from=maven /usr/share/maven /usr/share/maven
#COPY --from=maven /usr/bin/mvn /usr/bin/mvn
#COPY --from=maven /opt/java/openjdk /opt/java/openjdk
#COPY --from=maven /usr/local/bin/mvn-entrypoint.sh /usr/local/bin/mvn-entrypoint.sh

#RUN apt-get update && apt-get install -y \
RUN apk add --no-cache \
    bash \
    coreutils \
    curl \
    findutils \
    git \
    helm \
    make \
    maven \
    openjdk17 \
    python3 \
    sudo \
    shadow \
    shellcheck \
    wget \
    yq \
    zip

ENV DOCKER_CREDENTIAL_GCR_VERSION=2.1.20 \
    DOCKER_CREDENTIAL_GCR_OS=linux \
    DOCKER_CREDENTIAL_GCR_ARCH=amd64

RUN curl -fsSL "https://github.com/GoogleCloudPlatform/docker-credential-gcr/releases/download/v${DOCKER_CREDENTIAL_GCR_VERSION}/docker-credential-gcr_${DOCKER_CREDENTIAL_GCR_OS}_${DOCKER_CREDENTIAL_GCR_ARCH}-${DOCKER_CREDENTIAL_GCR_VERSION}.tar.gz" \
    | tar xz docker-credential-gcr \
    && chmod +x docker-credential-gcr && sudo mv docker-credential-gcr /usr/bin/ \
    && docker-credential-gcr configure-docker --registries=europe-west1-docker.pkg.dev

ARG UID=1001
ARG GID=999
ARG WORKDIR=/run/strimzi

ENV MVN_ARGS="-Duser.home=${WORKDIR} -DskipTests -X" \
    HOME=${WORKDIR} \
    DOCKER_REGISTRY=europe-west1-docker.pkg.dev/vivacity-infrastructure \
    DOCKER_ORG=kafka-strimzi \
    USERNAME=strimzi

    #PATH=/opt/java/openjdk/bin:${PATH} \
    #MAVEN_HOME=/usr/share/maven \
    #JAVA_VERSION=jdk-17.0.9+9 \
    #JAVA_HOME=/opt/java/openjdk \
    #CLASSWORLDS_JAR=/usr/share/maven/boot/plexus-classworlds-2.7.0.jar \
    #M2_HOME=/usr/share/maven \
    #M3_HOME=/usr/share/maven


#    M3_HOME=/usr/share/maven \
#    M2_HOME=/usr/share/maven \

WORKDIR ${WORKDIR}

RUN mkdir -p ${WORKDIR} && \
    chown ${UID}:${GID} ${WORKDIR} && \
#    addgroup docker && \
#    addgroup -g ${GID} ${USERNAME} && \
    groupadd -for -g 999 docker && \
    groupadd -for -g ${GID} ${USERNAME} && \
    useradd -u ${UID} -g ${GID} ${USERNAME} && \
    usermod -aG docker ${USERNAME} && \
    addgroup ${USERNAME} docker
#    gpasswd -a ${USERNAME} docker

USER ${UID}:${GID}

# Install gcloud CLI
RUN curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-455.0.0-linux-x86_64.tar.gz | tar xz && \
    ./google-cloud-sdk/install.sh && \
    mv ./google-cloud-sdk/bin/gcloud /usr/bin

COPY --chown=${UID}:${GID} . .


#RUN ls -al /opt/java/openjdk/bin
#RUN pwd ; ls -al ; which java ; ls -al /opt/java/openjdk/bin/java ; /opt/java/openjdk/bin/java --version
#bin/java ; /opt/java/openjdk/bin/java --version

#RUN make all


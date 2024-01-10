# syntax=docker/dockerfile:1
FROM docker:24.0-dind-rootless as dind

USER root

RUN apk add --no-cache \
    bash \
    coreutils \
    curl \
    findutils \
    git \
    grep \
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

ARG UID=1001
ARG GID=999
ARG WORKDIR=/run/strimzi

ENV MVN_ARGS="-Duser.home=${WORKDIR} -DskipTests -X" \
    HOME=${WORKDIR} \
    DOCKER_REGISTRY=europe-west1-docker.pkg.dev/vivacity-infrastructure \
    DOCKER_ORG=kafka-strimzi \
    USERNAME=strimzi

WORKDIR ${WORKDIR}

RUN mkdir -p ${WORKDIR} && \
    chown ${UID}:${GID} ${WORKDIR} && \
    groupadd -for -g 999 docker && \
    groupadd -for -g ${GID} ${USERNAME} && \
    useradd -u ${UID} -g ${GID} ${USERNAME} && \
    usermod -aG docker ${USERNAME} && \
    addgroup ${USERNAME} docker

USER ${UID}:${GID}

# Install gcloud CLI
RUN curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-455.0.0-linux-x86_64.tar.gz | tar xz && \
    ./google-cloud-sdk/bin/gcloud config set project vivacity-infrastructure && \
    ./google-cloud-sdk/bin/gcloud config set artifacts/repository kafka-strimzi && \
    ./google-cloud-sdk/bin/gcloud config set artifacts/location europe-west1

ENV PATH=${WORKDIR}/google-cloud-sdk/bin:$PATH

COPY --chown=${UID}:${GID} . .

#CMD ["make", "all"]


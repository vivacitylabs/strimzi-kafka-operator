# syntax=docker/dockerfile:1
#FROM eclipse-temurin:17-jdk-jammy
FROM maven:3-eclipse-temurin-17

#COPY .azure/scripts scripts
RUN mkdir /strimzi
WORKDIR /strimzi
COPY . .


# Install
#- yq
#- helm v3.12.0
#- shellcheck
#- java 17 \
RUN apt-get update && apt-get install -y \
        apt-transport-https \
        curl \
        make \
        sudo \
        shellcheck \
        wget \
        zip
##    curl https://baltocdn.com/helm/signing.asc | gpg --dearmor | tee /usr/share/keyrings/helm.gpg > /dev/null && \
##    echo "deb [arch=$(dpkg --.print-architecture) signed-by=/usr/share/keyrings/helm.gpg] https://baltocdn.com/helm/stable/debian/ all main" | tee /etc/apt/sources.list.d/helm-stable-debian.list && \
##    apt-get update && apt-get install -y helm && \
##        rm -rf /var/lib/apt/lists/* && \
#    wget https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 -O /usr/bin/yq && \
#        chmod +x /usr/bin/yq

RUN ./.azure/scripts/install_yq.sh
ENV TEST_CLUSTER=minikube \
    TEST_KUBECTL_VERSION=v1.21.0 \
    TEST_MINIKUBE_VERSION=v1.24.0

#RUN apt-get update && \
#    apt-get install -y ca-certificates curl gnupg && \
#    install -m 0755 -d /etc/apt/keyrings && \
#    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
#    chmod a+r /etc/apt/keyrings/docker.gpg && \
#    echo \
#      "deb [arch="$(dpkg --print-architecture)" signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
#      "$(. /etc/os-release && echo "$VERSION_CODENAME")" stable" | \
#      tee /etc/apt/sources.list.d/docker.list > /dev/null && \
#    apt-get update && \
#    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin && \
#RUN scripts/setup-kubernetes.sh

ENV TEST_HELM3_VERSION='v3.12.0'
RUN ./.azure/scripts/setup-helm.sh
#RUN scripts/setup_shellcheck.sh


ENV MAVEN_OPTS='-Xmx8192m'
ENV MVN_ARGS="-DskipTests -e -V -B -X"

#ENV JAVA_HOME /usr/lib/jvm/jre-17
# RUN apt-get update && apt-get install -y mvn

RUN make java_install

ENV MVN_ARGS="-e -V -B -X"

RUN make spotbugs && \
    make dashboard_install && \
    make helm_install && \
    make crd_install && \
    make docu_versions && \
    make docu_check && \
    make shellcheck && \
    make release_files_check

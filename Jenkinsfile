pipeline {
    agent {
        dockerfile {
            label 'cross-compiler'
            additionalBuildArgs '--build-arg GID=999 --build-arg WORKDIR=${WORKSPACE}'
            args '-v /var/run/docker.sock:/var/run/docker.sock --privileged --user 1001:999'
        }
    }
    environment {
        HOME = "${WORKSPACE}"
        WORKDIR = "${WORKSPACE}"
    }

    stages {
//         stage('Debug') {
//             steps {
//                 sh 'env'
//                 sh 'pwd'
//                 sh 'id'
//             }
//         }
        stage('Build and push') {
            steps {
                sh 'curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-455.0.0-linux-x86_64.tar.gz | tar xz && ./google-cloud-sdk/bin/gcloud config set project vivacity-infrastructure && ./google-cloud-sdk/bin/gcloud config set artifacts/repository kafka-strimzi && ./google-cloud-sdk/bin/gcloud config set artifacts/location europe-west1 && PATH=${WORKDIR}/google-cloud-sdk/bin:$PATH'
                withGCP("atrocity-gar-pusher") {
                    sh 'gcloud auth print-access-token | docker login -u oauth2accesstoken --password-stdin https://europe-west1-docker.pkg.dev'
                    sh 'make all'
                }
            }
        }
        stage('Re-tag and push') {
            when {
              expression {
                currentBuild.result == null || currentBuild.result == 'SUCCESS'
              }
            }
            steps {
                old_tag = "${env.DOCKER_REGISTRY}/${env.DOCKER_ORG}/operator:latest"
                new_tag = "${env.DOCKER_REGISTRY}/${env.DOCKER_ORG}/operator:${env.BRANCH_NAME}"
                sh "docker tag ${old_tag} ${new_tag}"
                withGCP("atrocity-gar-pusher") {
                    sh "docker push ${new_tag}"
                }
            }
        }
    }
}

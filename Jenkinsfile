pipeline {
    agent {
        dockerfile {
            label 'cross-compiler'
            additionalBuildArgs '--build-arg GID=999 --build-arg WORKDIR=${WORKSPACE}'
            args '-v /var/run/docker.sock:/var/run/docker.sock --privileged --user 1001:999'
        }
    }
    parameters {
        string(name: 'DOCKER_REGISTRY', defaultValue: 'europe-west1-docker.pkg.dev/vivacity-infrastructure', description: 'Docker registry to push images to')
        string(name: 'DOCKER_ORG', defaultValue: 'kafka-strimzi', description: 'Docker repository to push images to')
        string(name: 'DOCKER_TAGS', defaultValue: 'latest', description: 'List (e.g. a,b,c) of strings to tag/push operator image to')
    }
    environment {
        HOME = "${WORKSPACE}"
        WORKDIR = "${WORKSPACE}"
        DOCKER_REGISTRY = "${params.DOCKER_REGISTRY}"
        DOCKER_ORG = "${params.DOCKER_ORG}"
    }

    stages {
        stage('Parallel build and push operator:latest image') {
            parallel {
                stage('Debug') {
                    steps {

                        sh 'env'
                        sh 'pwd'
                        sh 'id'
                        script {
                            echo "TimeStamp: ${currentBuild.startTimeInMillis}"

                            def now = new Date()
                            println now.format("yyMMdd.HHmm", TimeZone.getTimeZone('UTC'))
                        }
                    }
                }
                stage('Generate tags') {
                    steps {
                        script {
                            def now = new Date()
                            def default_tags = [
                                env.BRANCH_NAME,
                                env.GIT_COMMIT,
                                currentBuild.startTimeInMillis,
                                now.format("yyMMdd.HHmm", TimeZone.getTimeZone('UTC')),
                            ]
                            def tags = params.DOCKER_TAGS.tokenize(',') + default_tags
                        }
                    }
                }
                stage('Build and push') {
                    steps {
                        sh 'curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-455.0.0-linux-x86_64.tar.gz | tar xz && ./google-cloud-sdk/bin/gcloud config set project vivacity-infrastructure && ./google-cloud-sdk/bin/gcloud config set artifacts/repository kafka-strimzi && ./google-cloud-sdk/bin/gcloud config set artifacts/location europe-west1 && PATH=${WORKDIR}/google-cloud-sdk/bin:$PATH'
                        withGCP("atrocity-gar-pusher") {
                            sh 'gcloud auth print-access-token | docker login -u oauth2accesstoken --password-stdin https://europe-west1-docker.pkg.dev'
                            sh 'make all'
                        }
                    }
                }
            }
        }
        stage('Push extra tags') {
            when {
              expression {
                currentBuild.result == null || currentBuild.result == 'SUCCESS'
              }
            }
            steps {
                script {
                    def original_tag = "${env.DOCKER_REGISTRY}/${env.DOCKER_ORG}/operator:latest"
                    tags.each{ tag ->
                        full_tag = "${env.DOCKER_REGISTRY}/${env.DOCKER_ORG}/operator:${tag}"
                        sh "docker tag ${original_tag} ${full_tag}"
                        withGCP("atrocity-gar-pusher") {
                            sh "docker push ${full_tag}"
                        }
                    }
                }
            }
        }
    }
}

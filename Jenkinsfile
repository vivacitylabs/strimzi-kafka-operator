properties([
    parameters([
        string(name: 'timeout', defaultValue: '240', description: 'Overall tests timeout (in minutes)'),
        string(name: 'timeout_node', defaultValue: '120', description: 'Per-node test timeout (in minutes)'),
        string(name: 'timeout_docker_save', defaultValue: '30', description: 'Docker save timeout (in minutes)'),
        string(name: 'numOfTesters', defaultValue: '4', description: 'Number of parallel executors for regular tests'),
        string(name: 'projectName', defaultValue: '', description: 'Name of project to build customized monobin and bundles'),
        booleanParam(name: 'runAllTests', defaultValue: env.BRANCH_NAME == "master", description: 'When checked, all tests will be built and tested'),
        booleanParam(name: 'pushImages', defaultValue: env.BRANCH_NAME == "master", description: 'When checked, the built images will be pushed to GCR'),
        booleanParam(name: 'publishRelease', defaultValue: env.BRANCH_NAME == "master", description: 'When checked, the commit will be tagged and built binaries will be published to GitHub'),
        booleanParam(name: 'pushBuildImage', defaultValue: false, description: 'When checked, the built supermario `build` image will be pushed to GCR (dev usage)'),
        booleanParam(name: 'ignoreTests', defaultValue: false, description: 'When checked, no tests will be run (dev usage)')
    ]),
    [$class: 'BuildDiscarderProperty',
        strategy: [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '60', numToKeepStr: '']
    ]
])

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
//         PATH = "${WORKSPACE}/google-cloud-sdk/bin:${PATH}"

//         JAVA_HOME = "/usr/lib/jvm/java-17-openjdk"
//         MAVEN_HOME = "/usr/share/java/maven-3"
//         MVN_HOME = "/usr/share/java/maven-3"
    }

    stages {
//         stage('Debug') {
//             steps {
// //                 sh 'mvn --version'
//                 sh 'env'
//                 sh 'pwd'
//                 sh 'ls -alphs1 || exit 0'
// //                 sh 'ls -al /root/.m2 || exit 0'
// //                 sh 'ls -al $HOME/.m2 || exit 0'
// //                 sh 'cat /usr/share/java/maven-3/bin/m2.conf || exit 0'
// //                 sh 'cat /usr/share/maven/conf/settings.xml || exit 0'
// //                 sh 'cat /root/.m2/settings-docker.xml || exit 0'
// //                 sh 'cat /root/.m2/settings-docker.xml || exit 0'
// //                 sh 'find / -name \'*.xml\' || exit 0'
// //                 sh 'find / -name \'*pom.xml\' || exit 0'
// //                 sh 'find / -name \'*settings.xml\' || exit 0'
// //                 sh 'apk list -I || exit 0'
//                 sh 'id'
// //                 sh 'make all'
//             }
//         }
//         stage('Maven install') {
//             steps {
//                 sh 'export MAVEN_HOME=/usr/share/maven ; mvn -DskipTests -X clean install'
//             }
//         }
        stage('Build and push') {
            steps {
//                 sh 'curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-455.0.0-linux-x86_64.tar.gz | tar xz'
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

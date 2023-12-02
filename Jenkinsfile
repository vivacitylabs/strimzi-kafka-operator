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
        stage('Make') {
            steps {
//                 sh 'curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-455.0.0-linux-x86_64.tar.gz | tar xz'
//                 sh 'curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-455.0.0-linux-x86_64.tar.gz | tar xz && ./google-cloud-sdk/install.sh && export PATH=${WORKSPACE}/google-cloud-sdk/bin:$PATH && ./google-cloud-sdk/bin/gcloud init'
                withGCP("atrocity-gar-pusher") {
                    sh 'gcloud auth print-access-token | docker login -u oauth2accesstoken --password-stdin https://europe-west1-docker.pkg.dev'
                    sh 'make all'
                }
            }
        }
//         stage('Re-tag and push') {
//             when {
//               expression {
//                 currentBuild.result == null || currentBuild.result == 'SUCCESS'
//               }
//             }
//             steps {
//                 sh 'make publish'
//             }
//         }
    }
}

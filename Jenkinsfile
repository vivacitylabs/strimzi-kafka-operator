pipeline {
    agent {
        docker {
            image 'europe-west1-docker.pkg.dev/vivacity-infrastructure/kafka-strimzi/operator-ci:4'
            label 'cross-compiler'
        }
    }

    stages {
        stage('Debug') {
            steps {
                sh 'mvn --version'
                sh 'env'
                sh 'pwd'
                sh 'ls -alphs1'
            }
        }
        stage('Maven install') {
            steps {
                sh 'mvn -DskipTests -X install'
            }
        }
        stage('Make') {
            steps {
                sh 'make all'
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

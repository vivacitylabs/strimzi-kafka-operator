pipeline {
    agent {
        dockerfile {
            label 'cross-compiler'
        }
    }

    stages {
        stage('Debug') {
            steps {
                sh 'mvn --version'
            }
        }
        stage('Maven install') {
            steps {
                sh 'mvn -DskipTests install'
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

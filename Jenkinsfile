pipeline {
    agent {
        dockerfile {
            label 'cross-compiler'
        }
    }

    stages {
        stage('Build') {
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

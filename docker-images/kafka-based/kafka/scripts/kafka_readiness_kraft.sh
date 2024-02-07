#!/usr/bin/env bash
set -e

file=/tmp/strimzi.properties
test -f $file
roles=$(grep -Po '(?<=^process.roles=).+' "$file")
if [[ "$roles" =~ "controller" ]] && [[ ! "$roles" =~ "broker" ]]; then
  # For controller only mode, check if it is listening on port 9090 (configured in controller.listener.names).
  netstat -lnt | grep -Eq 'tcp6?[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]+[^ ]+:9090.*LISTEN[[:space:]]*'
else
  # For combined or broker only mode, check readiness via HTTP endpoint exposed by Kafka Agent.
  # The endpoint returns 204 when broker state is 3 (RUNNING).
  tmp_dir=/tmp
  kafka_root=/opt/kafka
  password=top_secret_password123
  broker_id=$(echo "$HOSTNAME" | sed -r 's/^.*-([0-9]+$)/\1/')
  curl http://localhost:8080/v1/ready/ --fail

  IFS=" "
  # shellcheck disable=SC2153
  read -r -a broker_hostnames <<< "$BROKER_HOSTNAMES" # Supplied by terraform broker containers template env var
  broker_hostname=${broker_hostnames[$broker_id]}
  unset IFS

  rm -f $tmp_dir/truststore.jks
  keytool -noprompt -storepass $password -import -file $kafka_root/client-ca-certs/ca.crt -alias VivaCityECDSA_CA -keystore $tmp_dir/truststore.jks
  openssl pkcs12 -export -out $tmp_dir/keystore.p12 -inkey $kafka_root/certificates/custom-tls-9093-certs/tls.key -in $kafka_root/certificates/custom-tls-9093-certs/tls.crt -certfile $kafka_root/client-ca-certs/ca.crt -password pass:$password
  cat <<EOF > $tmp_dir/ssl.properties
ssl.ca.location=$kafka_root/client-ca-certs/ca.crt
security.protocol=SSL
ssl.keystore.location=$tmp_dir/keystore.p12
ssl.keystore.password=$password
ssl.keystore.type=PKCS12
ssl.truststore.location=$tmp_dir/truststore.jks
ssl.truststore.password=$password
ssl.truststore.type=JKS
EOF
  if [ "$($kafka_root/bin/kafka-topics.sh --bootstrap-server "$broker_hostname:9093" --command-config $tmp_dir/ssl.properties --under-replicated-partitions --describe | wc -l)" -eq 0 ]; then exit 0; else exit 1; fi;
fi

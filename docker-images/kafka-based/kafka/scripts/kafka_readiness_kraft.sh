#!/usr/bin/env bash
set -e

file=/tmp/strimzi.properties
test -f $file

# During migration, the process.roles field can be still not set on broker only nodes
# so, because grep would fail, the "|| true" operation allows to return empty roles result
roles=$(grep -Po '(?<=^process.roles=).+' "$file" || true)
if [[ "$roles" =~ "controller" ]] && [[ ! "$roles" =~ "broker" ]]; then
  # For controller only mode, check if it is listening on port 9090 (configured in controller.listener.names).
  netstat -lnt | grep -Eq 'tcp6?[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]+[^ ]+:9090.*LISTEN[[:space:]]*'
else
  # For combined or broker only mode, check readiness via HTTP endpoint exposed by Kafka Agent.
  # The endpoint returns 204 when broker state is 3 (RUNNING).
  curl http://localhost:8080/v1/ready/ --fail

  tmp_dir=/tmp
  kafka_root=/opt/kafka

  # BROKER_HOSTNAMES_WITH_PORT_CSV Supplied by terraform broker containers template env var
  if [ -z ${BROKER_HOSTNAMES_WITH_PORT_CSV+x} ]; then echo "BROKER_HOSTNAMES_WITH_PORT_CSV is unset"; exit 1 ; else echo "BROKER_HOSTNAMES_WITH_PORT_CSV is set to '$BROKER_HOSTNAMES_WITH_PORT_CSV'"; fi

  password=$(cat "$kafka_root/broker-certs/$HOSTNAME.password")
  rm -f $tmp_dir/truststore.jks
  keytool -noprompt -storepass "$password" -import -file $kafka_root/cluster-ca-certs/ca.crt -alias StrimziCA -keystore $tmp_dir/truststore.jks

  cat <<EOF > $tmp_dir/ssl.properties
ssl.ca.location=$kafka_root/cluster-ca-certs/ca.crt
security.protocol=SSL
ssl.keystore.location=$kafka_root/broker-certs/$HOSTNAME.p12
ssl.keystore.password=$password
ssl.keystore.type=PKCS12
ssl.truststore.location=$tmp_dir/truststore.jks
ssl.truststore.password=$password
ssl.truststore.type=JKS
EOF
  $kafka_root/bin/kafka-topics.sh --bootstrap-server "${BROKER_HOSTNAMES_WITH_PORT_CSV}" --command-config $tmp_dir/ssl.properties --under-replicated-partitions --describe > /tmp/under_replicated_partitions
  if [ "$(wc -l < /tmp/under_replicated_partitions)" -eq 0 ]; then exit 0; else exit 1; fi
fi

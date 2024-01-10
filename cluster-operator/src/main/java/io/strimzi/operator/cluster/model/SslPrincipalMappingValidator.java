package io.strimzi.operator.cluster.model;

import io.strimzi.api.kafka.model.KafkaResources;
import org.apache.kafka.common.security.ssl.SslPrincipalMapper;

import java.io.IOException;
import java.util.List;

public class SslPrincipalMappingValidator {

    public static void validate(String mappingRules, String clusterName) throws InvalidResourceException {
        List<String> INTERNAL_NAMES = List.of(
                String.format("CN=%s,O=io.strimzi", KafkaResources.kafkaStatefulSetName(clusterName)),
                String.format("CN=%s-%s,O=io.strimzi", clusterName, "entity-topic-operator"),
                String.format("CN=%s-%s,O=io.strimzi", clusterName, "entity-user-operator"),
                String.format("CN=%s-%s,O=io.strimzi", clusterName, "kafka-exporter"),
                String.format("CN=%s-%s,O=io.strimzi", clusterName, "cruise-control"),
                String.format("CN=%s,O=io.strimzi", "cluster-operator")
                // TODO - add any more internal names
        );
        SslPrincipalMapper mapper = new SslPrincipalMapper(mappingRules);
        try {
            for (String name : INTERNAL_NAMES) {
                String newName = mapper.getName(name);
                if (!newName.equals(name)) {
                    throw new InvalidResourceException(String.format("expected \"ssl.principal.mapping.rules\" to leave internal names unchanged, %s mapped to %s. Try prepending a rule such as 'RULE:^CN=(.*?),O=io\\.strimzi$/CN=$1,O=io.strimzi/', to your rules", name, newName));
                }
            }
        } catch (IOException ignored) {

        }
    }
}

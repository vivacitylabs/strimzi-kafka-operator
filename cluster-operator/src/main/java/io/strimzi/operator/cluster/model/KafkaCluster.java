/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLSBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleRef;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.strimzi.api.kafka.model.CertAndKeySecretSource;
import io.strimzi.api.kafka.model.CruiseControlResources;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaAuthorization;
import io.strimzi.api.kafka.model.KafkaAuthorizationKeycloak;
import io.strimzi.api.kafka.model.KafkaAuthorizationOpa;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaExporterResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaSpec;
import io.strimzi.api.kafka.model.Rack;
import io.strimzi.api.kafka.model.StrimziPodSet;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationCustom;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationOAuth;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolStatus;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.ExternalTrafficPolicy;
import io.strimzi.api.kafka.model.template.InternalServiceTemplate;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplate;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplate;
import io.strimzi.api.kafka.model.template.PodTemplate;
import io.strimzi.api.kafka.model.template.ResourceTemplate;
import io.strimzi.certs.CertAndKey;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.cruisecontrol.CruiseControlMetricsReporter;
import io.strimzi.operator.cluster.model.jmx.JmxModel;
import io.strimzi.operator.cluster.model.jmx.SupportsJmx;
import io.strimzi.operator.cluster.model.logging.LoggingModel;
import io.strimzi.operator.cluster.model.logging.SupportsLogging;
import io.strimzi.operator.cluster.model.metrics.MetricsModel;
import io.strimzi.operator.cluster.model.metrics.SupportsMetrics;
import io.strimzi.operator.cluster.model.securityprofiles.ContainerSecurityProviderContextImpl;
import io.strimzi.operator.cluster.model.securityprofiles.PodSecurityProviderContextImpl;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.ClientsCa;
import io.strimzi.operator.common.model.InvalidResourceException;
import io.strimzi.operator.common.model.Labels;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.strimzi.operator.cluster.model.ListenersUtils.isListenerWithCustomAuth;
import static io.strimzi.operator.cluster.model.ListenersUtils.isListenerWithOAuth;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

/**
 * Kafka cluster model
 */
@SuppressWarnings({"checkstyle:ClassDataAbstractionCoupling", "checkstyle:ClassFanOutComplexity"})
public class KafkaCluster extends AbstractModel implements SupportsMetrics, SupportsLogging, SupportsJmx {
    protected static final String COMPONENT_TYPE = "kafka";

    protected static final String ENV_VAR_KAFKA_INIT_EXTERNAL_ADDRESS = "EXTERNAL_ADDRESS";
    /* test */ static final String ENV_VAR_STRIMZI_CLUSTER_ID = "STRIMZI_CLUSTER_ID";
    /* test */ static final String ENV_VAR_STRIMZI_KRAFT_ENABLED = "STRIMZI_KRAFT_ENABLED";
    private static final String ENV_VAR_KAFKA_METRICS_ENABLED = "KAFKA_METRICS_ENABLED";

    // For port names in services, a 'tcp-' prefix is added to support Istio protocol selection
    // This helps Istio to avoid using a wildcard listener and instead present IP:PORT pairs which effects
    // proper listener, routing and metrics configuration sent to Envoy
    /**
     * Port number used for replication
     */
    public static final int REPLICATION_PORT = 9091;
    protected static final String REPLICATION_PORT_NAME = "tcp-replication";
    protected static final int KAFKA_AGENT_PORT = 8443;
    protected static final String KAFKA_AGENT_PORT_NAME = "tcp-kafkaagent";
    protected static final int CONTROLPLANE_PORT = 9090;
    protected static final String CONTROLPLANE_PORT_NAME = "tcp-ctrlplane"; // port name is up to 15 characters


    /**
     * Port used by the Route listeners
     */
    public static final int ROUTE_PORT = 443;

    /**
     * Port used by the Ingress listeners
     */
    public static final int INGRESS_PORT = 443;

    protected static final String KAFKA_NAME = "kafka";
    protected static final String CLUSTER_CA_CERTS_VOLUME = "cluster-ca";
    protected static final String BROKER_CERTS_VOLUME = "broker-certs";
    protected static final String CLIENT_CA_CERTS_VOLUME = "client-ca-cert";
    protected static final String CLUSTER_CA_CERTS_VOLUME_MOUNT = "/opt/kafka/cluster-ca-certs";
    protected static final String BROKER_CERTS_VOLUME_MOUNT = "/opt/kafka/broker-certs";
    protected static final String CLIENT_CA_CERTS_VOLUME_MOUNT = "/opt/kafka/client-ca-certs";
    protected static final String TRUSTED_CERTS_BASE_VOLUME_MOUNT = "/opt/kafka/certificates";
    protected static final String CUSTOM_AUTHN_SECRETS_VOLUME_MOUNT = "/opt/kafka/custom-authn-secrets";
    private static final String DATA_VOLUME_MOUNT_PATH = "/var/lib/kafka";
    private static final String LOG_AND_METRICS_CONFIG_VOLUME_NAME = "kafka-metrics-and-logging";
    private static final String LOG_AND_METRICS_CONFIG_VOLUME_MOUNT = "/opt/kafka/custom-config/";

    protected static final String CO_ENV_VAR_CUSTOM_KAFKA_POD_LABELS = "STRIMZI_CUSTOM_KAFKA_LABELS";

    /**
     * Records the Kafka version currently running inside Kafka StrimziPodSet
     */
    public static final String ANNO_STRIMZI_IO_KAFKA_VERSION = Annotations.STRIMZI_DOMAIN + "kafka-version";

    /**
     * Records the used log.message.format.version
     */
    public static final String ANNO_STRIMZI_IO_LOG_MESSAGE_FORMAT_VERSION = Annotations.STRIMZI_DOMAIN + "log-message-format-version";

    /**
     * Records the used inter.broker.protocol.version
     */
    public static final String ANNO_STRIMZI_IO_INTER_BROKER_PROTOCOL_VERSION = Annotations.STRIMZI_DOMAIN + "inter-broker-protocol-version";

    /**
     * Records the state of the Kafka upgrade process. Unset outside of upgrades.
     */
    public static final String ANNO_STRIMZI_BROKER_CONFIGURATION_HASH = Annotations.STRIMZI_DOMAIN + "broker-configuration-hash";

    /**
     * Annotation for keeping certificate thumprints
     */
    public static final String ANNO_STRIMZI_CUSTOM_LISTENER_CERT_THUMBPRINTS = Annotations.STRIMZI_DOMAIN + "custom-listener-cert-thumbprints";

    /**
     * The annotation value which indicates that the Node Pools are enabled
     */
    public static final String ENABLED_VALUE_STRIMZI_IO_NODE_POOLS = "enabled";

    /**
     * Key under which the broker configuration is stored in Config Map
     */
    public static final String BROKER_CONFIGURATION_FILENAME = "server.config";

    /**
     * Key under which the listener configuration is stored in Config Map
     */
    public static final String BROKER_LISTENERS_FILENAME = "listeners.config";

    // Kafka configuration
    private Rack rack;
    private String initImage;
    private List<GenericKafkaListener> listeners;
    private KafkaAuthorization authorization;
    private KafkaVersion kafkaVersion;
    private boolean useKRaft = false;
    private String clusterId;
    private JmxModel jmx;
    private CruiseControlMetricsReporter ccMetricsReporter;
    private MetricsModel metrics;
    private LoggingModel logging;
    /* test */ KafkaConfiguration configuration;

    /**
     * Warning conditions generated from the Custom Resource
     */
    protected List<Condition> warningConditions = new ArrayList<>(0);

    /**
     * Node pools
     */
    private List<KafkaPool> nodePools;

    // Templates
    private PodDisruptionBudgetTemplate templatePodDisruptionBudget;
    private ResourceTemplate templateInitClusterRoleBinding;
    private InternalServiceTemplate templateHeadlessService;
    private InternalServiceTemplate templateService;
    private ResourceTemplate templateExternalBootstrapService;
    private ResourceTemplate templateBootstrapRoute;
    private ResourceTemplate templateBootstrapIngress;

    private static final Map<String, String> DEFAULT_POD_LABELS = new HashMap<>();
    static {
        String value = System.getenv(CO_ENV_VAR_CUSTOM_KAFKA_POD_LABELS);
        if (value != null) {
            DEFAULT_POD_LABELS.putAll(Util.parseMap(value));
        }
    }

    /**
     * Constructor
     *
     * @param reconciliation The reconciliation
     * @param resource Kubernetes resource with metadata containing the namespace and cluster name
     * @param sharedEnvironmentProvider Shared environment provider
     */
    private KafkaCluster(Reconciliation reconciliation, HasMetadata resource, SharedEnvironmentProvider sharedEnvironmentProvider) {
        super(reconciliation, resource, KafkaResources.kafkaStatefulSetName(resource.getMetadata().getName()), COMPONENT_TYPE, sharedEnvironmentProvider);

        this.initImage = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_KAFKA_INIT_IMAGE, "quay.io/strimzi/operator:latest");
    }

    /**
     * Creates the Kafka cluster model instance from a Kafka CR
     *
     * @param reconciliation                Reconciliation marker
     * @param kafka                         Kafka custom resource
     * @param pools                         Set of node pools used by this cluster
     * @param versions                      Supported Kafka versions
     * @param useKRaft                      Flag indicating if KRaft is enabled
     * @param clusterId                     Kafka cluster Id (or null if it is not known yet)
     * @param sharedEnvironmentProvider     Shared environment provider
     * @return Kafka cluster instance
     */
    public static KafkaCluster fromCrd(Reconciliation reconciliation, Kafka kafka, List<KafkaPool> pools, KafkaVersion.Lookup versions, boolean useKRaft, String clusterId, SharedEnvironmentProvider sharedEnvironmentProvider) {
        KafkaSpec kafkaSpec = kafka.getSpec();
        KafkaClusterSpec kafkaClusterSpec = kafkaSpec.getKafka();

        KafkaCluster result = new KafkaCluster(reconciliation, kafka, sharedEnvironmentProvider);

        result.clusterId = clusterId;
        result.useKRaft = useKRaft;
        result.nodePools = pools;

        // This also validates that the Kafka version is supported
        result.kafkaVersion = versions.supportedVersion(kafkaClusterSpec.getVersion());

        // Number of broker nodes => used later in various validation methods
        long numberOfBrokers = result.brokerNodes().size();

        ModelUtils.validateComputeResources(kafkaClusterSpec.getResources(), ".spec.kafka.resources");
        validateIntConfigProperty("default.replication.factor", kafkaClusterSpec, numberOfBrokers);
        validateIntConfigProperty("offsets.topic.replication.factor", kafkaClusterSpec, numberOfBrokers);
        validateIntConfigProperty("transaction.state.log.replication.factor", kafkaClusterSpec, numberOfBrokers);
        validateIntConfigProperty("transaction.state.log.min.isr", kafkaClusterSpec, numberOfBrokers);

        result.image = versions.kafkaImage(kafkaClusterSpec.getImage(), kafkaClusterSpec.getVersion());
        result.readinessProbeOptions = ProbeUtils.extractReadinessProbeOptionsOrDefault(kafkaClusterSpec, ProbeUtils.DEFAULT_HEALTHCHECK_OPTIONS);
        result.livenessProbeOptions = ProbeUtils.extractLivenessProbeOptionsOrDefault(kafkaClusterSpec, ProbeUtils.DEFAULT_HEALTHCHECK_OPTIONS);
        result.rack = kafkaClusterSpec.getRack();

        String initImage = kafkaClusterSpec.getBrokerRackInitImage();
        if (initImage == null) {
            initImage = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_KAFKA_INIT_IMAGE, "quay.io/strimzi/operator:latest");
        }
        result.initImage = initImage;

        result.metrics = new MetricsModel(kafkaClusterSpec);
        result.logging = new LoggingModel(kafkaClusterSpec, result.getClass().getSimpleName(), false, true);

        result.jmx = new JmxModel(
                reconciliation.namespace(),
                KafkaResources.kafkaJmxSecretName(result.cluster),
                result.labels,
                result.ownerReference,
                kafkaClusterSpec
        );

        // Handle Kafka broker configuration
        KafkaConfiguration configuration = new KafkaConfiguration(reconciliation, kafkaClusterSpec.getConfig().entrySet());
        validateConfiguration(reconciliation, kafka, result.kafkaVersion, configuration);
        result.configuration = configuration;

        result.ccMetricsReporter = CruiseControlMetricsReporter.fromCrd(kafka, configuration, numberOfBrokers);

        // Configure listeners
        if (kafkaClusterSpec.getListeners() == null || kafkaClusterSpec.getListeners().isEmpty()) {
            LOGGER.errorCr(reconciliation, "The required field .spec.kafka.listeners is missing");
            throw new InvalidResourceException("The required field .spec.kafka.listeners is missing");
        }
        List<GenericKafkaListener> listeners = kafkaClusterSpec.getListeners();
        ListenersValidator.validate(reconciliation, result.brokerNodes(), listeners);
        result.listeners = listeners;

        String sslPrincipalMappingRules = result.configuration.getConfigOption("ssl.principal.mapping.rules");
        if (sslPrincipalMappingRules != null) {
            SslPrincipalMappingValidator.validate(sslPrincipalMappingRules, kafka.getMetadata().getName());
        }

        // Set authorization
        if (kafkaClusterSpec.getAuthorization() instanceof KafkaAuthorizationKeycloak) {
            if (!ListenersUtils.hasListenerWithOAuth(listeners)) {
                throw new InvalidResourceException("You cannot configure Keycloak Authorization without any listener with OAuth based authentication");
            } else {
                KafkaAuthorizationKeycloak authorizationKeycloak = (KafkaAuthorizationKeycloak) kafkaClusterSpec.getAuthorization();
                if (authorizationKeycloak.getClientId() == null || authorizationKeycloak.getTokenEndpointUri() == null) {
                    LOGGER.errorCr(reconciliation, "Keycloak Authorization: Token Endpoint URI and clientId are both required");
                    throw new InvalidResourceException("Keycloak Authorization: Token Endpoint URI and clientId are both required");
                }
            }
        }

        result.authorization = kafkaClusterSpec.getAuthorization();

        if (kafkaClusterSpec.getTemplate() != null) {
            KafkaClusterTemplate template = kafkaClusterSpec.getTemplate();

            result.templatePodDisruptionBudget = template.getPodDisruptionBudget();
            result.templateInitClusterRoleBinding = template.getClusterRoleBinding();
            result.templateService = template.getBootstrapService();
            result.templateHeadlessService = template.getBrokersService();
            result.templateExternalBootstrapService = template.getExternalBootstrapService();
            result.templateBootstrapRoute = template.getExternalBootstrapRoute();
            result.templateBootstrapIngress = template.getExternalBootstrapIngress();
            result.templateServiceAccount = template.getServiceAccount();
        }

        // Should run at the end when everything is set
        KafkaSpecChecker specChecker = new KafkaSpecChecker(kafkaSpec, versions, result);
        result.warningConditions.addAll(specChecker.run(useKRaft));

        return result;
    }

    /**
     * Generates list of references to Kafka nodes for this Kafka cluster. The references contain both the pod name and
     * the ID of the Kafka node.
     *
     * @return  Set of Kafka node references
     */
    public Set<NodeRef> nodes() {
        Set<NodeRef> nodes = new LinkedHashSet<>();

        for (KafkaPool pool : nodePools)    {
            nodes.addAll(pool.nodes());
        }

        return nodes;
    }

    /**
     * Generates list of references to Kafka nodes for this Kafka cluster which have the broker role. The references
     * contain both the pod name and the ID of the Kafka node.
     *
     * @return  Set of Kafka node references with broker role
     */
    public Set<NodeRef> brokerNodes() {
        Set<NodeRef> brokers = new LinkedHashSet<>();

        for (KafkaPool pool : nodePools)    {
            if (pool.isBroker()) {
                brokers.addAll(pool.nodes());
            }
        }

        return brokers;
    }

    /**
     * Generates list of references to Kafka nodes for this Kafka cluster which have the controller role. The references
     * contain both the pod name and the ID of the Kafka node.
     *
     * @return  Set of Kafka node references with controller role
     */
    public Set<NodeRef> controllerNodes() {
        Set<NodeRef> controllers = new LinkedHashSet<>();

        for (KafkaPool pool : nodePools)    {
            if (pool.isController()) {
                controllers.addAll(pool.nodes());
            }
        }

        return controllers;
    }

    /**
     * Generates updated statuses for the different node pools
     *
     * @return  Map with statuses for the different node pools
     */
    public Map<String, KafkaNodePoolStatus> nodePoolStatuses() {
        Map<String, KafkaNodePoolStatus> statuses = new HashMap<>();

        for (KafkaPool pool : nodePools)    {
            statuses.put(pool.poolName, pool.generateNodePoolStatus(clusterId));
        }

        return statuses;
    }

    /**
     * Finds a node pool to which this given node belongs
     *
     * @return  KafkaPool which includes this node ID
     */
    private KafkaPool nodePoolForNodeId(int nodeId) {
        for (KafkaPool pool : nodePools)    {
            if (pool.containsNodeId(nodeId))    {
                return pool;
            }
        }

        throw new RuntimeException("Node ID " + nodeId + " does not belong to any known node pool!");
    }

    /**
     * Validates the Kafka broker configuration against the configuration options of the desired Kafka version.
     *
     * @param reconciliation    The reconciliation
     * @param kafkaAssembly     Kafka custom resource
     * @param desiredVersion    Desired Kafka version
     * @param configuration     Kafka broker configuration
     */
    private static void validateConfiguration(Reconciliation reconciliation, Kafka kafkaAssembly, KafkaVersion desiredVersion, KafkaConfiguration configuration) {
        List<String> errorsInConfig = configuration.validate(desiredVersion);

        if (!errorsInConfig.isEmpty()) {
            for (String error : errorsInConfig) {
                LOGGER.warnCr(reconciliation, "Kafka {}/{} has invalid spec.kafka.config: {}",
                        kafkaAssembly.getMetadata().getNamespace(),
                        kafkaAssembly.getMetadata().getName(),
                        error);
            }

            throw new InvalidResourceException("Kafka " +
                    kafkaAssembly.getMetadata().getNamespace() + "/" + kafkaAssembly.getMetadata().getName() +
                    " has invalid spec.kafka.config: " +
                    String.join(", ", errorsInConfig));
        }
    }

    protected static void validateIntConfigProperty(String propertyName, KafkaClusterSpec kafkaClusterSpec, long numberOfBrokers) {
        String orLess = numberOfBrokers > 1 ? " or less" : "";
        if (kafkaClusterSpec.getConfig() != null && kafkaClusterSpec.getConfig().get(propertyName) != null) {
            try {
                int propertyVal = Integer.parseInt(kafkaClusterSpec.getConfig().get(propertyName).toString());
                if (propertyVal > numberOfBrokers) {
                    throw new InvalidResourceException("Kafka configuration option '" + propertyName + "' should be set to " + numberOfBrokers + orLess + " because this cluster has only " + numberOfBrokers + " Kafka broker(s).");
                }
            } catch (NumberFormatException e) {
                throw new InvalidResourceException("Property " + propertyName + " should be an integer");
            }
        }
    }

    /**
     * Generates ports for bootstrap service.
     * The bootstrap service contains only the client interfaces.
     * Not the replication interface which doesn't need bootstrap service.
     *
     * @return List with generated ports
     */
    private List<ServicePort> getServicePorts() {
        List<GenericKafkaListener> internalListeners = ListenersUtils.internalListeners(listeners);

        List<ServicePort> ports = new ArrayList<>(internalListeners.size() + 1);
        ports.add(ServiceUtils.createServicePort(REPLICATION_PORT_NAME, REPLICATION_PORT, REPLICATION_PORT, "TCP"));

        for (GenericKafkaListener listener : internalListeners) {
            ports.add(ServiceUtils.createServicePort(ListenersUtils.backwardsCompatiblePortName(listener), listener.getPort(), listener.getPort(), "TCP"));
        }

        return ports;
    }

    /**
     * Generates ports for headless service.
     * The headless service contains both the client interfaces and replication interface.
     *
     * @return List with generated ports
     */
    private List<ServicePort> getHeadlessServicePorts() {
        List<GenericKafkaListener> internalListeners = ListenersUtils.internalListeners(listeners);

        List<ServicePort> ports = new ArrayList<>(internalListeners.size() + 3);
        ports.add(ServiceUtils.createServicePort(CONTROLPLANE_PORT_NAME, CONTROLPLANE_PORT, CONTROLPLANE_PORT, "TCP"));
        ports.add(ServiceUtils.createServicePort(REPLICATION_PORT_NAME, REPLICATION_PORT, REPLICATION_PORT, "TCP"));
        ports.add(ServiceUtils.createServicePort(KAFKA_AGENT_PORT_NAME, KAFKA_AGENT_PORT, KAFKA_AGENT_PORT, "TCP"));

        for (GenericKafkaListener listener : internalListeners) {
            ports.add(ServiceUtils.createServicePort(ListenersUtils.backwardsCompatiblePortName(listener), listener.getPort(), listener.getPort(), "TCP"));
        }

        ports.addAll(jmx.servicePorts());

        return ports;
    }

    /**
     * Generates a Service according to configured defaults
     *
     * @return The generated Service
     */
    public Service generateService() {
        return ServiceUtils.createDiscoverableClusterIpService(
                KafkaResources.bootstrapServiceName(cluster),
                namespace,
                labels,
                ownerReference,
                templateService,
                getServicePorts(),
                brokersSelector(),
                null,
                getInternalDiscoveryAnnotation()
        );
    }

    /**
     * Generates a JSON String with the discovery annotation for the internal bootstrap service
     *
     * @return  JSON with discovery annotation
     */
    /*test*/ Map<String, String> getInternalDiscoveryAnnotation() {
        JsonArray anno = new JsonArray();

        for (GenericKafkaListener listener : listeners) {
            JsonObject discovery = new JsonObject();
            discovery.put("port", listener.getPort());
            discovery.put("tls", listener.isTls());
            discovery.put("protocol", "kafka");

            if (listener.getAuth() != null) {
                discovery.put("auth", listener.getAuth().getType());
            } else {
                discovery.put("auth", "none");
            }

            anno.add(discovery);
        }

        return singletonMap(Labels.STRIMZI_DISCOVERY_LABEL, anno.encodePrettily());
    }

    /**
     * Generates list of external bootstrap services. These services are used for exposing it externally.
     * Separate services are used to make sure that we do expose the right port in the right way.
     *
     * @return The list with generated Services
     */
    public List<Service> generateExternalBootstrapServices() {
        List<GenericKafkaListener> externalListeners = ListenersUtils.listenersWithOwnServices(listeners);
        List<Service> services = new ArrayList<>(externalListeners.size());

        for (GenericKafkaListener listener : externalListeners)   {
            if (ListenersUtils.skipCreateBootstrapService(listener)) {
                continue;
            }

            String serviceName = ListenersUtils.backwardsCompatibleBootstrapServiceName(cluster, listener);

            List<ServicePort> ports = Collections.singletonList(
                    ServiceUtils.createServicePort(ListenersUtils.backwardsCompatiblePortName(listener),
                            listener.getPort(),
                            listener.getPort(),
                            ListenersUtils.bootstrapNodePort(listener),
                            "TCP")
            );

            Service service = ServiceUtils.createService(
                    serviceName,
                    namespace,
                    labels,
                    ownerReference,
                    templateExternalBootstrapService,
                    ports,
                    brokersSelector(),
                    ListenersUtils.serviceType(listener),
                    ListenersUtils.bootstrapLabels(listener),
                    ListenersUtils.bootstrapAnnotations(listener),
                    ListenersUtils.ipFamilyPolicy(listener),
                    ListenersUtils.ipFamilies(listener)
            );

            if (KafkaListenerType.LOADBALANCER == listener.getType()) {
                String loadBalancerIP = ListenersUtils.bootstrapLoadBalancerIP(listener);
                if (loadBalancerIP != null) {
                    service.getSpec().setLoadBalancerIP(loadBalancerIP);
                }

                List<String> loadBalancerSourceRanges = ListenersUtils.loadBalancerSourceRanges(listener);
                if (loadBalancerSourceRanges != null
                        && !loadBalancerSourceRanges.isEmpty()) {
                    service.getSpec().setLoadBalancerSourceRanges(loadBalancerSourceRanges);
                }

                List<String> finalizers = ListenersUtils.finalizers(listener);
                if (finalizers != null
                        && !finalizers.isEmpty()) {
                    service.getMetadata().setFinalizers(finalizers);
                }

                String loadBalancerClass = ListenersUtils.controllerClass(listener);
                if (loadBalancerClass != null) {
                    service.getSpec().setLoadBalancerClass(loadBalancerClass);
                }
            }

            if (KafkaListenerType.LOADBALANCER == listener.getType() || KafkaListenerType.NODEPORT == listener.getType()) {
                ExternalTrafficPolicy etp = ListenersUtils.externalTrafficPolicy(listener);
                if (etp != null) {
                    service.getSpec().setExternalTrafficPolicy(etp.toValue());
                } else {
                    service.getSpec().setExternalTrafficPolicy(ExternalTrafficPolicy.CLUSTER.toValue());
                }
            }

            services.add(service);
        }

        return services;
    }

    /**
     * Generates list of per-pod service.
     *
     * @return The list with generated Services
     */
    public List<Service> generatePerPodServices() {
        List<GenericKafkaListener> externalListeners = ListenersUtils.listenersWithOwnServices(listeners);
        List<Service> services = new ArrayList<>();

        for (GenericKafkaListener listener : externalListeners)   {
            for (KafkaPool pool : nodePools)    {
                if (pool.isBroker()) {
                    for (NodeRef node : pool.nodes()) {
                        String serviceName = ListenersUtils.backwardsCompatiblePerBrokerServiceName(pool.componentName, node.nodeId(), listener);

                        List<ServicePort> ports = Collections.singletonList(
                                ServiceUtils.createServicePort(
                                        ListenersUtils.backwardsCompatiblePortName(listener),
                                        listener.getPort(),
                                        listener.getPort(),
                                        ListenersUtils.brokerNodePort(listener, node.nodeId()),
                                        "TCP")
                        );

                        Service service = ServiceUtils.createService(
                                serviceName,
                                namespace,
                                pool.labels,
                                pool.ownerReference,
                                pool.templatePerBrokerService,
                                ports,
                                pool.labels.strimziSelectorLabels().withStatefulSetPod(node.podName()),
                                ListenersUtils.serviceType(listener),
                                ListenersUtils.brokerLabels(listener, node.nodeId()),
                                ListenersUtils.brokerAnnotations(listener, node.nodeId()),
                                ListenersUtils.ipFamilyPolicy(listener),
                                ListenersUtils.ipFamilies(listener)
                        );

                        if (KafkaListenerType.LOADBALANCER == listener.getType()) {
                            String loadBalancerIP = ListenersUtils.brokerLoadBalancerIP(listener, node.nodeId());
                            if (loadBalancerIP != null) {
                                service.getSpec().setLoadBalancerIP(loadBalancerIP);
                            }

                            List<String> loadBalancerSourceRanges = ListenersUtils.loadBalancerSourceRanges(listener);
                            if (loadBalancerSourceRanges != null
                                    && !loadBalancerSourceRanges.isEmpty()) {
                                service.getSpec().setLoadBalancerSourceRanges(loadBalancerSourceRanges);
                            }

                            List<String> finalizers = ListenersUtils.finalizers(listener);
                            if (finalizers != null
                                    && !finalizers.isEmpty()) {
                                service.getMetadata().setFinalizers(finalizers);
                            }

                            String loadBalancerClass = ListenersUtils.controllerClass(listener);
                            if (loadBalancerClass != null) {
                                service.getSpec().setLoadBalancerClass(loadBalancerClass);
                            }
                        }

                        if (KafkaListenerType.LOADBALANCER == listener.getType() || KafkaListenerType.NODEPORT == listener.getType()) {
                            ExternalTrafficPolicy etp = ListenersUtils.externalTrafficPolicy(listener);
                            if (etp != null) {
                                service.getSpec().setExternalTrafficPolicy(etp.toValue());
                            } else {
                                service.getSpec().setExternalTrafficPolicy(ExternalTrafficPolicy.CLUSTER.toValue());
                            }
                        }

                        services.add(service);
                    }
                }
            }
        }

        return services;
    }

    /**
     * Generates a list of bootstrap route which can be used to bootstrap clients outside of OpenShift.
     *
     * @return The list of generated Routes
     */
    public List<Route> generateExternalBootstrapRoutes() {
        List<GenericKafkaListener> routeListeners = ListenersUtils.routeListeners(listeners);
        List<Route> routes = new ArrayList<>(routeListeners.size());

        for (GenericKafkaListener listener : routeListeners)   {
            String routeName = ListenersUtils.backwardsCompatibleBootstrapRouteOrIngressName(cluster, listener);
            String serviceName = ListenersUtils.backwardsCompatibleBootstrapServiceName(cluster, listener);

            Route route = new RouteBuilder()
                    .withNewMetadata()
                        .withName(routeName)
                        .withLabels(Util.mergeLabelsOrAnnotations(labels.withAdditionalLabels(TemplateUtils.labels(templateBootstrapRoute)).toMap(), ListenersUtils.bootstrapLabels(listener)))
                        .withAnnotations(Util.mergeLabelsOrAnnotations(TemplateUtils.annotations(templateBootstrapRoute), ListenersUtils.bootstrapAnnotations(listener)))
                        .withNamespace(namespace)
                        .withOwnerReferences(ownerReference)
                    .endMetadata()
                    .withNewSpec()
                        .withNewTo()
                            .withKind("Service")
                            .withName(serviceName)
                        .endTo()
                        .withNewPort()
                            .withNewTargetPort(listener.getPort())
                        .endPort()
                        .withNewTls()
                            .withTermination("passthrough")
                        .endTls()
                    .endSpec()
                    .build();

            String host = ListenersUtils.bootstrapHost(listener);
            if (host != null)   {
                route.getSpec().setHost(host);
            }

            routes.add(route);
        }

        return routes;
    }

    /**
     * Generates list of per-pod routes. These routes are used for exposing it externally using OpenShift Routes.
     *
     * @return The list with generated Routes
     */
    public List<Route> generateExternalRoutes() {
        List<GenericKafkaListener> routeListeners = ListenersUtils.routeListeners(listeners);
        List<Route> routes = new ArrayList<>();

        for (GenericKafkaListener listener : routeListeners)   {
            for (KafkaPool pool : nodePools)    {
                if (pool.isBroker()) {
                    for (NodeRef node : pool.nodes()) {
                        String routeName = ListenersUtils.backwardsCompatiblePerBrokerServiceName(pool.componentName, node.nodeId(), listener);
                        Route route = new RouteBuilder()
                                .withNewMetadata()
                                    .withName(routeName)
                                    .withLabels(pool.labels.withAdditionalLabels(Util.mergeLabelsOrAnnotations(TemplateUtils.labels(pool.templatePerBrokerRoute), ListenersUtils.brokerLabels(listener, node.nodeId()))).toMap())
                                    .withAnnotations(Util.mergeLabelsOrAnnotations(TemplateUtils.annotations(pool.templatePerBrokerRoute), ListenersUtils.brokerAnnotations(listener, node.nodeId())))
                                    .withNamespace(namespace)
                                    .withOwnerReferences(pool.ownerReference)
                                .endMetadata()
                                .withNewSpec()
                                    .withNewTo()
                                        .withKind("Service")
                                        .withName(routeName)
                                    .endTo()
                                    .withNewPort()
                                        .withNewTargetPort(listener.getPort())
                                    .endPort()
                                    .withNewTls()
                                        .withTermination("passthrough")
                                    .endTls()
                                .endSpec()
                                .build();

                        String host = ListenersUtils.brokerHost(listener, node.nodeId());
                        if (host != null) {
                            route.getSpec().setHost(host);
                        }

                        routes.add(route);
                    }
                }
            }
        }

        return routes;
    }

    /**
     * Generates a list of bootstrap ingress which can be used to bootstrap clients outside of Kubernetes.
     *
     * @return The list of generated Ingresses
     */
    public List<Ingress> generateExternalBootstrapIngresses() {
        List<GenericKafkaListener> ingressListeners = ListenersUtils.ingressListeners(listeners);
        List<Ingress> ingresses = new ArrayList<>(ingressListeners.size());

        for (GenericKafkaListener listener : ingressListeners)   {
            String ingressName = ListenersUtils.backwardsCompatibleBootstrapRouteOrIngressName(cluster, listener);
            String serviceName = ListenersUtils.backwardsCompatibleBootstrapServiceName(cluster, listener);

            String host = ListenersUtils.bootstrapHost(listener);
            String ingressClass = ListenersUtils.controllerClass(listener);

            HTTPIngressPath path = new HTTPIngressPathBuilder()
                    .withPath("/")
                    .withPathType("Prefix")
                    .withNewBackend()
                        .withNewService()
                            .withName(serviceName)
                            .withNewPort()
                                .withNumber(listener.getPort())
                            .endPort()
                        .endService()
                    .endBackend()
                    .build();

            IngressRule rule = new IngressRuleBuilder()
                    .withHost(host)
                    .withNewHttp()
                        .withPaths(path)
                    .endHttp()
                    .build();

            IngressTLS tls = new IngressTLSBuilder()
                    .withHosts(host)
                    .build();

            Ingress ingress = new IngressBuilder()
                    .withNewMetadata()
                        .withName(ingressName)
                        .withLabels(labels.withAdditionalLabels(Util.mergeLabelsOrAnnotations(TemplateUtils.labels(templateBootstrapIngress), ListenersUtils.bootstrapLabels(listener))).toMap())
                        .withAnnotations(Util.mergeLabelsOrAnnotations(generateInternalIngressAnnotations(), TemplateUtils.annotations(templateBootstrapIngress), ListenersUtils.bootstrapAnnotations(listener)))
                        .withNamespace(namespace)
                        .withOwnerReferences(ownerReference)
                    .endMetadata()
                    .withNewSpec()
                        .withIngressClassName(ingressClass)
                        .withRules(rule)
                        .withTls(tls)
                    .endSpec()
                    .build();

            ingresses.add(ingress);
        }

        return ingresses;
    }

    /**
     * Generates list of per-pod ingress. This ingress is used for exposing it externally using Nginx Ingress.
     *
     * @return The list of generated Ingresses
     */
    public List<Ingress> generateExternalIngresses() {
        List<GenericKafkaListener> ingressListeners = ListenersUtils.ingressListeners(listeners);
        List<Ingress> ingresses = new ArrayList<>();

        for (GenericKafkaListener listener : ingressListeners)   {
            for (KafkaPool pool : nodePools)    {
                if (pool.isBroker()) {
                    for (NodeRef node : pool.nodes()) {
                        String ingressName = ListenersUtils.backwardsCompatiblePerBrokerServiceName(pool.componentName, node.nodeId(), listener);
                        String host = ListenersUtils.brokerHost(listener, node.nodeId());
                        String ingressClass = ListenersUtils.controllerClass(listener);

                        HTTPIngressPath path = new HTTPIngressPathBuilder()
                                .withPath("/")
                                .withPathType("Prefix")
                                .withNewBackend()
                                    .withNewService()
                                        .withName(ingressName)
                                        .withNewPort()
                                            .withNumber(listener.getPort())
                                        .endPort()
                                    .endService()
                                .endBackend()
                                .build();

                        IngressRule rule = new IngressRuleBuilder()
                                .withHost(host)
                                .withNewHttp()
                                    .withPaths(path)
                                .endHttp()
                                .build();

                        IngressTLS tls = new IngressTLSBuilder()
                                .withHosts(host)
                                .build();

                        Ingress ingress = new IngressBuilder()
                                .withNewMetadata()
                                    .withName(ingressName)
                                    .withLabels(pool.labels.withAdditionalLabels(Util.mergeLabelsOrAnnotations(TemplateUtils.labels(pool.templatePerBrokerIngress), ListenersUtils.brokerLabels(listener, node.nodeId()))).toMap())
                                    .withAnnotations(Util.mergeLabelsOrAnnotations(generateInternalIngressAnnotations(), TemplateUtils.annotations(pool.templatePerBrokerIngress), ListenersUtils.brokerAnnotations(listener, node.nodeId())))
                                    .withNamespace(namespace)
                                    .withOwnerReferences(pool.ownerReference)
                                .endMetadata()
                                .withNewSpec()
                                    .withIngressClassName(ingressClass)
                                    .withRules(rule)
                                    .withTls(tls)
                                .endSpec()
                                .build();

                        ingresses.add(ingress);
                    }
                }
            }
        }

        return ingresses;
    }

    /**
     * Generates the annotations needed to configure the Ingress as TLS passthrough
     *
     * @return Map with the annotations
     */
    private Map<String, String> generateInternalIngressAnnotations() {
        Map<String, String> internalAnnotations = new HashMap<>(3);

        internalAnnotations.put("ingress.kubernetes.io/ssl-passthrough", "true");
        internalAnnotations.put("nginx.ingress.kubernetes.io/ssl-passthrough", "true");
        internalAnnotations.put("nginx.ingress.kubernetes.io/backend-protocol", "HTTPS");

        return internalAnnotations;
    }

    /**
     * Generates a headless Service according to configured defaults
     *
     * @return The generated Service
     */
    public Service generateHeadlessService() {
        return ServiceUtils.createHeadlessService(
                KafkaResources.brokersServiceName(cluster),
                namespace,
                labels,
                ownerReference,
                templateHeadlessService,
                getHeadlessServicePorts()
        );
    }

    /**
     * Prepares annotations for the controller resource such as StrimziPodSet.
     *
     * @param storage   Storage configuration which should be stored int he annotation
     *
     * @return  Map with all annotations which should be used for thr controller resource
     */
    private Map<String, String> preparePodSetAnnotations(Storage storage)   {
        Map<String, String> controllerAnnotations = new HashMap<>(2);
        controllerAnnotations.put(ANNO_STRIMZI_IO_KAFKA_VERSION, kafkaVersion.version());
        controllerAnnotations.put(Annotations.ANNO_STRIMZI_IO_STORAGE, ModelUtils.encodeStorageToJson(storage));

        return controllerAnnotations;
    }

    /**
     * Generates the StrimziPodSet for the Kafka cluster.
     *
     * @param isOpenShift            Flags whether we are on OpenShift or not
     * @param imagePullPolicy        Image pull policy which will be used by the pods
     * @param imagePullSecrets       List of image pull secrets
     * @param podAnnotationsProvider Function which provides annotations for given pod based on its broker ID. The
     *                               annotations for each pod are different due to the individual configurations.
     *                               So they need to be dynamically generated though this function instead of just
     *                               passed as Map.
     *
     * @return List of generated StrimziPodSets with Kafka pods
     */
    public List<StrimziPodSet> generatePodSets(boolean isOpenShift,
                                               ImagePullPolicy imagePullPolicy,
                                               List<LocalObjectReference> imagePullSecrets,
                                               Function<Integer, Map<String, String>> podAnnotationsProvider) {
        List<StrimziPodSet> podSets = new ArrayList<>();

        for (KafkaPool pool : nodePools)    {
            podSets.add(WorkloadUtils.createPodSet(
                    pool.componentName,
                    namespace,
                    pool.labels,
                    pool.ownerReference,
                    pool.templatePodSet,
                    pool.nodes(),
                    preparePodSetAnnotations(pool.storage),
                    pool.labels.strimziSelectorLabels(),
                    node -> WorkloadUtils.createStatefulPod(
                            reconciliation,
                            node.podName(),
                            namespace,
                            pool.labels.withStrimziBrokerRole(node.broker()).withStrimziControllerRole(node.controller()),
                            pool.componentName,
                            componentName,
                            pool.templatePod,
                            DEFAULT_POD_LABELS,
                            podAnnotationsProvider.apply(node.nodeId()),
                            KafkaResources.brokersServiceName(cluster),
                            getMergedAffinity(pool),
                            ContainerUtils.listOrNull(createInitContainer(imagePullPolicy, pool)),
                            List.of(createContainer(imagePullPolicy, pool)),
                            getPodSetVolumes(node.podName(), pool.storage, pool.templatePod, isOpenShift),
                            imagePullSecrets,
                            securityProvider.kafkaPodSecurityContext(new PodSecurityProviderContextImpl(pool.storage, pool.templatePod))
                    )
            ));
        }

        return podSets;
    }

    /**
     * Generates the private keys for the Kafka brokers (if needed) and the secret with them which contains both the
     * public and private keys.
     *
     * @param clusterCa                             The CA for cluster certificates
     * @param clientsCa                             The CA for clients certificates
     * @param externalBootstrapDnsName              Map with bootstrap DNS names which should be added to the certificate
     * @param externalDnsNames                      Map with broker DNS names  which should be added to the certificate
     * @param isMaintenanceTimeWindowsSatisfied     Indicates whether we are in a maintenance window or not
     *
     * @return  The generated Secret with broker certificates
     */
    public Secret generateCertificatesSecret(ClusterCa clusterCa, ClientsCa clientsCa, Set<String> externalBootstrapDnsName, Map<Integer, Set<String>> externalDnsNames, boolean isMaintenanceTimeWindowsSatisfied) {
        Set<NodeRef> nodes = nodes();
        Map<String, CertAndKey> brokerCerts;

        try {
            brokerCerts = clusterCa.generateBrokerCerts(namespace, cluster, nodes, externalBootstrapDnsName, externalDnsNames, isMaintenanceTimeWindowsSatisfied);
        } catch (IOException e) {
            LOGGER.warnCr(reconciliation, "Error while generating certificates", e);
            throw new RuntimeException("Failed to prepare Kafka certificates", e);
        }

        Map<String, String> data = new HashMap<>();

        for (NodeRef node : nodes)  {
            CertAndKey cert = brokerCerts.get(node.podName());
            data.put(node.podName() + ".key", cert.keyAsBase64String());
            data.put(node.podName() + ".crt", cert.certAsBase64String());
            data.put(node.podName() + ".p12", cert.keyStoreAsBase64String());
            data.put(node.podName() + ".password", cert.storePasswordAsBase64String());
        }

        return ModelUtils.createSecret(
                KafkaResources.kafkaSecretName(cluster),
                namespace,
                labels,
                ownerReference,
                data,
                Map.of(
                        clusterCa.caCertGenerationAnnotation(), String.valueOf(clusterCa.certGeneration()),
                        clientsCa.caCertGenerationAnnotation(), String.valueOf(clientsCa.certGeneration())
                ),
                emptyMap());
    }

    /* test */ List<ContainerPort> getContainerPortList() {
        List<ContainerPort> ports = new ArrayList<>(listeners.size() + 3);
        ports.add(ContainerUtils.createContainerPort(CONTROLPLANE_PORT_NAME, CONTROLPLANE_PORT));
        ports.add(ContainerUtils.createContainerPort(REPLICATION_PORT_NAME, REPLICATION_PORT));

        for (GenericKafkaListener listener : listeners) {
            ports.add(ContainerUtils.createContainerPort(ListenersUtils.backwardsCompatiblePortName(listener), listener.getPort()));
        }

        if (metrics.isEnabled()) {
            ports.add(ContainerUtils.createContainerPort(MetricsModel.METRICS_PORT_NAME, MetricsModel.METRICS_PORT));
        }

        ports.addAll(jmx.containerPorts());

        return ports;
    }

    /**
     * Generate the persistent volume claims for this cluster.
     *
     * @return The list of PersistentVolumeClaims used by this Kafka cluster
     */
    public List<PersistentVolumeClaim> generatePersistentVolumeClaims() {
        List<PersistentVolumeClaim> pvcs = new ArrayList<>();

        for (KafkaPool pool : nodePools)    {
            pvcs.addAll(generatePersistentVolumeClaimsForPool(pool, pool.storage));
        }

        return pvcs;
    }

    /**
     * Generates PVCs for a single pool. The Storage configuration is passed separately to allow passing custom storage
     * configuration. This is used for example during the "Pod and PVC" cleanup through annotation.
     *
     * @param pool      Kafka pool for which the PVCs will be geenrated
     * @param storage   Storage configuration
     *
     * @return  List of PVCs
     */
    private List<PersistentVolumeClaim> generatePersistentVolumeClaimsForPool(KafkaPool pool, Storage storage)  {
        return PersistentVolumeClaimUtils
                .createPersistentVolumeClaims(
                        namespace,
                        pool.nodes(),
                        storage,
                        false,
                        pool.labels,
                        pool.ownerReference,
                        pool.templatePersistentVolumeClaims
                );
    }

    /**
     * Generates list of non-data volumes used by Kafka Pods. This includes tmp volumes, mounted secrets and config
     * maps.
     *
     * @param isOpenShift Indicates whether we are on OpenShift or not
     * @param podName     The name of the Pod for which are these volumes generated. The Pod name
     *                    identifies which ConfigMap should be used when perBrokerConfiguration is set to
     *                    true. When perBrokerConfiguration is set to false, the Pod name is not used and
     *                    can be set to null.
     * @param templatePod Template with custom pod configurations
     *
     * @return List of non-data volumes used by the ZooKeeper pods
     */
    /* test */ List<Volume> getNonDataVolumes(boolean isOpenShift, String podName, PodTemplate templatePod) {
        List<Volume> volumeList = new ArrayList<>();

        if (rack != null || isExposedWithNodePort()) {
            volumeList.add(VolumeUtils.createEmptyDirVolume(INIT_VOLUME_NAME, "1Mi", "Memory"));
        }

        volumeList.add(VolumeUtils.createTempDirVolume(templatePod));
        volumeList.add(VolumeUtils.createSecretVolume(CLUSTER_CA_CERTS_VOLUME, AbstractModel.clusterCaCertSecretName(cluster), isOpenShift));
        volumeList.add(VolumeUtils.createSecretVolume(BROKER_CERTS_VOLUME, KafkaResources.kafkaSecretName(cluster), isOpenShift));
        volumeList.add(VolumeUtils.createSecretVolume(CLIENT_CA_CERTS_VOLUME, KafkaResources.clientsCaCertificateSecretName(cluster), isOpenShift));
        volumeList.add(VolumeUtils.createConfigMapVolume(LOG_AND_METRICS_CONFIG_VOLUME_NAME, podName));
        volumeList.add(VolumeUtils.createEmptyDirVolume("ready-files", "1Ki", "Memory"));

        for (GenericKafkaListener listener : listeners) {
            if (listener.isTls()
                    && listener.getConfiguration() != null
                    && listener.getConfiguration().getBrokerCertChainAndKey() != null)  {
                CertAndKeySecretSource secretSource = listener.getConfiguration().getBrokerCertChainAndKey();

                Map<String, String> items = new HashMap<>(2);
                items.put(secretSource.getKey(), "tls.key");
                items.put(secretSource.getCertificate(), "tls.crt");

                volumeList.add(
                        VolumeUtils.createSecretVolume(
                                "custom-" + ListenersUtils.identifier(listener) + "-certs",
                                secretSource.getSecretName(),
                                items,
                                isOpenShift
                        )
                );
            }

            if (isListenerWithOAuth(listener))   {
                KafkaListenerAuthenticationOAuth oauth = (KafkaListenerAuthenticationOAuth) listener.getAuth();
                volumeList.addAll(AuthenticationUtils.configureOauthCertificateVolumes("oauth-" + ListenersUtils.identifier(listener), oauth.getTlsTrustedCertificates(), isOpenShift));
            }

            if (isListenerWithCustomAuth(listener)) {
                KafkaListenerAuthenticationCustom custom = (KafkaListenerAuthenticationCustom) listener.getAuth();
                volumeList.addAll(AuthenticationUtils.configureGenericSecretVolumes("custom-listener-" + ListenersUtils.identifier(listener), custom.getSecrets(), isOpenShift));
            }
        }

        if (authorization instanceof KafkaAuthorizationOpa opaAuthz) {
            volumeList.addAll(AuthenticationUtils.configureOauthCertificateVolumes("authz-opa", opaAuthz.getTlsTrustedCertificates(), isOpenShift));
        }

        if (authorization instanceof KafkaAuthorizationKeycloak keycloakAuthz) {
            volumeList.addAll(AuthenticationUtils.configureOauthCertificateVolumes("authz-keycloak", keycloakAuthz.getTlsTrustedCertificates(), isOpenShift));
        }

        return volumeList;
    }

    /**
     * Generates a list of volumes used by PodSets. For StrimziPodSet, it needs to include also all persistent claim
     * volumes which StatefulSet would generate on its own.
     *
     * @param podName       Name of the pod used to name the volumes
     * @param storage       Storage for which the volumes should be generated
     * @param templatePod   Pod template with pod customizations
     * @param isOpenShift   Flag whether we are on OpenShift or not
     *
     * @return              List of volumes to be included in the StrimziPodSet pod
     */
    private List<Volume> getPodSetVolumes(String podName, Storage storage, PodTemplate templatePod, boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>();

        volumeList.addAll(VolumeUtils.createPodSetVolumes(podName, storage, false));
        volumeList.addAll(getNonDataVolumes(isOpenShift, podName, templatePod));

        return volumeList;
    }

    /**
     * Generates the volume mounts for a Kafka container
     *
     * @param storage   Storage configuration for which the volume mounts should be generated
     *
     * @return  List of volume mounts
     */
    private List<VolumeMount> getVolumeMounts(Storage storage) {
        List<VolumeMount> volumeMountList = new ArrayList<>(VolumeUtils.createVolumeMounts(storage, DATA_VOLUME_MOUNT_PATH, false));
        volumeMountList.add(VolumeUtils.createTempDirVolumeMount());
        volumeMountList.add(VolumeUtils.createVolumeMount(CLUSTER_CA_CERTS_VOLUME, CLUSTER_CA_CERTS_VOLUME_MOUNT));
        volumeMountList.add(VolumeUtils.createVolumeMount(BROKER_CERTS_VOLUME, BROKER_CERTS_VOLUME_MOUNT));
        volumeMountList.add(VolumeUtils.createVolumeMount(CLIENT_CA_CERTS_VOLUME, CLIENT_CA_CERTS_VOLUME_MOUNT));
        volumeMountList.add(VolumeUtils.createVolumeMount(LOG_AND_METRICS_CONFIG_VOLUME_NAME, LOG_AND_METRICS_CONFIG_VOLUME_MOUNT));
        volumeMountList.add(VolumeUtils.createVolumeMount("ready-files", "/var/opt/kafka"));

        if (rack != null || isExposedWithNodePort()) {
            volumeMountList.add(VolumeUtils.createVolumeMount(INIT_VOLUME_NAME, INIT_VOLUME_MOUNT));
        }

        for (GenericKafkaListener listener : listeners) {
            String identifier = ListenersUtils.identifier(listener);

            if (listener.isTls()
                    && listener.getConfiguration() != null
                    && listener.getConfiguration().getBrokerCertChainAndKey() != null)  {
                volumeMountList.add(VolumeUtils.createVolumeMount("custom-" + identifier + "-certs", "/opt/kafka/certificates/custom-" + identifier + "-certs"));
            }

            if (isListenerWithOAuth(listener))   {
                KafkaListenerAuthenticationOAuth oauth = (KafkaListenerAuthenticationOAuth) listener.getAuth();
                volumeMountList.addAll(AuthenticationUtils.configureOauthCertificateVolumeMounts("oauth-" + identifier, oauth.getTlsTrustedCertificates(), TRUSTED_CERTS_BASE_VOLUME_MOUNT + "/oauth-" + identifier + "-certs"));
            }

            if (isListenerWithCustomAuth(listener)) {
                KafkaListenerAuthenticationCustom custom = (KafkaListenerAuthenticationCustom) listener.getAuth();
                volumeMountList.addAll(AuthenticationUtils.configureGenericSecretVolumeMounts("custom-listener-" + identifier, custom.getSecrets(), CUSTOM_AUTHN_SECRETS_VOLUME_MOUNT + "/custom-listener-" + identifier));
            }
        }

        if (authorization instanceof KafkaAuthorizationOpa opaAuthz) {
            volumeMountList.addAll(AuthenticationUtils.configureOauthCertificateVolumeMounts("authz-opa", opaAuthz.getTlsTrustedCertificates(), TRUSTED_CERTS_BASE_VOLUME_MOUNT + "/authz-opa-certs"));
        }

        if (authorization instanceof KafkaAuthorizationKeycloak keycloakAuthz) {
            volumeMountList.addAll(AuthenticationUtils.configureOauthCertificateVolumeMounts("authz-keycloak", keycloakAuthz.getTlsTrustedCertificates(), TRUSTED_CERTS_BASE_VOLUME_MOUNT + "/authz-keycloak-certs"));
        }

        return volumeMountList;
    }

    /**
     * Returns a combined affinity: Adding the affinity needed for the "kafka-rack" to the user-provided affinity.
     *
     * @param pool  Node pool with custom affinity configuration
     *
     * @return  Combined affinity
     */
    protected Affinity getMergedAffinity(KafkaPool pool) {
        Affinity userAffinity = pool.templatePod != null && pool.templatePod.getAffinity() != null ? pool.templatePod.getAffinity() : new Affinity();
        AffinityBuilder builder = new AffinityBuilder(userAffinity);
        if (rack != null) {
            // If there's a rack config, we need to add a podAntiAffinity to spread the brokers among the racks
            builder = builder
                    .editOrNewPodAntiAffinity()
                        .addNewPreferredDuringSchedulingIgnoredDuringExecution()
                            .withWeight(100)
                            .withNewPodAffinityTerm()
                                .withTopologyKey(rack.getTopologyKey())
                                .withNewLabelSelector()
                                    .addToMatchLabels(Labels.STRIMZI_CLUSTER_LABEL, cluster)
                                    .addToMatchLabels(Labels.STRIMZI_NAME_LABEL, componentName)
                                .endLabelSelector()
                            .endPodAffinityTerm()
                        .endPreferredDuringSchedulingIgnoredDuringExecution()
                    .endPodAntiAffinity();

            builder = ModelUtils.populateAffinityBuilderWithRackLabelSelector(builder, userAffinity, rack.getTopologyKey());
        }

        return builder.build();
    }

    protected List<EnvVar> getInitContainerEnvVars(KafkaPool pool) {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(ContainerUtils.createEnvVarFromFieldRef(ENV_VAR_KAFKA_INIT_NODE_NAME, "spec.nodeName"));

        if (rack != null) {
            varList.add(ContainerUtils.createEnvVar(ENV_VAR_KAFKA_INIT_RACK_TOPOLOGY_KEY, rack.getTopologyKey()));
        }

        if (!ListenersUtils.nodePortListeners(listeners).isEmpty()) {
            varList.add(ContainerUtils.createEnvVar(ENV_VAR_KAFKA_INIT_EXTERNAL_ADDRESS, "TRUE"));
        }

        // Add shared environment variables used for all containers
        varList.addAll(sharedEnvironmentProvider.variables());

        ContainerUtils.addContainerEnvsToExistingEnvs(reconciliation, varList, pool.templateInitContainer);

        return varList;
    }

    /* test */ Container createInitContainer(ImagePullPolicy imagePullPolicy, KafkaPool pool) {
        if (rack != null || !ListenersUtils.nodePortListeners(listeners).isEmpty()) {
            return ContainerUtils.createContainer(
                    INIT_NAME,
                    initImage,
                    List.of("/opt/strimzi/bin/kafka_init_run.sh"),
                    securityProvider.kafkaInitContainerSecurityContext(new ContainerSecurityProviderContextImpl(pool.templateInitContainer)),
                    pool.resources,
                    getInitContainerEnvVars(pool),
                    null,
                    List.of(VolumeUtils.createVolumeMount(INIT_VOLUME_NAME, INIT_VOLUME_MOUNT)),
                    null,
                    null,
                    imagePullPolicy
            );
        } else {
            return null;
        }
    }

    /**
     * Creates the Kafka container
     *
     * @param imagePullPolicy   Image pull policy configuration
     * @param pool              Node pool for which is this container generated
     *
     * @return  Kafka container
     */
    /* test */ Container createContainer(ImagePullPolicy imagePullPolicy, KafkaPool pool) {
        return ContainerUtils.createContainer(
                KAFKA_NAME,
                image,
                List.of("/opt/kafka/kafka_run.sh"),
                securityProvider.kafkaContainerSecurityContext(new ContainerSecurityProviderContextImpl(pool.storage, pool.templateContainer)),
                pool.resources,
                getEnvVars(pool),
                getContainerPortList(),
                getVolumeMounts(pool.storage),
                ProbeUtils.defaultBuilder(livenessProbeOptions).withNewExec().withCommand("/opt/kafka/kafka_liveness.sh").endExec().build(),
                ProbeUtils.defaultBuilder(readinessProbeOptions).withNewExec().withCommand("/opt/kafka/kafka_readiness.sh").endExec().build(),
                imagePullPolicy
        );
    }

    /**
     * Generates environment variables for the Kafka container
     *
     * @param pool  Pool to which this container belongs
     *
     * @return  List of environment variables
     */
    protected List<EnvVar> getEnvVars(KafkaPool pool) {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(ContainerUtils.createEnvVar(ENV_VAR_KAFKA_METRICS_ENABLED, String.valueOf(metrics.isEnabled())));
        varList.add(ContainerUtils.createEnvVar(ENV_VAR_STRIMZI_KAFKA_GC_LOG_ENABLED, String.valueOf(pool.gcLoggingEnabled)));

        JvmOptionUtils.heapOptions(varList, 50, 5L * 1024L * 1024L * 1024L, pool.jvmOptions, pool.resources);
        JvmOptionUtils.jvmPerformanceOptions(varList, pool.jvmOptions);
        JvmOptionUtils.jvmSystemProperties(varList, pool.jvmOptions);

        for (GenericKafkaListener listener : listeners) {
            if (isListenerWithOAuth(listener))   {
                KafkaListenerAuthenticationOAuth oauth = (KafkaListenerAuthenticationOAuth) listener.getAuth();

                if (oauth.getClientSecret() != null)    {
                    varList.add(ContainerUtils.createEnvVarFromSecret("STRIMZI_" + ListenersUtils.envVarIdentifier(listener) + "_OAUTH_CLIENT_SECRET", oauth.getClientSecret().getSecretName(), oauth.getClientSecret().getKey()));
                }
            }
        }

        if (useKRaft)   {
            varList.add(ContainerUtils.createEnvVar(ENV_VAR_STRIMZI_CLUSTER_ID, clusterId));
            varList.add(ContainerUtils.createEnvVar(ENV_VAR_STRIMZI_KRAFT_ENABLED, "true"));
        }

        varList.addAll(jmx.envVars());

        // Add shared environment variables used for all containers
        varList.addAll(sharedEnvironmentProvider.variables());

        // Add user defined environment variables to the Kafka broker containers
        ContainerUtils.addContainerEnvsToExistingEnvs(reconciliation, varList, pool.templateContainer);

        return varList;
    }

    /**
     * Creates the ClusterRoleBinding which is used to bind the Kafka SA to the ClusterRole
     * which permissions the Kafka init container to access K8S nodes (necessary for rack-awareness).
     *
     * @param assemblyNamespace The namespace.
     * @return The cluster role binding.
     */
    public ClusterRoleBinding generateClusterRoleBinding(String assemblyNamespace) {
        if (rack != null || isExposedWithNodePort()) {
            Subject subject = new SubjectBuilder()
                    .withKind("ServiceAccount")
                    .withName(componentName)
                    .withNamespace(assemblyNamespace)
                    .build();

            RoleRef roleRef = new RoleRefBuilder()
                    .withName("strimzi-kafka-broker")
                    .withApiGroup("rbac.authorization.k8s.io")
                    .withKind("ClusterRole")
                    .build();

            return RbacUtils
                    .createClusterRoleBinding(KafkaResources.initContainerClusterRoleBindingName(cluster, namespace), roleRef, List.of(subject), labels, templateInitClusterRoleBinding);
        } else {
            return null;
        }
    }

    /**
     * Generates the NetworkPolicies relevant for Kafka brokers
     *
     * @param operatorNamespace                             Namespace where the Strimzi Cluster Operator runs. Null if not configured.
     * @param operatorNamespaceLabels                       Labels of the namespace where the Strimzi Cluster Operator runs. Null if not configured.
     *
     * @return The network policy.
     */
    public NetworkPolicy generateNetworkPolicy(String operatorNamespace, Labels operatorNamespaceLabels) {
        // Internal peers => Strimzi components which need access
        NetworkPolicyPeer clusterOperatorPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_KIND_LABEL, "cluster-operator"), NetworkPolicyUtils.clusterOperatorNamespaceSelector(namespace, operatorNamespace, operatorNamespaceLabels));
        NetworkPolicyPeer kafkaClusterPeer = NetworkPolicyUtils.createPeer(labels.strimziSelectorLabels().toMap());
        NetworkPolicyPeer entityOperatorPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_NAME_LABEL, KafkaResources.entityOperatorDeploymentName(cluster)));
        NetworkPolicyPeer kafkaExporterPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_NAME_LABEL, KafkaExporterResources.deploymentName(cluster)));
        NetworkPolicyPeer cruiseControlPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_NAME_LABEL, CruiseControlResources.deploymentName(cluster)));

        // List of network policy rules for all ports
        List<NetworkPolicyIngressRule> rules = new ArrayList<>();

        // Control Plane rule covers the control plane listener.
        // Control plane listener is used by Kafka for internal coordination only
        rules.add(NetworkPolicyUtils.createIngressRule(CONTROLPLANE_PORT, List.of(kafkaClusterPeer)));

        // Replication rule covers the replication listener.
        // Replication listener is used by Kafka but also by our own tools => Operators, Cruise Control, and Kafka Exporter
        rules.add(NetworkPolicyUtils.createIngressRule(REPLICATION_PORT, List.of(clusterOperatorPeer, kafkaClusterPeer, entityOperatorPeer, kafkaExporterPeer, cruiseControlPeer)));

        // KafkaAgent rule covers the KafkaAgent listener.
        // KafkaAgent listener is used by our own tool => Operators
        rules.add(NetworkPolicyUtils.createIngressRule(KAFKA_AGENT_PORT, List.of(clusterOperatorPeer)));

        // User-configured listeners are by default open for all. Users can pass peers in the Kafka CR.
        for (GenericKafkaListener listener : listeners) {
            rules.add(NetworkPolicyUtils.createIngressRule(listener.getPort(), listener.getNetworkPolicyPeers()));
        }

        // The Metrics port (if enabled) is opened to all by default
        if (metrics.isEnabled()) {
            rules.add(NetworkPolicyUtils.createIngressRule(MetricsModel.METRICS_PORT, List.of()));
        }

        // The JMX port (if enabled) is opened to all by default
        rules.addAll(jmx.networkPolicyIngresRules());

        // Build the final network policy with all rules covering all the ports
        return NetworkPolicyUtils.createNetworkPolicy(
                KafkaResources.kafkaNetworkPolicyName(cluster),
                namespace,
                labels,
                ownerReference,
                rules
        );
    }

    /**
     * Generates the PodDisruptionBudget.
     *
     * @return The PodDisruptionBudget.
     */
    public PodDisruptionBudget generatePodDisruptionBudget() {
        return PodDisruptionBudgetUtils.createCustomControllerPodDisruptionBudget(componentName, namespace, labels, ownerReference, templatePodDisruptionBudget, nodes().size());
    }

    /**
     * @return The listeners
     */
    public List<GenericKafkaListener> getListeners() {
        return listeners;
    }

    /**
     * Returns true when the Kafka cluster is exposed to the outside using NodePort type services
     *
     * @return true when the Kafka cluster is exposed to the outside using NodePort.
     */
    private boolean isExposedWithNodePort() {
        return ListenersUtils.hasNodePortListener(listeners);
    }

    /**
     * Returns true when the Kafka cluster is exposed to the outside of Kubernetes using Ingress
     *
     * @return true when the Kafka cluster is exposed using Kubernetes Ingress.
     */
    /* test */ boolean isExposedWithIngress() {
        return ListenersUtils.hasIngressListener(listeners);
    }

    /**
     * Returns true when the Kafka cluster is exposed to the outside of Kubernetes using ClusterIP services
     *
     * @return true when the Kafka cluster is exposed using Kubernetes Ingress with TCP mode.
     */
    /* test */ boolean isExposedWithClusterIP() {
        return ListenersUtils.hasClusterIPListener(listeners);
    }

    /**
     * Returns the configuration of the Kafka cluster. This method is currently used by the KafkaSpecChecker to get the
     * Kafka configuration and check it for warnings.
     *
     * @return  Kafka cluster configuration
     */
    public KafkaConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Generates the individual Kafka broker configuration. This configuration uses only minimum of placeholders - for
     * values which are known only inside the pod such as secret values (e.g. OAuth client secrets), NodePort addresses
     * or Rack IDs. All other values such as broker IDs, advertised ports or hostnames are already prefilled in the
     * configuration. This method is normally used with StrimziPodSets.
     *
     * @param nodeId              ID of the broker for which is this configuration generated
     * @param advertisedHostnames Map with advertised hostnames for different listeners
     * @param advertisedPorts     Map with advertised ports for different listeners
     *
     * @return The Kafka broker configuration as a String
     */
    public String generatePerBrokerBrokerConfiguration(int nodeId, Map<Integer, Map<String, String>> advertisedHostnames, Map<Integer, Map<String, String>> advertisedPorts)   {
        KafkaPool pool = nodePoolForNodeId(nodeId);

        return generatePerBrokerBrokerConfiguration(
                pool.nodeRef(nodeId),
                pool,
                advertisedHostnames,
                advertisedPorts
        );
    }

    /**
     * Internal method used to generate a Kafka configuration for given broker node.
     *
     * @param node                  Node reference with Node ID and pod name
     * @param pool                  Pool to which this node belongs - this is used to get pool-specific settings such as storage
     * @param advertisedHostnames   Map with advertised hostnames
     * @param advertisedPorts       Map with advertised ports
     *
     * @return  String with the Kafka broker configuration
     */
    private String generatePerBrokerBrokerConfiguration(NodeRef node, KafkaPool pool, Map<Integer, Map<String, String>> advertisedHostnames, Map<Integer, Map<String, String>> advertisedPorts)   {
        if (useKRaft) {
            return new KafkaBrokerConfigurationBuilder(reconciliation, String.valueOf(node.nodeId()), true)
                    .withRackId(rack)
                    .withKRaft(cluster, namespace, pool.processRoles, nodes())
                    .withLogDirs(VolumeUtils.createVolumeMounts(pool.storage, DATA_VOLUME_MOUNT_PATH, false))
                    .withListeners(cluster,
                            namespace,
                            node,
                            listeners,
                            listenerId -> advertisedHostnames.get(node.nodeId()).get(listenerId),
                            listenerId -> advertisedPorts.get(node.nodeId()).get(listenerId)
                    )
                    .withAuthorization(cluster, authorization)
                    .withCruiseControl(cluster, ccMetricsReporter, node.broker())
                    .withUserConfiguration(configuration, node.broker() && ccMetricsReporter != null)
                    .build().trim();
        } else {
            return new KafkaBrokerConfigurationBuilder(reconciliation, String.valueOf(node.nodeId()), false)
                    .withRackId(rack)
                    .withZookeeper(cluster)
                    .withLogDirs(VolumeUtils.createVolumeMounts(pool.storage, DATA_VOLUME_MOUNT_PATH, false))
                    .withListeners(cluster,
                            namespace,
                            node,
                            listeners,
                            listenerId -> advertisedHostnames.get(node.nodeId()).get(listenerId),
                            listenerId -> advertisedPorts.get(node.nodeId()).get(listenerId)
                    )
                    .withAuthorization(cluster, authorization)
                    .withCruiseControl(cluster, ccMetricsReporter, node.broker())
                    .withUserConfiguration(configuration, node.broker() && ccMetricsReporter != null)
                    .build().trim();
        }
    }

    /**
     * Generates a list of configuration ConfigMaps - one for each broker in the cluster. The ConfigMaps contain the
     * configurations which should be used by given broker. This is used with StrimziPodSets.
     *
     * @param metricsAndLogging   Object with logging and metrics configuration collected from external user-provided config maps
     * @param advertisedHostnames Map with advertised hostnames for different brokers and listeners
     * @param advertisedPorts     Map with advertised ports for different brokers and listeners
     *
     * @return ConfigMap with the shared configuration.
     */
    public List<ConfigMap> generatePerBrokerConfigurationConfigMaps(MetricsAndLogging metricsAndLogging, Map<Integer, Map<String, String>> advertisedHostnames, Map<Integer, Map<String, String>> advertisedPorts)   {
        String parsedMetrics = metrics.metricsJson(reconciliation, metricsAndLogging.metricsCm());
        String parsedLogging = logging().loggingConfiguration(reconciliation, metricsAndLogging.loggingCm());
        List<ConfigMap> configMaps = new ArrayList<>();

        for (KafkaPool pool : nodePools)    {
            for (NodeRef node : pool.nodes())   {
                Map<String, String> data = new HashMap<>(4);

                if (parsedMetrics != null) {
                    data.put(MetricsModel.CONFIG_MAP_KEY, parsedMetrics);
                }

                data.put(logging.configMapKey(), parsedLogging);
                data.put(BROKER_CONFIGURATION_FILENAME, generatePerBrokerBrokerConfiguration(node, pool, advertisedHostnames, advertisedPorts));
                // List of configured listeners => StrimziPodSets still need this because of OAUTH and how the OAUTH secret
                // environment variables are parsed in the container bash scripts
                data.put(BROKER_LISTENERS_FILENAME, listeners.stream().map(ListenersUtils::envVarIdentifier).collect(Collectors.joining(" ")));

                configMaps.add(ConfigMapUtils.createConfigMap(node.podName(), namespace, pool.labels.withStrimziPodName(node.podName()), pool.ownerReference, data));

            }
        }

        return configMaps;
    }

    /**
     * @return  Kafka version
     */
    public KafkaVersion getKafkaVersion() {
        return this.kafkaVersion;
    }

    /**
     * @return  Kafka's log message format configuration
     */
    public String getLogMessageFormatVersion() {
        return configuration.getConfigOption(KafkaConfiguration.LOG_MESSAGE_FORMAT_VERSION);
    }

    /**
     * Sets the log message format version
     *
     * @param logMessageFormatVersion       Log message format version
     */
    public void setLogMessageFormatVersion(String logMessageFormatVersion) {
        configuration.setConfigOption(KafkaConfiguration.LOG_MESSAGE_FORMAT_VERSION, logMessageFormatVersion);
    }

    /**
     * @return  Kafka's inter-broker protocol configuration
     */
    public String getInterBrokerProtocolVersion() {
        return configuration.getConfigOption(KafkaConfiguration.INTERBROKER_PROTOCOL_VERSION);
    }

    /**
     * Sets the inter-broker protocol version
     *
     * @param interBrokerProtocolVersion    Inter-broker protocol version
     */
    public void setInterBrokerProtocolVersion(String interBrokerProtocolVersion) {
        configuration.setConfigOption(KafkaConfiguration.INTERBROKER_PROTOCOL_VERSION, interBrokerProtocolVersion);
    }

    /**
     * @return  JMX Model instance for configuring JMX access
     */
    public JmxModel jmx()   {
        return jmx;
    }

    /**
     * @return  Metrics Model instance for configuring Prometheus metrics
     */
    public MetricsModel metrics()   {
        return metrics;
    }

    /**
     * @return  Logging Model instance for configuring logging
     */
    public LoggingModel logging()   {
        return logging;
    }

    /**
     * @return A Map with the storage configuration used by the different node pools. The key in the map is the name of
     *         the node pool and the value is the storage configuration from the custom resource. The map includes the
     *         storage for both broker and controller pools as it is used also for Storage validation.
     */
    public Map<String, Storage> getStorageByPoolName() {
        Map<String, Storage> storage = new HashMap<>();

        for (KafkaPool pool : nodePools)    {
            storage.put(pool.poolName, pool.storage);
        }

        return storage;
    }

    /**
     * @return A Map with the resources configuration used by the different node pool with brokers. the key in the map
     *         is the name of the node pool and the value is the ResourceRequirements configuration from the custom
     *         resource. The map includes only pools with broker role. Controller-only node pools are not included.
     */
    public Map<String, ResourceRequirements> getBrokerResourceRequirementsByPoolName() {
        Map<String, ResourceRequirements> resources = new HashMap<>();

        for (KafkaPool pool : nodePools)    {
            if (pool.isBroker()) {
                resources.put(pool.poolName, pool.resources);
            }
        }

        return resources;
    }

    /**
     * @return  Returns a list of warning conditions set by the model and the pool models. Returns an empty list if no
     *          warning conditions were set.
     */
    public List<Condition> getWarningConditions() {
        List<Condition> consolidatedWarningConditions = new ArrayList<>();

        if (warningConditions != null) {
            consolidatedWarningConditions.addAll(warningConditions);
        }

        for (KafkaPool pool : nodePools)    {
            if (pool.warningConditions != null) {
                consolidatedWarningConditions.addAll(pool.warningConditions);
            }
        }

        return consolidatedWarningConditions;
    }

    /**
     * When KRaft is enabled, not all nodes might act as brokers as some might be controllers only. So some services
     * such as the bootstrap services should not route to the controller-only nodes but only to the nodes with the broker role.
     *
     * @return  A regular Strimzi selector labels when KRaft is disabled. Or selector labels for targeting only the
     *          broker nodes when KRaft is enabled.
     */
    private Labels brokersSelector()    {
        if (useKRaft)   {
            return labels.strimziSelectorLabels().withStrimziBrokerRole(true);
        } else {
            return labels.strimziSelectorLabels();
        }
    }
}

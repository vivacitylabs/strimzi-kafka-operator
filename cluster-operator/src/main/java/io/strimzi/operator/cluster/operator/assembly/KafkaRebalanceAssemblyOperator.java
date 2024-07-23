/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaList;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.CruiseControlResources;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceAnnotation;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceBuilder;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceList;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceMode;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceSpec;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceState;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceStatus;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceStatusBuilder;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.CruiseControl;
import io.strimzi.operator.cluster.model.ModelUtils;
import io.strimzi.operator.cluster.model.NoSuchResourceException;
import io.strimzi.operator.cluster.model.cruisecontrol.CruiseControlConfiguration;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.AbstractRebalanceOptions;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.AddBrokerOptions;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApi;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApiImpl;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlRebalanceResponse;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlRestException;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.RebalanceOptions;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.RemoveBrokerOptions;
import io.strimzi.operator.cluster.operator.resource.kubernetes.AbstractWatchableStatusedNamespacedResourceOperator;
import io.strimzi.operator.cluster.operator.resource.kubernetes.ConfigMapOperator;
import io.strimzi.operator.cluster.operator.resource.kubernetes.CrdOperator;
import io.strimzi.operator.cluster.operator.resource.kubernetes.SecretOperator;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.InvalidResourceException;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.StatusUtils;
import io.strimzi.operator.common.model.cruisecontrol.CruiseControlLoadParameters;
import io.strimzi.operator.common.model.cruisecontrol.CruiseControlRebalanceKeys;
import io.strimzi.operator.common.model.cruisecontrol.CruiseControlUserTaskStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApiImpl.HTTP_DEFAULT_IDLE_TIMEOUT_SECONDS;
import static io.strimzi.operator.common.Annotations.ANNO_STRIMZI_IO_REBALANCE;
import static io.strimzi.operator.common.Annotations.ANNO_STRIMZI_IO_REBALANCE_AUTOAPPROVAL;

/**
 * <p>Assembly operator for a "KafkaRebalance" assembly, which interacts with the Cruise Control REST API</p>
 *
 * <p>
 *     This operator takes care of the {@code KafkaRebalance} custom resource type that a user can create in order
 *     to interact with the Cruise Control REST API and execute a cluster rebalance.
 *     A state machine is used for the rebalancing flow which is reflected in the {@code status} of the custom resource.
 *
 *     When a new {@code KafkaRebalance} custom resource is created, the operator sends a rebalance proposal
 *     request to the Cruise Control REST API and moves to the {@code PendingProposal} state. It stays in this state
 *     until a the rebalance proposal is ready, polling the related status on Cruise Control, and then finally moves
 *     to the {@code ProposalReady} state. The status of the {@code KafkaRebalance} custom resource is updated with the
 *     computed rebalance proposal so that the user can view it and making a decision to execute it or not.
 *
 *     For starting the actual rebalance on the cluster, the user annotates the custom resource with
 *     the {@code strimzi.io/rebalance=approve} annotation, triggering the operator to send a rebalance request to the
 *     Cruise Control REST API in order to execute the rebalancing.
 *     During the rebalancing, the operator state machine is in the {@code Rebalancing} state and it moves finally
 *     to the {@code Ready} state when the rebalancing is done.
 *
 *     The user is able to stop the retrieval of an in-progress rebalance proposal computation (if it is taking a long
 *     time and they no longer want it) by annotating the custom resource with {@code strimzi.io/rebalance=stop} when
 *     it is in the {@code PendingProposal} state. This will prevent the operator waiting for Cruise Control to complete
 *     the proposal. There is no way to stop the preparation of a optimization proposal on the Cruise Control side. The
 *     operator then moves to the {@code Stopped} state and the user can request a new proposal by applying the
 *     {@code strimzi.io/rebalance=refresh} annotation on the custom resource.
 *
 *     The user can stop an ongoing rebalance by annotating the custom resource with {@code strimzi.io/rebalance=stop}
 *     when it is in the {@code Rebalancing} state. The operator then moves to the {@code Stopped} state. The ongoing
 *     partition reassignment will complete and further reassignments will be cancelled. The user can request a new
 *     proposal by applying the {@code strimzi.io/rebalance=refresh} annotation on the custom resource.
 *
 *     Finally, when a proposal is ready but the user has not approved it right after the computation, the proposal may
 *     be in a stale state as the cluster conditions might be changed. The proposal can be refreshed by annotating
 *     the custom resource with the {@code strimzi.io/rebalance=refresh} annotation.
 * </p>
 * <pre><code>
 *   User        Kube           Operator              CC
 *    | Create KR  |               |                   |
 *    |-----------→|   Watch       |                   |
 *    |            |--------------→|   Proposal        |
 *    |            |               |------------------→|
 *    |            |               |   Poll            |
 *    |            |               |------------------→|
 *    |            |               |   Poll            |
 *    |            | Update Status |------------------→|
 *    |            |←--------------|                   |
 *    |            |   Watch       |                   |
 *    |            |--------------→|                   |
 *    | Get        |               |                   |
 *    |-----------→|               |                   |
 *    |            |               |                   |
 *    | Approve    |               |                   |
 *    |-----------→|  Watch        |                   |
 *    |            |--------------→|   Rebalance       |
 *    |            |               |------------------→|
 *    |            |               |   Poll            |
 *    |            |               |------------------→|
 *    |            |               |   Poll            |
 *    |            | Update Status |------------------→|
 *    |            |←--------------|                   |
 *    |            |   Watch       |                   |
 *    |            |--------------→|                   |
 *    | Get        |               |                   |
 *    |-----------→|               |                   |
 * </code></pre>
 */
@SuppressWarnings({"checkstyle:ClassFanOutComplexity"})
public class KafkaRebalanceAssemblyOperator
       extends AbstractOperator<KafkaRebalance, KafkaRebalanceSpec, KafkaRebalanceStatus, AbstractWatchableStatusedNamespacedResourceOperator<KubernetesClient, KafkaRebalance, KafkaRebalanceList, Resource<KafkaRebalance>>> {

    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(KafkaRebalanceAssemblyOperator.class.getName());

    protected static final String BROKER_LOAD_KEY = "brokerLoad.json";
    private final CrdOperator<KubernetesClient, KafkaRebalance, KafkaRebalanceList> kafkaRebalanceOperator;
    private final CrdOperator<KubernetesClient, Kafka, KafkaList> kafkaOperator;
    private final SecretOperator secretOperations;
    private final LabelSelector kafkaSelector;
    private final ConfigMapOperator configMapOperator;
    private int cruiseControlPort;

    /**
     * @param vertx The Vertx instance
     * @param supplier Supplies the operators for different resources
     * @param config Cluster Operator configuration
     */
    public KafkaRebalanceAssemblyOperator(Vertx vertx,
                                          ResourceOperatorSupplier supplier,
                                          ClusterOperatorConfig config) {
        this(vertx, supplier, config, CruiseControl.REST_API_PORT);
    }
    
    /**
     * @param vertx The Vertx instance
     * @param supplier Supplies the operators for different resources
     * @param config Cluster Operator configuration
     * @param cruiseControlPort Cruise Control server port
     */
    /* test */ KafkaRebalanceAssemblyOperator(Vertx vertx,
                                              ResourceOperatorSupplier supplier, 
                                              ClusterOperatorConfig config,
                                              int cruiseControlPort) {
        super(vertx, KafkaRebalance.RESOURCE_KIND, supplier.kafkaRebalanceOperator, supplier.metricsProvider, null);
        this.kafkaSelector = (config.getCustomResourceSelector() == null || config.getCustomResourceSelector().toMap().isEmpty()) 
            ? null : new LabelSelector(null, config.getCustomResourceSelector().toMap());
        this.kafkaRebalanceOperator = supplier.kafkaRebalanceOperator;
        this.kafkaOperator = supplier.kafkaOperator;
        this.configMapOperator = supplier.configMapOperations;
        this.secretOperations = supplier.secretOperations;
        this.cruiseControlPort = cruiseControlPort;
    }

    /**
     * Provides an implementation of the Cruise Control API client
     *
     * @param ccSecret Cruise Control secret
     * @param ccApiSecret Cruise Control API secret
     * @param apiAuthEnabled if enabled, configures auth
     * @param apiSslEnabled if enabled, configures SSL
     * @return Cruise Control API client instance
     */
    public CruiseControlApi cruiseControlClientProvider(Secret ccSecret, Secret ccApiSecret,
                                                           boolean apiAuthEnabled, boolean apiSslEnabled) {
        return new CruiseControlApiImpl(vertx, HTTP_DEFAULT_IDLE_TIMEOUT_SECONDS, ccSecret, ccApiSecret, apiAuthEnabled, apiSslEnabled);
    }

    /**
     * The Cruise Control hostname to connect to
     *
     * @param clusterName the Kafka cluster resource name
     * @param clusterNamespace the namespace of the Kafka cluster
     * @return the Cruise Control hostname to connect to
     */
    protected String cruiseControlHost(String clusterName, String clusterNamespace) {
        return CruiseControlResources.qualifiedServiceName(clusterName, clusterNamespace);
    }

    /**
     * Searches through the conditions in the supplied status instance and finds those whose type matches one of the values defined
     * in the {@link KafkaRebalanceState} enum.
     * If there are none it will return null.
     * If there is only one it will return that Condition.
     * If there are more than one it will throw a RuntimeException.
     *
     * @param status The KafkaRebalanceStatus instance whose conditions will be searched.
     * @return The Condition instance from the supplied status that has a type value matching one of the values of the
     *         {@link KafkaRebalanceState} enum. If none are found then the method will return null.
     * @throws RuntimeException If there is more than one Condition instance in the supplied status whose type matches one of the
     *                          {@link KafkaRebalanceState} enum values.
     */
    /* test */ protected Condition rebalanceStateCondition(KafkaRebalanceStatus status) {
        if (status.getConditions() != null) {

            List<Condition> statusConditions = status.getConditions()
                    .stream()
                    .filter(condition -> condition.getType() != null)
                    .filter(condition -> Arrays.stream(KafkaRebalanceState.values())
                            .anyMatch(stateValue -> stateValue.toString().equals(condition.getType())))
                    .toList();

            if (statusConditions.size() == 1) {
                return statusConditions.get(0);
            } else if (statusConditions.size() > 1) {
                throw new RuntimeException("Multiple KafkaRebalance State Conditions were present in the KafkaRebalance status");
            }
        }
        // If there are no conditions or none that have the correct status
        return null;
    }

    /**
     * Searches through the conditions in the supplied status instance and finds those whose type matches one of the values defined
     * in the {@link KafkaRebalanceState} enum.
     * If there are none it will return null.
     * If there are more than one it will throw a RuntimeException.
     * If there is only one it will return that Condition's type as a string.
     *
     * @param status The status instance whose conditions will be searched.
     * @return The type of the rebalance status condition.
     * @throws RuntimeException If there is more than one Condition instance in the supplied status whose type matches one of the
     *                          {@link KafkaRebalanceState} enum values.
     */
    private String rebalanceStateConditionType(KafkaRebalanceStatus status) {
        Condition rebalanceStateCondition = rebalanceStateCondition(status);
        return rebalanceStateCondition != null ? rebalanceStateCondition.getType() : null;
    }

    private KafkaRebalanceStatus updateStatus(KafkaRebalance kafkaRebalance,
                                              KafkaRebalanceStatus desiredStatus,
                                              Throwable e) {
        // Leave the current status when the desired state is null
        if (desiredStatus != null) {

            Condition cond = rebalanceStateCondition(desiredStatus);

            List<Condition> previous = Collections.emptyList();
            if (desiredStatus.getConditions() != null) {
                previous = desiredStatus.getConditions().stream().filter(condition -> condition != cond).collect(Collectors.toList());
            }

            // If a throwable is supplied, it is set in the status with priority
            if (e != null) {
                StatusUtils.setStatusConditionAndObservedGeneration(kafkaRebalance, desiredStatus, KafkaRebalanceState.NotReady.toString(), e);
                desiredStatus.setConditions(Stream.concat(desiredStatus.getConditions().stream(), previous.stream()).collect(Collectors.toList()));
            } else if (cond != null) {
                StatusUtils.setStatusConditionAndObservedGeneration(kafkaRebalance, desiredStatus, cond);
                desiredStatus.setConditions(Stream.concat(desiredStatus.getConditions().stream(), previous.stream()).collect(Collectors.toList()));
            } else {
                throw new IllegalArgumentException("Status related exception and the Status condition's type cannot both be null");
            }
            return desiredStatus;
        }
        return kafkaRebalance.getStatus();
    }

    /* test */ AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> convertRebalanceSpecToRebalanceOptions(KafkaRebalanceSpec kafkaRebalanceSpec) {

        AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder;
        // backward compatibility, no mode specified means "full"
        KafkaRebalanceMode mode = Optional.ofNullable(kafkaRebalanceSpec)
                .map(kr -> kr.getMode())
                .orElse(KafkaRebalanceMode.FULL);
        List<Integer> brokers = Optional.ofNullable(kafkaRebalanceSpec)
                .map(kr -> kr.getBrokers())
                .orElse(null);

        switch (mode) {
            case ADD_BROKERS:
                rebalanceOptionsBuilder = new AddBrokerOptions.AddBrokerOptionsBuilder();
                if (brokers != null && !brokers.isEmpty()) {
                    ((AddBrokerOptions.AddBrokerOptionsBuilder) rebalanceOptionsBuilder).withBrokers(brokers);
                } else {
                    throw new IllegalArgumentException("The brokers list is mandatory when using the " + mode.toValue() + " rebalancing mode");
                }
                break;
            case REMOVE_BROKERS:
                rebalanceOptionsBuilder = new RemoveBrokerOptions.RemoveBrokerOptionsBuilder();
                if (brokers != null && !brokers.isEmpty()) {
                    ((RemoveBrokerOptions.RemoveBrokerOptionsBuilder) rebalanceOptionsBuilder).withBrokers(brokers);
                } else {
                    throw new IllegalArgumentException("The brokers list is mandatory when using the " + mode.toValue() + " rebalancing mode");
                }
                break;
            default:
                rebalanceOptionsBuilder = new RebalanceOptions.RebalanceOptionsBuilder();
                if (kafkaRebalanceSpec.isRebalanceDisk()) {
                    ((RebalanceOptions.RebalanceOptionsBuilder) rebalanceOptionsBuilder).withRebalanceDisk();
                }
                if (kafkaRebalanceSpec.getConcurrentIntraBrokerPartitionMovements() > 0) {
                    ((RebalanceOptions.RebalanceOptionsBuilder) rebalanceOptionsBuilder)
                            .withConcurrentIntraPartitionMovements(kafkaRebalanceSpec.getConcurrentIntraBrokerPartitionMovements());
                }
                if (brokers != null && !brokers.isEmpty()) {
                    LOGGER.warnOp("The {} mode is used. The specified list of brokers is ignored", mode.toValue());
                }
                break;
        }

        if (kafkaRebalanceSpec.getGoals() != null) {
            rebalanceOptionsBuilder.withGoals(kafkaRebalanceSpec.getGoals());
        }
        if (kafkaRebalanceSpec.isSkipHardGoalCheck()) {
            rebalanceOptionsBuilder.withSkipHardGoalCheck();
        }
        if (kafkaRebalanceSpec.getExcludedTopics() != null) {
            rebalanceOptionsBuilder.withExcludedTopics(kafkaRebalanceSpec.getExcludedTopics());
        }
        if (kafkaRebalanceSpec.getConcurrentPartitionMovementsPerBroker() > 0) {
            rebalanceOptionsBuilder.withConcurrentPartitionMovementsPerBroker(kafkaRebalanceSpec.getConcurrentPartitionMovementsPerBroker());
        }
        if (kafkaRebalanceSpec.getConcurrentLeaderMovements() > 0) {
            rebalanceOptionsBuilder.withConcurrentLeaderMovements(kafkaRebalanceSpec.getConcurrentLeaderMovements());
        }
        if (kafkaRebalanceSpec.getReplicationThrottle() > 0) {
            rebalanceOptionsBuilder.withReplicationThrottle(kafkaRebalanceSpec.getReplicationThrottle());
        }
        if (kafkaRebalanceSpec.getReplicaMovementStrategies() != null) {
            rebalanceOptionsBuilder.withReplicaMovementStrategies(kafkaRebalanceSpec.getReplicaMovementStrategies());
        }

        return rebalanceOptionsBuilder;
    }

    private Future<KafkaRebalanceStatus> reconcile(Reconciliation reconciliation, String host,
                                                   CruiseControlApi apiClient, KafkaRebalance kafkaRebalance,
                                                   KafkaRebalanceState currentState) {

        if (kafkaRebalance != null && kafkaRebalance.getStatus() != null
                && "Ready".equals(rebalanceStateConditionType(kafkaRebalance.getStatus()))
                && rawRebalanceAnnotation(kafkaRebalance) == null) {
            LOGGER.infoCr(reconciliation, "Rebalancing is completed. You can use the `refresh` annotation to ask for a new rebalance request");
        } else {
            LOGGER.infoCr(reconciliation, "Rebalance action is performed and KafkaRebalance resource is currently in [{}] state", currentState);
        }

        if (Annotations.isReconciliationPausedWithAnnotation(kafkaRebalance)) {
            // we need to do this check again because it was triggered by a watcher
            KafkaRebalanceStatus status = new KafkaRebalanceStatus();

            Set<Condition> unknownAndDeprecatedConditions = StatusUtils.validate(reconciliation, kafkaRebalance);
            unknownAndDeprecatedConditions.add(StatusUtils.getPausedCondition());
            status.setConditions(new ArrayList<>(unknownAndDeprecatedConditions));

            return Future.succeededFuture(updateStatus(kafkaRebalance, status, null));
        }

        Promise<KafkaRebalanceStatus> reconcilePromise = Promise.promise();

        try {
            AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder = convertRebalanceSpecToRebalanceOptions(kafkaRebalance.getSpec());

            computeNextStatus(reconciliation, host, apiClient, kafkaRebalance, currentState, rebalanceOptionsBuilder)
                    .compose(desiredStatusAndMap -> {
                        KafkaRebalanceAnnotation rebalanceAnnotation = rebalanceAnnotation(kafkaRebalance);
                        return configMapOperator.reconcile(reconciliation, kafkaRebalance.getMetadata().getNamespace(),
                                        kafkaRebalance.getMetadata().getName(), desiredStatusAndMap.getLoadMap())
                                .onComplete(ignoredConfigMapResult -> {
                                    KafkaRebalanceStatus kafkaRebalanceStatus = updateStatus(kafkaRebalance, desiredStatusAndMap.getStatus(), null);
                                    if (kafkaRebalance.getStatus() != null
                                            && kafkaRebalanceStatus != null
                                            && !rebalanceStateConditionType(kafkaRebalance.getStatus()).equals(rebalanceStateConditionType(kafkaRebalanceStatus))) {
                                        String message = "KafkaRebalance state is now updated to [{}]";

                                        if (rawRebalanceAnnotation(kafkaRebalance) != null) {
                                            message = message + " with annotation {}={} applied on the KafkaRebalance resource";
                                        }
                                        LOGGER.infoCr(reconciliation, message, rebalanceStateConditionType(kafkaRebalanceStatus),
                                                ANNO_STRIMZI_IO_REBALANCE,
                                                rawRebalanceAnnotation(kafkaRebalance));
                                    }
                                    if (hasRebalanceAnnotation(kafkaRebalance)) {
                                        if (currentState != KafkaRebalanceState.ReconciliationPaused && rebalanceAnnotation != KafkaRebalanceAnnotation.none && !currentState.isValidateAnnotation(rebalanceAnnotation)) {
                                            reconcilePromise.complete(kafkaRebalanceStatus);
                                        } else {
                                            LOGGER.infoCr(reconciliation, "Removing annotation {}={}",
                                                    ANNO_STRIMZI_IO_REBALANCE,
                                                    rawRebalanceAnnotation(kafkaRebalance));
                                            // Updated KafkaRebalance has rebalance annotation removed as
                                            // action specified by user has been completed.
                                            KafkaRebalance patchedKafkaRebalance = new KafkaRebalanceBuilder(kafkaRebalance)
                                                    .editMetadata()
                                                        .removeFromAnnotations(ANNO_STRIMZI_IO_REBALANCE)
                                                    .endMetadata()
                                                    .build();
                                            kafkaRebalanceOperator.patchAsync(reconciliation, patchedKafkaRebalance)
                                                    .onComplete(ignoredKafkaRebalanceResult -> reconcilePromise.complete(kafkaRebalanceStatus));
                                        }
                                    } else {
                                        LOGGER.debugCr(reconciliation, "No annotation {}", ANNO_STRIMZI_IO_REBALANCE);
                                        reconcilePromise.complete(kafkaRebalanceStatus);
                                    }
                                });
                    }).onFailure(exception -> {
                        LOGGER.errorCr(reconciliation, "Status updated to [NotReady] due to error: {}", exception.getMessage());
                        reconcilePromise.complete(updateStatus(kafkaRebalance, new KafkaRebalanceStatus(), exception));
                    });
        } catch (IllegalArgumentException e) {
            LOGGER.errorCr(reconciliation, "Status updated to [NotReady] due to error: {}", e.getMessage());
            return Future.succeededFuture(updateStatus(kafkaRebalance, new KafkaRebalanceStatus(), e));
        }

        return reconcilePromise.future();
    }

    /**
     * computeNextStatus returns a future to a wrapper class containing ConfigMap and KafkaRebalanceStatus that will be computed depending on the given
     * KafkaRebalance state, status and annotations.
     */
    /* test */ protected Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> computeNextStatus(Reconciliation reconciliation,
                                                                         String host, CruiseControlApi apiClient,
                                                                         KafkaRebalance kafkaRebalance, KafkaRebalanceState currentState,
                                                                         AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {
        switch (currentState) {
            case New:
                return onNew(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
            case PendingProposal:
                return onPendingProposal(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
            case ProposalReady:
                return onProposalReady(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
            case Rebalancing:
                return onRebalancing(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
            case Stopped:
                return onStop(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
            case Ready:
                // Rebalance Complete
                return onReady(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
            case ReconciliationPaused:
                return onReconciliationPaused(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
            case NotReady:
                // Error case
                return onNotReady(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
            default:
                return Future.failedFuture(new RuntimeException("Unexpected state " + currentState));
        }
    }

    private KafkaRebalanceStatus buildRebalanceStatusFromPreviousStatus(KafkaRebalanceStatus currentStatus, Set<Condition> validation) {
        List<Condition> conditions = new ArrayList<>();
        conditions.addAll(validation);
        Condition currentState = rebalanceStateCondition(currentStatus);
        conditions.add(currentState);
        return new KafkaRebalanceStatusBuilder()
                .withSessionId(currentStatus.getSessionId())
                .withOptimizationResult(currentStatus.getOptimizationResult())
                .withConditions(conditions)
                .build();
    }

    private MapAndStatus<ConfigMap, KafkaRebalanceStatus> buildRebalanceStatus(String sessionID, KafkaRebalanceState cruiseControlState, Set<Condition> validation) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(StatusUtils.buildRebalanceCondition(cruiseControlState.toString()));
        conditions.addAll(validation);
        return new MapAndStatus<>(null, new KafkaRebalanceStatusBuilder()
                .withSessionId(sessionID)
                .withConditions(conditions)
                .build());
    }

    /**
     * Converts the supplied JSONArray containing the load information JSONObject for each broker, into a map linking from
     * broker ID to a map linking a more readable version of the load parameters key to their values. The load parameters
     * that are extracted and the readable versions of the keys are dictated by the values defined in the
     * {@link CruiseControlLoadParameters} enum.
     *
     * @param brokerLoadArray The JSONArray of broker load JSONObjects returned by the Cruise Control rebalance endpoint.
     * @return A map linking from broker ID integer to a map of load parameter to value.
     */
    protected static Map<Integer, Map<String, Object>> extractLoadParameters(JsonArray brokerLoadArray) {

        Map<Integer, Map<String, Object>> loadMap = new HashMap<>();

        for (Object rawBrokerLoad : brokerLoadArray) {
            JsonObject brokerLoad = (JsonObject) rawBrokerLoad;

            Map<String, Object> brokerLoadMap = new HashMap<>();

            for (CruiseControlLoadParameters intParam : CruiseControlLoadParameters.getIntegerParameters()) {
                if (brokerLoad.containsKey(intParam.getCruiseControlKey())) {
                    brokerLoadMap.put(intParam.getKafkaRebalanceStatusKey(), brokerLoad.getInteger(intParam.getCruiseControlKey()));
                }
            }

            for (CruiseControlLoadParameters doubleParam : CruiseControlLoadParameters.getDoubleParameters()) {
                if (brokerLoad.containsKey(doubleParam.getCruiseControlKey())) {
                    brokerLoadMap.put(doubleParam.getKafkaRebalanceStatusKey(), brokerLoad.getDouble(doubleParam.getCruiseControlKey()));
                }
            }

            int brokerID = brokerLoad.getInteger(CruiseControlRebalanceKeys.BROKER_ID.getKey());
            loadMap.put(brokerID, brokerLoadMap);

        }

        return loadMap;

    }

    /**
     * Converts the supplied before and after broker load arrays into a map linking from broker ID integer to a map linking
     * from load parameter to an array of [before, after, difference]. The load parameters included in the map are dictated
     * by the values in he {@link CruiseControlLoadParameters} enum.
     *
     * @param brokerLoadBeforeArray The JSONArray of broker load JSONObjects, for before the optimization proposal is applied,
     *                              returned by the Cruise Control rebalance endpoint.
     * @param brokerLoadAfterArray The JSONArray of broker load JSONObjects, for after the optimization proposal is applied,
     *                             returned by the Cruise Control rebalance endpoint.
     * @return A JsonObject linking from broker ID integer to a map of load parameter to [before, after, difference] arrays.
     */
    protected static JsonObject parseLoadStats(JsonArray brokerLoadBeforeArray, JsonArray brokerLoadAfterArray) {

        // There is no guarantee that the brokers are in the same order in both the before and after arrays.
        // Therefore we need to convert them into maps indexed by broker ID so we can align them later for the comparison.
        Map<Integer, Map<String, Object>> loadBeforeMap = extractLoadParameters(brokerLoadBeforeArray);
        Map<Integer, Map<String, Object>> loadAfterMap = extractLoadParameters(brokerLoadAfterArray);

        if (loadBeforeMap.size() != loadAfterMap.size()) {
            throw new IllegalArgumentException("Broker data was missing from the load before/after information");
        }

        JsonObject brokersStats = new JsonObject();

        for (Map.Entry<Integer, Map<String, Object>> loadBeforeEntry : loadBeforeMap.entrySet()) {
            Map<String, Object> brokerBefore = loadBeforeEntry.getValue();
            Map<String, Object> brokerAfter = loadAfterMap.get(loadBeforeEntry.getKey());

            JsonObject brokerStats = new JsonObject();

            for (CruiseControlLoadParameters intLoadParameter : CruiseControlLoadParameters.getIntegerParameters()) {

                if (brokerBefore.containsKey(intLoadParameter.getKafkaRebalanceStatusKey()) &&
                        brokerAfter.containsKey(intLoadParameter.getKafkaRebalanceStatusKey())) {

                    int intBeforeStat = (int) brokerBefore.get(intLoadParameter.getKafkaRebalanceStatusKey());
                    int intAfterStat = (int) brokerAfter.get(intLoadParameter.getKafkaRebalanceStatusKey());
                    int intDiff = intAfterStat - intBeforeStat;


                    JsonObject intStats = new JsonObject();
                    intStats.put("before", intBeforeStat);
                    intStats.put("after", intAfterStat);
                    intStats.put("diff", intDiff);

                    brokerStats.put(intLoadParameter.getKafkaRebalanceStatusKey(), intStats);
                } else {
                    LOGGER.warnOp("{} information was missing from the broker before/after load information",
                            intLoadParameter.getKafkaRebalanceStatusKey());
                }

            }

            for (CruiseControlLoadParameters doubleLoadParameter : CruiseControlLoadParameters.getDoubleParameters()) {

                if (brokerBefore.containsKey(doubleLoadParameter.getKafkaRebalanceStatusKey()) &&
                        brokerAfter.containsKey(doubleLoadParameter.getKafkaRebalanceStatusKey())) {

                    double doubleBeforeStat = (double) brokerBefore.get(doubleLoadParameter.getKafkaRebalanceStatusKey());
                    double doubleAfterStat = (double) brokerAfter.get(doubleLoadParameter.getKafkaRebalanceStatusKey());
                    double doubleDiff = doubleAfterStat - doubleBeforeStat;

                    JsonObject doubleStats = new JsonObject();
                    doubleStats.put("before", doubleBeforeStat);
                    doubleStats.put("after", doubleAfterStat);
                    doubleStats.put("diff", doubleDiff);

                    brokerStats.put(doubleLoadParameter.getKafkaRebalanceStatusKey(), doubleStats);
                } else {
                    LOGGER.warnOp("{} information was missing from the broker before/after load information",
                            doubleLoadParameter.getKafkaRebalanceStatusKey());
                }

            }

            brokersStats.put(String.valueOf(loadBeforeEntry.getKey()), brokerStats);
        }

        return brokersStats;
    }

    /**
     * A wrapper class containing used to bind the ConfigMap and the status together.
     */
    static class MapAndStatus<T, K> {

        T loadMap;
        K status;

        public T getLoadMap() {
            return loadMap;
        }

        public K getStatus() {
            return status;
        }

        public void setStatus(K status) {
            this.status = status;
        }

        public MapAndStatus(T loadMap, K status) {
            this.loadMap = loadMap;
            this.status = status;
        }
    }

    /**
     * Converts the supplied JSONObject containing the response from the {@link CruiseControlApi#rebalance} or
     * {@link CruiseControlApi#getUserTaskStatus} methods, into a map linking to a proposal summary map and a broker
     * load map.
     *
     * @param  proposalJson The JSONObject representing the response from the Cruise Control rebalance endpoint.
     * @return A wrapper class containing the proposal summary map and a config map containing broker load.
     */
    protected static MapAndStatus<ConfigMap, Map<String, Object>> processOptimizationProposal(KafkaRebalance kafkaRebalance, JsonObject proposalJson) {

        JsonArray brokerLoadBeforeOptimization;
        JsonArray brokerLoadAfterOptimization;
        if (proposalJson.containsKey(CruiseControlRebalanceKeys.LOAD_BEFORE_OPTIMIZATION.getKey()) &&
                proposalJson.containsKey(CruiseControlRebalanceKeys.LOAD_AFTER_OPTIMIZATION.getKey())) {
            brokerLoadBeforeOptimization = proposalJson
                    .getJsonObject(CruiseControlRebalanceKeys.LOAD_BEFORE_OPTIMIZATION.getKey())
                    .getJsonArray(CruiseControlRebalanceKeys.BROKERS.getKey());
            brokerLoadAfterOptimization = proposalJson
                    .getJsonObject(CruiseControlRebalanceKeys.LOAD_AFTER_OPTIMIZATION.getKey())
                    .getJsonArray(CruiseControlRebalanceKeys.BROKERS.getKey());
        } else {
            throw new IllegalArgumentException("The rebalance optimization proposal returned by Cruise Control did not contain broker load information");
        }

        JsonObject beforeAndAfterBrokerLoad = parseLoadStats(
                brokerLoadBeforeOptimization, brokerLoadAfterOptimization);

        ConfigMap rebalanceMap = new ConfigMapBuilder()
                .withNewMetadata()
                    .withNamespace(kafkaRebalance.getMetadata().getNamespace())
                    .withName(kafkaRebalance.getMetadata().getName())
                    .withLabels(Collections.singletonMap("app", "strimzi"))
                    .withOwnerReferences(ModelUtils.createOwnerReference(kafkaRebalance, false))
                .endMetadata()
                .withData(Collections.singletonMap(BROKER_LOAD_KEY, beforeAndAfterBrokerLoad.encode()))
                .build();

        proposalJson.getJsonObject(CruiseControlRebalanceKeys.SUMMARY.getKey()).getMap().put("afterBeforeLoadConfigMap", rebalanceMap.getMetadata().getName());
        return new MapAndStatus<>(rebalanceMap, proposalJson.getJsonObject(CruiseControlRebalanceKeys.SUMMARY.getKey()).getMap());
    }

    private MapAndStatus<ConfigMap, KafkaRebalanceStatus> buildRebalanceStatus(KafkaRebalance kafkaRebalance, String sessionID, KafkaRebalanceState cruiseControlState, JsonObject proposalJson, Set<Condition> validation) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(StatusUtils.buildRebalanceCondition(cruiseControlState.toString()));
        conditions.addAll(validation);
        MapAndStatus<ConfigMap, Map<String, Object>> optimizationProposalMapAndStatus = processOptimizationProposal(kafkaRebalance, proposalJson);
        return new MapAndStatus<>(optimizationProposalMapAndStatus.getLoadMap(), new KafkaRebalanceStatusBuilder()
                .withSessionId(sessionID)
                .withConditions(conditions)
                .withOptimizationResult(optimizationProposalMapAndStatus.getStatus())
                .build());

    }

    /**
     * This method handles the transition from {@code New} state.
     * When a new {@link KafkaRebalance} is created, it calls the Cruise Control API for requesting a rebalance proposal.
     * If the proposal is immediately ready, the next state is {@code ProposalReady}.
     * If the proposal is not ready yet and Cruise Control is still processing it, the next state is {@code PendingProposal}.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code MapAndStatus<ConfigMap, KafkaRebalanceStatus>} including the ConfigMap and state
     */

    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> onNew(Reconciliation reconciliation,
                                                                        String host, CruiseControlApi apiClient, KafkaRebalance kafkaRebalance,
                                                                        AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {
        return requestRebalance(reconciliation, host, apiClient, kafkaRebalance, true, rebalanceOptionsBuilder);
    }

    /**
     * This method handles the transition from the {@code NotReady} state.
     * This state indicates that the rebalance has suffered some kind of error.
     * This could be due to misconfiguration or the result of an error during a reconcile.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param kafkaRebalance Current {@code KafkaRebalance} resource
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code MapAndStatus<ConfigMap, KafkaRebalanceStatus> } including the ConfigMap and state
     */
    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> onNotReady(Reconciliation reconciliation,
                                                                             String host, CruiseControlApi apiClient,
                                                                             KafkaRebalance kafkaRebalance,
                                                                             AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {
        KafkaRebalanceAnnotation rebalanceAnnotation = rebalanceAnnotation(kafkaRebalance);
        if (shouldRenewRebalance(kafkaRebalance, rebalanceAnnotation)) {
            // CC is activated from the disabled state or the user has fixed the error on the resource and want to 'refresh'
            return onNew(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
        } else {
            // Stay in the current NotReady state
            Set<Condition> conditions = StatusUtils.validate(reconciliation, kafkaRebalance);
            validateAnnotation(reconciliation, conditions, KafkaRebalanceState.NotReady, rebalanceAnnotation, kafkaRebalance);
            return Future.succeededFuture(new MapAndStatus<>(null, buildRebalanceStatusFromPreviousStatus(kafkaRebalance.getStatus(), conditions)));
        }
    }

    /**
     * This method handles the transition from {@code ReconciliationPaused} state.
     * When the reconciliation is unpaused {@link KafkaRebalance} , it calls the Cruise Control API for requesting a rebalance proposal.
     * If the proposal is immediately ready, the next state is {@code ProposalReady}.
     * If the proposal is not ready yet and Cruise Control is still processing it, the next state is {@code PendingProposal}.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code MapAndStatus<ConfigMap, KafkaRebalanceStatus>} including the ConfigMap and state
     */

    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> onReconciliationPaused(Reconciliation reconciliation,
                                                                             String host, CruiseControlApi apiClient,
                                                                             KafkaRebalance kafkaRebalance,
                                                                             AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {

        return requestRebalance(reconciliation, host, apiClient, kafkaRebalance, true, rebalanceOptionsBuilder);
    }

    /**
     * This method handles the transition from {@code PendingProposal} state.
     * It starts a periodic timer in order to check the status of the ongoing rebalance proposal processing on Cruise Control side.
     * In order to do that, it calls the Cruise Control API for requesting the rebalance proposal.
     * When the proposal is ready, the next state is {@code ProposalReady}.
     * If the user sets the strimzi.io/rebalance=stop annotation, it stops polling the Cruise Control API for requesting the rebalance proposal.
     * If the user sets any other values for the strimzi.io/rebalance annotation, it is ignored and the rebalance proposal request continues.
     *
     * This method holds the lock until the rebalance proposal is ready or any exception is raised.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the REST API requests
     * @param apiClient Cruise Control REST API client instance
     * @param kafkaRebalance Current {@code KafkaRebalance} resource
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code MapAndStatus<ConfigMap, KafkaRebalanceStatus>} including the ConfigMap and state.
     */
    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> onPendingProposal(Reconciliation reconciliation,
                                                                    String host, CruiseControlApi apiClient,
                                                                    KafkaRebalance kafkaRebalance,
                                                                    AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {
        Promise<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> p = Promise.promise();

        if (rebalanceAnnotation(kafkaRebalance) == KafkaRebalanceAnnotation.refresh) {
            LOGGER.infoCr(reconciliation, "Requesting a new proposal since refresh annotation is applied on the KafkaRebalance resource");
            requestRebalance(reconciliation, host, apiClient, kafkaRebalance, true, rebalanceOptionsBuilder).onSuccess(p::complete);
        } else if (rebalanceAnnotation(kafkaRebalance) == KafkaRebalanceAnnotation.stop) {
            LOGGER.infoCr(reconciliation, "Stopping to request proposal or checking the status");
            p.complete(buildRebalanceStatus(null, KafkaRebalanceState.Stopped, StatusUtils.validate(reconciliation, kafkaRebalance)));
        } else {
            LOGGER.infoCr(reconciliation, "Requesting a new proposal or checking the status for an already issued one");
            requestRebalance(reconciliation, host, apiClient, kafkaRebalance, true, rebalanceOptionsBuilder,
                    kafkaRebalance.getStatus().getSessionId())
                .onSuccess(rebalanceMapAndStatus -> {
                    // If the returned status has an optimization result then the rebalance proposal is ready
                    KafkaRebalanceStatus status = rebalanceMapAndStatus.getStatus();
                    Set<Condition> conditions = new HashSet<>();
                    validateAnnotation(reconciliation, conditions, KafkaRebalanceState.PendingProposal, rebalanceAnnotation(kafkaRebalance), kafkaRebalance);
                    status.addConditions(conditions);
                    rebalanceMapAndStatus.setStatus(status);
                    if (rebalanceMapAndStatus.getStatus().getOptimizationResult() != null &&
                            !rebalanceMapAndStatus.getStatus().getOptimizationResult().isEmpty()) {
                        LOGGER.infoCr(reconciliation, "Optimization proposal ready");
                    } else {
                        LOGGER.infoCr(reconciliation, "Waiting for optimization proposal to be ready");
                    }
                    p.complete(rebalanceMapAndStatus);
                })
                .onFailure(e -> {
                    LOGGER.errorCr(reconciliation, "Cruise Control getting rebalance proposal failed");
                    p.fail(e);
                });
        }
        return p.future();
    }

    /**
     * This method handles the transition from {@code ProposalReady} state.
     * It is related to the value that the user apply to the strimzi.io/rebalance annotation.
     * If the strimzi.io/rebalance annotation is set to 'approve', it calls the Cruise Control API for executing the proposed rebalance.
     * If the strimzi.io/rebalance annotation is set to 'refresh', it calls the Cruise Control API for requesting/refreshing the ready rebalance proposal.
     * If the rebalance is immediately complete, the next state is {@code Ready}.
     * If the rebalance is not finished yet as Cruise Control is still processing it (the usual case), the next state is {@code Rebalancing}.
     * If the user sets any other value for the strimzi.io/rebalance annotation, it is ignored.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance request
     * @param apiClient Cruise Control REST API client instance
     * @param kafkaRebalance Current {@code KafkaRebalance} resource
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code MapAndStatus<ConfigMap, KafkaRebalanceStatus>} including the ConfigMap and state
     */
    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> onProposalReady(Reconciliation reconciliation,
                                                                      String host, CruiseControlApi apiClient,
                                                                      KafkaRebalance kafkaRebalance,
                                                                      AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {
        if (Annotations.booleanAnnotation(kafkaRebalance, ANNO_STRIMZI_IO_REBALANCE_AUTOAPPROVAL, false)) {
            LOGGER.infoCr(reconciliation, "Auto-approval set on the KafkaRebalance resource");
            return requestRebalance(reconciliation, host, apiClient, kafkaRebalance, false, rebalanceOptionsBuilder);
        } else {
            KafkaRebalanceAnnotation rebalanceAnnotation = rebalanceAnnotation(kafkaRebalance);
            switch (rebalanceAnnotation) {
                case none:
                    LOGGER.debugCr(reconciliation, "No {} annotation set", ANNO_STRIMZI_IO_REBALANCE);
                    return configMapOperator.getAsync(kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName()).compose(loadmap -> Future.succeededFuture(new MapAndStatus<>(loadmap, buildRebalanceStatusFromPreviousStatus(kafkaRebalance.getStatus(), StatusUtils.validate(reconciliation, kafkaRebalance)))));
                case approve:
                    LOGGER.debugCr(reconciliation, "Annotation {}={}", ANNO_STRIMZI_IO_REBALANCE, KafkaRebalanceAnnotation.approve);
                    return requestRebalance(reconciliation, host, apiClient, kafkaRebalance, false, rebalanceOptionsBuilder);
                case refresh:
                    LOGGER.debugCr(reconciliation, "Annotation {}={}", ANNO_STRIMZI_IO_REBALANCE, KafkaRebalanceAnnotation.refresh);
                    return requestRebalance(reconciliation, host, apiClient, kafkaRebalance, true, rebalanceOptionsBuilder);
                default:
                    LOGGER.warnCr(reconciliation, "Ignore annotation {}={}", ANNO_STRIMZI_IO_REBALANCE, kafkaRebalance.getMetadata().getAnnotations().get(ANNO_STRIMZI_IO_REBALANCE));
                    Set<Condition> conditions = StatusUtils.validate(reconciliation, kafkaRebalance);
                    validateAnnotation(reconciliation, conditions, KafkaRebalanceState.ProposalReady, rebalanceAnnotation, kafkaRebalance);
                    return configMapOperator.getAsync(kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName())
                            .compose(loadmap -> Future.succeededFuture(new MapAndStatus<>(loadmap, buildRebalanceStatusFromPreviousStatus(kafkaRebalance.getStatus(), conditions))));
            }
        }
    }

    /**
     * This method handles the transition from {@code Rebalancing} state.
     * It starts a periodic timer in order to check the status of the ongoing rebalance processing on Cruise Control side.
     * In order to do that, it calls the related Cruise Control REST API about asking the user task status.
     * When the rebalance is finished, the next state is {@code Ready}.
     * If the user sets the strimzi.io/rebalance annotation to 'stop', it calls the Cruise Control REST API for stopping the ongoing task
     * and then transitions to the {@code Stopped} state.
     * If the user sets any other values for the strimzi.io/rebalance annotation, it is just ignored and the user task checks continue.
     * This method holds the lock until the rebalance is finished, the ongoing task is stopped or any exception is raised.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the REST API requests
     * @param apiClient Cruise Control REST API client instance
     * @param kafkaRebalance Current {@code KafkaRebalance} resource
     * @return a Future with the next {@code MapAndStatus<ConfigMap, KafkaRebalanceStatus>} including the state
     */
    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> onRebalancing(Reconciliation reconciliation,
                                                                                String host, CruiseControlApi apiClient,
                                                                                KafkaRebalance kafkaRebalance,
                                                                                AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {
        Promise<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> p = Promise.promise();

        String sessionId = kafkaRebalance.getStatus().getSessionId();
        if (rebalanceAnnotation(kafkaRebalance) == KafkaRebalanceAnnotation.stop) {
            LOGGER.infoCr(reconciliation, "Stopping current Cruise Control rebalance user task");
            apiClient.stopExecution(reconciliation, host, cruiseControlPort)
                .onSuccess(r -> p.complete(buildRebalanceStatus(null, KafkaRebalanceState.Stopped, StatusUtils.validate(reconciliation, kafkaRebalance))))
                .onFailure(e -> {
                    LOGGER.errorCr(reconciliation, "Cruise Control stopping execution failed", e.getCause());
                    p.fail(e.getCause());
                });
        } else if (rebalanceAnnotation(kafkaRebalance) == KafkaRebalanceAnnotation.refresh) {
            LOGGER.infoCr(reconciliation, "Stopping current Cruise Control rebalance user task since refresh annotation is applied on the KafkaRebalance resource and requesting a new proposal");
            apiClient.stopExecution(reconciliation, host, cruiseControlPort)
                    .onSuccess(r -> {
                        requestRebalance(reconciliation, host, apiClient, kafkaRebalance, true, rebalanceOptionsBuilder).onSuccess(p::complete);
                    })
                    .onFailure(e -> {
                        LOGGER.errorCr(reconciliation, "Cruise Control stopping execution failed", e.getCause());
                        p.fail(e.getCause());
                    });
        } else {
            LOGGER.infoCr(reconciliation, "Getting Cruise Control rebalance user task status");
            Set<Condition> conditions = StatusUtils.validate(reconciliation, kafkaRebalance);
            validateAnnotation(reconciliation, conditions, KafkaRebalanceState.Rebalancing, rebalanceAnnotation(kafkaRebalance), kafkaRebalance);
            apiClient.getUserTaskStatus(reconciliation, host, cruiseControlPort, sessionId)
                .onSuccess(cruiseControlResponse -> {
                    if (cruiseControlResponse.getJson().isEmpty()) {
                        // This may happen if:
                        // 1. Cruise Control restarted so resetting the state because the tasks queue is not persisted
                        // 2. Task's retention time expired, or the cache has become full
                        LOGGER.warnCr(reconciliation, "User task {} not found, going to generate a new proposal", sessionId);
                        requestRebalance(reconciliation, host, apiClient, kafkaRebalance, true, rebalanceOptionsBuilder).onSuccess(p::complete);
                    } else {
                        JsonObject taskStatusJson = cruiseControlResponse.getJson();
                        CruiseControlUserTaskStatus taskStatus = CruiseControlUserTaskStatus.lookup(taskStatusJson.getString("Status"));
                        switch (taskStatus) {
                            case COMPLETED:
                                LOGGER.infoCr(reconciliation, "Rebalance ({}) is now complete", sessionId);
                                p.complete(buildRebalanceStatus(kafkaRebalance, null, KafkaRebalanceState.Ready, taskStatusJson, conditions));
                                break;
                            case COMPLETED_WITH_ERROR:
                                // TODO: There doesn't seem to be a way to retrieve the actual error message from the user tasks endpoint?
                                //       We may need to propose an upstream PR for this.
                                // TODO: Once we can get the error details we need to add an error field to the Rebalance Status to hold
                                //       details of any issues while rebalancing.
                                LOGGER.errorCr(reconciliation, "Rebalance ({}) optimization proposal has failed to complete", sessionId);
                                p.complete(buildRebalanceStatus(sessionId, KafkaRebalanceState.NotReady, conditions));
                                break;
                            case IN_EXECUTION: // Rebalance is still in progress
                                LOGGER.infoCr(reconciliation, "Rebalance ({}) optimization proposal in execution", sessionId);
                                // We need to check that the status has been updated with the ongoing optimisation proposal
                                // The proposal field can be empty if a rebalance(dryrun=false) was called and the optimisation
                                // proposal was still being prepared (in progress). In that case the rebalance will start when
                                // the proposal is complete but the optimisation proposal summary will be missing.
                                if (kafkaRebalance.getStatus().getOptimizationResult() == null ||
                                        kafkaRebalance.getStatus().getOptimizationResult().isEmpty()) {
                                    LOGGER.infoCr(reconciliation, "Rebalance ({}) optimization proposal is now ready and has been added to the status", sessionId);
                                    p.complete(buildRebalanceStatus(
                                            kafkaRebalance, sessionId, KafkaRebalanceState.Rebalancing, taskStatusJson, conditions));
                                } else {
                                    configMapOperator.getAsync(kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName())
                                            .onSuccess(loadmap -> p.complete(new MapAndStatus<>(loadmap, buildRebalanceStatusFromPreviousStatus(kafkaRebalance.getStatus(), conditions))));
                                }
                                // TODO: Find out if there is any way to check the progress of a rebalance.
                                //       We could parse the verbose proposal for total number of reassignments and compare to number completed (if available)?
                                //       We can then update the status at this point.
                                break;
                            case ACTIVE: // Rebalance proposal is still being calculated
                                // If a rebalance(dryrun=false) was called and the proposal is still being prepared then the task
                                // will be in an ACTIVE state. When the proposal is ready it will shift to IN_EXECUTION and we will
                                // check that the optimisation proposal is added to the status on the next reconcile.
                                LOGGER.infoCr(reconciliation, "Rebalance ({}) optimization proposal is still being prepared", sessionId);
                                configMapOperator.getAsync(kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName())
                                        .onSuccess(loadmap -> p.complete(new MapAndStatus<>(loadmap, buildRebalanceStatusFromPreviousStatus(kafkaRebalance.getStatus(), conditions))));
                                break;
                            default:
                                LOGGER.errorCr(reconciliation, "Unexpected state {}", taskStatus);
                                p.fail("Unexpected state " + taskStatus);
                                break;
                        }
                    }
                })
                .onFailure(e -> {
                    LOGGER.errorCr(reconciliation, "Cruise Control getting rebalance task status failed", e.getCause());
                    p.fail(new CruiseControlRestException("Cruise Control getting rebalance task status failed"));
                });
        }
        return p.future();
    }

    /**
     * This method handles the transition from {@code Stopped} state.
     * If the user set strimzi.io/rebalance=refresh annotation, it calls the Cruise Control API for requesting a new rebalance proposal.
     * If the proposal is immediately ready, the next state is {@code ProposalReady}.
     * If the proposal is not ready yet and Cruise Control is still taking care of processing it, the next state is {@code PendingProposal}.
     * If the user sets any other values for the strimzi.io/rebalance, it is just ignored.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code MapAndStatus<ConfigMap, KafkaRebalanceStatus>} bringing the ConfigMap and state.
     */

    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> onStop(Reconciliation reconciliation,
                                                                 String host, CruiseControlApi apiClient,
                                                                 KafkaRebalance kafkaRebalance,
                                                                 AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {
        KafkaRebalanceAnnotation rebalanceAnnotation = rebalanceAnnotation(kafkaRebalance);
        if (rebalanceAnnotation == KafkaRebalanceAnnotation.refresh) {
            return requestRebalance(reconciliation, host, apiClient, kafkaRebalance, true, rebalanceOptionsBuilder);
        } else {
            Set<Condition> conditions = StatusUtils.validate(reconciliation, kafkaRebalance);
            validateAnnotation(reconciliation, conditions, KafkaRebalanceState.Stopped, rebalanceAnnotation, kafkaRebalance);
            return Future.succeededFuture(buildRebalanceStatus(null, KafkaRebalanceState.Stopped, conditions));
        }
    }

    /**
     * This method handles the transition from {@code Ready} state.
     * If the user set strimzi.io/rebalance=refresh annotation, it calls the Cruise Control API for requesting a new rebalance proposal.
     * If the proposal is immediately ready, the next state is {@code ProposalReady}.
     * If the proposal is not ready yet and Cruise Control is still taking care of processing it, the next state is {@code PendingProposal}.
     * If the user sets any other values for the strimzi.io/rebalance, it is just ignored.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code MapAndStatus<ConfigMap, KafkaRebalanceStatus>} bringing the ConfigMap and state
     */
    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> onReady(Reconciliation reconciliation,
                                                                    String host, CruiseControlApi apiClient,
                                                                    KafkaRebalance kafkaRebalance,
                                                                    AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {
        KafkaRebalanceAnnotation rebalanceAnnotation = rebalanceAnnotation(kafkaRebalance);
        if (rebalanceAnnotation == KafkaRebalanceAnnotation.refresh) {
            return requestRebalance(reconciliation, host, apiClient, kafkaRebalance, true, rebalanceOptionsBuilder);
        } else {
            Set<Condition> conditions = StatusUtils.validate(reconciliation, kafkaRebalance);
            validateAnnotation(reconciliation, conditions, KafkaRebalanceState.Ready, rebalanceAnnotation, kafkaRebalance);
            return configMapOperator.getAsync(kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName())
                    .compose(loadmap -> Future.succeededFuture(new MapAndStatus<>(loadmap, buildRebalanceStatusFromPreviousStatus(kafkaRebalance.getStatus(), conditions))));
        }
    }

    /**
     * Reconcile loop for the KafkaRebalance
     */
    @SuppressWarnings({"checkstyle:NPathComplexity"})
    /* test */ Future<KafkaRebalanceStatus> reconcileRebalance(Reconciliation reconciliation, KafkaRebalance kafkaRebalance) {
        if (kafkaRebalance == null) {
            LOGGER.infoCr(reconciliation, "KafkaRebalance resource deleted");
            return Future.succeededFuture();
        }

        LOGGER.debugCr(reconciliation, "KafkaRebalance {} with status [{}] and {}={}",
                kafkaRebalance.getMetadata().getName(),
                kafkaRebalance.getStatus() != null ? rebalanceStateConditionType(kafkaRebalance.getStatus()) : null,
                ANNO_STRIMZI_IO_REBALANCE, rawRebalanceAnnotation(kafkaRebalance));

        if (kafkaRebalance.getStatus() != null
                && kafkaRebalance.getStatus().getObservedGeneration() != kafkaRebalance.getMetadata().getGeneration()) {

            KafkaRebalanceBuilder patchedKafkaRebalance = new KafkaRebalanceBuilder(kafkaRebalance);

            patchedKafkaRebalance
                    .editMetadata()
                         .addToAnnotations(Map.of(ANNO_STRIMZI_IO_REBALANCE, KafkaRebalanceAnnotation.refresh.toString()))
                    .endMetadata()
                    .editStatus()
                         .withObservedGeneration(kafkaRebalance.getMetadata().getGeneration())
                    .endStatus();

            kafkaRebalanceOperator.patchAsync(reconciliation, patchedKafkaRebalance.build()).onComplete(
                    r -> LOGGER.debugCr(reconciliation, "The KafkaRebalance resource is updated with refresh annotation"));
        }

        String clusterName = kafkaRebalance.getMetadata().getLabels() == null ? null : kafkaRebalance.getMetadata().getLabels().get(Labels.STRIMZI_CLUSTER_LABEL);
        String clusterNamespace = kafkaRebalance.getMetadata().getNamespace();
        if (clusterName == null) {
            String errorString = "Resource lacks label '" + Labels.STRIMZI_CLUSTER_LABEL + "': No cluster related to a possible rebalance.";
            LOGGER.warnCr(reconciliation, errorString);
            KafkaRebalanceStatus status = new KafkaRebalanceStatus();
            return Future.succeededFuture(updateStatus(kafkaRebalance, status,
                    new InvalidResourceException(CruiseControlIssues.clusterLabelMissing.getMessage())));
        }

        // Get associated Kafka cluster state
        return kafkaOperator.getAsync(clusterNamespace, clusterName)
                .compose(kafka -> {
                    if (kafka == null) {
                        LOGGER.warnCr(reconciliation, "Kafka resource '{}' identified by label '{}' does not exist in namespace {}.",
                                clusterName, Labels.STRIMZI_CLUSTER_LABEL, clusterNamespace);
                        return Future.succeededFuture(updateStatus(kafkaRebalance, new KafkaRebalanceStatus(),
                                new NoSuchResourceException("Kafka resource '" + clusterName
                                        + "' identified by label '" + Labels.STRIMZI_CLUSTER_LABEL
                                        + "' does not exist in namespace " + clusterNamespace + ".")));
                    } else if (!isKafkaClusterReady(kafka) && state(kafkaRebalance) != (KafkaRebalanceState.Ready)) {
                        LOGGER.warnCr(reconciliation, "Kafka cluster is not Ready");
                        KafkaRebalanceStatus status = new KafkaRebalanceStatus();
                        return Future.succeededFuture(updateStatus(kafkaRebalance, status,
                                new RuntimeException(CruiseControlIssues.kafkaClusterNotReady.getMessage())));
                    } else if (!Util.matchesSelector(kafkaSelector, kafka)) {
                        LOGGER.debugCr(reconciliation, "{} {} in namespace {} belongs to a Kafka cluster {} which does not match label selector {} and will be ignored", kind(), kafkaRebalance.getMetadata().getName(), clusterNamespace, clusterName, kafkaSelector.getMatchLabels());
                        return Future.succeededFuture(kafkaRebalance.getStatus());
                    } else if (kafka.getSpec().getCruiseControl() == null) {
                        LOGGER.warnCr(reconciliation, "Kafka resource lacks 'cruiseControl' declaration" + ": No deployed Cruise Control for doing a rebalance.");
                        KafkaRebalanceStatus status = new KafkaRebalanceStatus();
                        return Future.succeededFuture(updateStatus(kafkaRebalance, status,
                                new InvalidResourceException(CruiseControlIssues.cruiseControlDisabled.getMessage())));
                    }

                    String ccSecretName =  CruiseControlResources.secretName(clusterName);
                    String ccApiSecretName =  CruiseControlResources.apiSecretName(clusterName);

                    Future<Secret> ccSecretFuture = secretOperations.getAsync(clusterNamespace, ccSecretName);
                    Future<Secret> ccApiSecretFuture = secretOperations.getAsync(clusterNamespace, ccApiSecretName);

                    return Future.join(ccSecretFuture, ccApiSecretFuture)
                            .compose(compositeFuture -> {

                                Secret ccSecret = compositeFuture.resultAt(0);
                                if (ccSecret == null) {
                                    return Future.failedFuture(Util.missingSecretException(clusterNamespace, ccSecretName));
                                }

                                Secret ccApiSecret = compositeFuture.resultAt(1);
                                if (ccApiSecret == null) {
                                    return Future.failedFuture(Util.missingSecretException(clusterNamespace, ccApiSecretName));
                                }

                                CruiseControlConfiguration ccConfig = new CruiseControlConfiguration(reconciliation, kafka.getSpec().getCruiseControl().getConfig().entrySet(), Map.of());
                                boolean apiAuthEnabled = ccConfig.isApiAuthEnabled();
                                boolean apiSslEnabled = ccConfig.isApiSslEnabled();
                                CruiseControlApi apiClient = cruiseControlClientProvider(ccSecret, ccApiSecret, apiAuthEnabled, apiSslEnabled);

                                // get latest KafkaRebalance state as it may have changed (see the patching above with "refresh" annotation)
                                return kafkaRebalanceOperator.getAsync(kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName())
                                        .compose(currentKafkaRebalance -> {
                                            KafkaRebalanceStatus kafkaRebalanceStatus = currentKafkaRebalance.getStatus();
                                            KafkaRebalanceState currentState;
                                            // cluster rebalance is new or it is in one of the others states
                                            if (kafkaRebalanceStatus == null) {
                                                currentState = KafkaRebalanceState.New;
                                            } else {
                                                String rebalanceStateType = rebalanceStateConditionType(kafkaRebalanceStatus);
                                                if (rebalanceStateType == null) {
                                                    throw new RuntimeException("Unable to find KafkaRebalance State in current KafkaRebalance status");
                                                }
                                                currentState = KafkaRebalanceState.valueOf(rebalanceStateType);
                                            }
                                            return reconcile(reconciliation, cruiseControlHost(clusterName, clusterNamespace),
                                                    apiClient, currentKafkaRebalance, currentState);
                                        }, exception -> Future.failedFuture(exception));
                            });
                }, exception -> Future.succeededFuture(updateStatus(kafkaRebalance, new KafkaRebalanceStatus(), exception)));
    }

    private boolean isKafkaClusterReady(Kafka kafka) {
        return kafka.getStatus() != null
                && kafka.getStatus().getConditions() != null
                && kafka.getStatus().getConditions().stream().anyMatch(condition -> condition.getType().equals("Ready") && condition.getStatus().equals("True"));
    }

    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> requestRebalance(Reconciliation reconciliation,
                                                          String host, CruiseControlApi apiClient, KafkaRebalance kafkaRebalance,
                                                          boolean dryrun, AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder) {
        return requestRebalance(reconciliation, host, apiClient, kafkaRebalance, dryrun, rebalanceOptionsBuilder, null);
    }


    private Future<MapAndStatus<ConfigMap, KafkaRebalanceStatus>> requestRebalance(Reconciliation reconciliation, String host, CruiseControlApi apiClient, KafkaRebalance kafkaRebalance,
                                                                                   boolean dryrun,
                                                                                   AbstractRebalanceOptions.AbstractRebalanceOptionsBuilder<?, ?> rebalanceOptionsBuilder, String userTaskID) {

        LOGGER.infoCr(reconciliation, "Requesting Cruise Control rebalance [dryrun={}]", dryrun);
        rebalanceOptionsBuilder.withVerboseResponse();
        if (!dryrun) {
            rebalanceOptionsBuilder.withFullRun();
        }
        // backward compatibility, no mode specified means "full"
        KafkaRebalanceMode mode = Optional.ofNullable(kafkaRebalance.getSpec())
                .map(spec -> spec.getMode())
                .orElse(KafkaRebalanceMode.FULL);

        Future<CruiseControlRebalanceResponse> future;
        switch (mode) {
            case ADD_BROKERS:
                future = apiClient.addBroker(reconciliation, host, cruiseControlPort, ((AddBrokerOptions.AddBrokerOptionsBuilder) rebalanceOptionsBuilder).build(), userTaskID);
                break;
            case REMOVE_BROKERS:
                future = apiClient.removeBroker(reconciliation, host, cruiseControlPort, ((RemoveBrokerOptions.RemoveBrokerOptionsBuilder) rebalanceOptionsBuilder).build(), userTaskID);
                break;
            default:
                future = apiClient.rebalance(reconciliation, host, cruiseControlPort, ((RebalanceOptions.RebalanceOptionsBuilder) rebalanceOptionsBuilder).build(), userTaskID);
                break;
        }
        return future.map(response -> handleRebalanceResponse(reconciliation, kafkaRebalance, dryrun, response));
    }

    private MapAndStatus<ConfigMap, KafkaRebalanceStatus> handleRebalanceResponse(Reconciliation reconciliation, KafkaRebalance kafkaRebalance, boolean dryrun, CruiseControlRebalanceResponse response) {
        if (dryrun) {
            if (response.isNotEnoughDataForProposal()) {
                // If there is not enough data for a rebalance, it's an error at the Cruise Control level
                // Need to re-request the proposal at a later time so move to the PendingProposal State.
                return buildRebalanceStatus(null, KafkaRebalanceState.PendingProposal, StatusUtils.validate(reconciliation, kafkaRebalance));
            } else if (response.isProposalStillCalculating()) {
                // If rebalance proposal is still being processed, we need to re-request the proposal at a later time
                // with the corresponding session-id so we move to the PendingProposal State.
                return buildRebalanceStatus(response.getUserTaskId(), KafkaRebalanceState.PendingProposal, StatusUtils.validate(reconciliation, kafkaRebalance));
            }
        } else {
            if (response.isNotEnoughDataForProposal()) {
                // We do not include a session id with this status as we do not want to retrieve the state of
                // this failed tasks (COMPLETED_WITH_ERROR)
                return buildRebalanceStatus(null, KafkaRebalanceState.PendingProposal, StatusUtils.validate(reconciliation, kafkaRebalance));
            } else if (response.isProposalStillCalculating()) {
                // If dryrun=false and the proposal is not ready we are going to be in a rebalancing state as
                // soon as it is ready, so set the state to rebalancing.
                // In the onRebalancing method the optimization proposal will be added when it is ready.
                return buildRebalanceStatus(response.getUserTaskId(), KafkaRebalanceState.Rebalancing, StatusUtils.validate(reconciliation, kafkaRebalance));
            }
        }

        if (response.getJson() != null && response.getJson().containsKey(CruiseControlRebalanceKeys.SUMMARY.getKey())) {
            // If there is enough data and the proposal is complete (the response has the "summary" key) then we move
            // to ProposalReady for a dry run or to the Rebalancing state for a full run
            KafkaRebalanceState ready = dryrun ? KafkaRebalanceState.ProposalReady : KafkaRebalanceState.Rebalancing;
            return buildRebalanceStatus(kafkaRebalance, response.getUserTaskId(), ready, response.getJson(), StatusUtils.validate(reconciliation, kafkaRebalance));
        } else {
            throw new CruiseControlRestException("Rebalance returned unknown response: " + response);
        }
    }

    /**
     * Return the {@code RebalanceAnnotation} enum value for the raw String value of the strimzi.io/rebalance annotation
     * set on the provided KafkaRebalance resource instance.
     * If the annotation is not set it returns {@code RebalanceAnnotation.none} while if it's a not valid value, it
     * returns {@code RebalanceAnnotation.unknown}.
     *
     * @param kafkaRebalance KafkaRebalance resource instance from which getting the value of the strimzio.io/rebalance annotation
     * @return the {@code RebalanceAnnotation} enum value for the raw String value of the strimzio.io/rebalance annotation
     */
    /* test */ KafkaRebalanceAnnotation rebalanceAnnotation(KafkaRebalance kafkaRebalance) {
        String rebalanceAnnotationValue = rawRebalanceAnnotation(kafkaRebalance);
        KafkaRebalanceAnnotation rebalanceAnnotation;

        try {
            if (rebalanceAnnotationValue == null) {
                rebalanceAnnotation = KafkaRebalanceAnnotation.none;
            } else if (rebalanceAnnotationValue.equals("none")) {
                rebalanceAnnotation = KafkaRebalanceAnnotation.unknown;
            } else {
                rebalanceAnnotation = KafkaRebalanceAnnotation.valueOf(rebalanceAnnotationValue);
            }
        } catch (IllegalArgumentException e) {
            rebalanceAnnotation = KafkaRebalanceAnnotation.unknown;
        }
        return rebalanceAnnotation;
    }

    /**
     * Return the raw String value of the strimzio.io/rebalance annotation, if exists, on the provided
     * KafkaRebalance resource instance otherwise return null
     *
     * @param kafkaRebalance KafkaRebalance resource instance from which getting the value of the strimzio.io/rebalance annotation
     * @return the value for the strimzio.io/rebalance annotation on the provided KafkaRebalance resource instance
     */
    private String rawRebalanceAnnotation(KafkaRebalance kafkaRebalance) {
        return hasRebalanceAnnotation(kafkaRebalance) ?
                kafkaRebalance.getMetadata().getAnnotations().get(ANNO_STRIMZI_IO_REBALANCE) : null;

    }

    /**
     * Return warning condition if the rebalance annotation wrong corresponding to the state
     *
     * @param reconciliation        Reconciliation marker
     * @param conditions            Set of conditions
     * @param state                 Contains the KafkaRebalanceState for which we are checking the valid annotation
     * @param rebalanceAnnotation   Contains the rebalance annotation
     * @param kafkaRebalance        Kafka Rebalance resource
     */

    private void validateAnnotation(Reconciliation reconciliation, Set<Condition> conditions, KafkaRebalanceState state, KafkaRebalanceAnnotation rebalanceAnnotation, KafkaRebalance kafkaRebalance) {
        String warningMessage = "Wrong annotation value: " + kafkaRebalance.getMetadata().getAnnotations().get(ANNO_STRIMZI_IO_REBALANCE)
                + ". The rebalance resource supports the following annotations in " + state.toString() + " state: " + state.getValidAnnotations();
        if (rebalanceAnnotation != KafkaRebalanceAnnotation.none) {
            if (!state.isValidateAnnotation(rebalanceAnnotation)) {
                LOGGER.warnCr(reconciliation, warningMessage);
                conditions.add(StatusUtils.buildWarningCondition("InvalidAnnotation", warningMessage));
            }
        }
    }

    /**
     * Return true if the provided KafkaRebalance resource instance has the strimzio.io/rebalance annotation
     *
     * @param kafkaRebalance KafkaRebalance resource instance to check
     * @return if the provided KafkaRebalance resource instance has the strimzio.io/rebalance annotation
     */
    private boolean hasRebalanceAnnotation(KafkaRebalance kafkaRebalance) {
        return kafkaRebalance.getMetadata().getAnnotations() != null &&
                kafkaRebalance.getMetadata().getAnnotations().containsKey(ANNO_STRIMZI_IO_REBALANCE);
    }

    private boolean shouldRenewRebalance(KafkaRebalance kafkaRebalance, KafkaRebalanceAnnotation rebalanceAnnotation) {
        // Checks if `refresh` annotation applied or request are made when CC is not active
        // We check for `CruiseControlRetriableConnectionException` which is thrown if requests are made when CC was not active. Not handling this case will move the KafkaRebalance to `NotReady` state
        // and it will stay in `NotReady` state until we apply `refresh` annotation
        if (rebalanceAnnotation == KafkaRebalanceAnnotation.refresh
                || kafkaRebalance.getStatus().getConditions().stream().anyMatch(condition -> "CruiseControlRetriableConnectionException".equals(condition.getReason()))) {
            return true;
        } else {
            return CruiseControlIssues.checkForMatch(kafkaRebalance.getStatus().getConditions());
        }
    }

    /**
     * Contains the issues that will be picked up in the periodic reconciliation, once corrected.
     */
    private enum CruiseControlIssues {

        /**
         * The Kafka cluster label is missing .
         */
        clusterLabelMissing("Resource lacks label '" + Labels.STRIMZI_CLUSTER_LABEL + "': No cluster related to a possible rebalance."),

        /**
         * Cruise Control was not declared in the Kafka Resource
         */
        cruiseControlDisabled("Kafka resource lacks 'cruiseControl' declaration"),

        /**
         * Kafka Cluster is not ready
         */
        kafkaClusterNotReady("Kafka cluster is not Ready");

        public String getMessage() {
            return message;
        }

        private final String message;

        CruiseControlIssues(String message) {
            this.message = message;
        }

        public static boolean checkForMatch(List<Condition> conditions) {
            for (CruiseControlIssues issue : CruiseControlIssues.values()) {
                if (conditions.stream().anyMatch(condition -> issue.getMessage().equals(condition.getMessage()))) {
                    return true;
                }
            }
            return false;
        }
    }

    private KafkaRebalanceState state(KafkaRebalance kafkaRebalance) {
        KafkaRebalanceStatus rebalanceStatus = kafkaRebalance.getStatus();
        if (rebalanceStatus != null) {
            String statusString = rebalanceStateConditionType(rebalanceStatus);
            if (statusString != null) {
                return KafkaRebalanceState.valueOf(statusString);
            }
        }
        return null;
    }

    @Override
    protected Future<KafkaRebalanceStatus> createOrUpdate(Reconciliation reconciliation, KafkaRebalance resource) {
        return reconcileRebalance(reconciliation, resource);
    }

    @Override
    protected Future<Boolean> delete(Reconciliation reconciliation) {
        return reconcileRebalance(reconciliation, null).map(v -> Boolean.TRUE);
    }

    @Override
    protected KafkaRebalanceStatus createStatus(KafkaRebalance ignored) {
        return new KafkaRebalanceStatus();
    }
}

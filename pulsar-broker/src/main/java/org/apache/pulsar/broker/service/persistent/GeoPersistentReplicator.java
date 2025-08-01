/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service.persistent;

import static org.apache.pulsar.client.impl.GeoReplicationProducerImpl.MSG_PROP_REPL_SOURCE_POSITION;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.transaction.TxnID;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.protocol.Markers;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.util.FutureUtil;

@Slf4j
public class GeoPersistentReplicator extends PersistentReplicator {

    public GeoPersistentReplicator(PersistentTopic topic, ManagedCursor cursor, String localCluster,
                                   String remoteCluster, BrokerService brokerService,
                                   PulsarClientImpl replicationClient)
            throws PulsarServerException {
        super(localCluster, topic, cursor, remoteCluster, topic.getName(), brokerService, replicationClient);
    }

    /**
     * @return Producer name format : replicatorPrefix.localCluster-->remoteCluster
     */
    @Override
    protected String getProducerName() {
        return getReplicatorName(replicatorPrefix, localCluster) + REPL_PRODUCER_NAME_DELIMITER + remoteCluster;
    }

    @Override
    protected CompletableFuture<Void> prepareCreateProducer() {
        if (brokerService.getPulsar().getConfig().isCreateTopicToRemoteClusterForReplication()) {
            return CompletableFuture.completedFuture(null);
        } else {
            CompletableFuture<Void> topicCheckFuture = new CompletableFuture<>();
            replicationClient.getPartitionedTopicMetadata(localTopic.getName(), false, false)
                    .whenComplete((metadata, ex) -> {
                if (ex == null) {
                    if (metadata.partitions == 0) {
                        topicCheckFuture.complete(null);
                    } else {
                        String errorMsg = String.format("{} Can not create the replicator due to the partitions in the"
                                        + " remote cluster is not 0, but is %s",
                                replicatorId, metadata.partitions);
                        log.error(errorMsg);
                        topicCheckFuture.completeExceptionally(
                                new PulsarClientException.NotAllowedException(errorMsg));
                    }
                } else {
                    topicCheckFuture.completeExceptionally(FutureUtil.unwrapCompletionException(ex));
                }
            });
            return topicCheckFuture;
        }
    }

    @Override
    protected boolean replicateEntries(List<Entry> entries, final InFlightTask inFlightTask) {
        boolean atLeastOneMessageSentForReplication = false;
        boolean isEnableReplicatedSubscriptions =
                brokerService.pulsar().getConfiguration().isEnableReplicatedSubscriptions();

        try {
            // This flag is set to true when we skip at least one local message,
            // in order to skip remaining local messages.
            boolean isLocalMessageSkippedOnce = false;
            boolean skipRemainingMessages = inFlightTask.isSkipReadResultDueToCursorRewind();
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                // Skip the messages since the replicator need to fetch the schema info to replicate the schema to the
                // remote cluster. Rewind the cursor first and continue the message read after fetched the schema.
                if (skipRemainingMessages) {
                    inFlightTask.incCompletedEntries();
                    entry.release();
                    continue;
                }
                int length = entry.getLength();
                ByteBuf headersAndPayload = entry.getDataBuffer();
                MessageImpl msg;
                try {
                    msg = MessageImpl.deserializeSkipBrokerEntryMetaData(headersAndPayload);
                } catch (Throwable t) {
                    log.error("[{}] Failed to deserialize message at {} (buffer size: {}): {}", replicatorId,
                            entry.getPosition(), length, t.getMessage(), t);
                    cursor.asyncDelete(entry.getPosition(), this, entry.getPosition());
                    inFlightTask.incCompletedEntries();
                    entry.release();
                    continue;
                }

                if (Markers.isTxnMarker(msg.getMessageBuilder())) {
                    cursor.asyncDelete(entry.getPosition(), this, entry.getPosition());
                    inFlightTask.incCompletedEntries();
                    entry.release();
                    msg.recycle();
                    continue;
                }
                if (msg.getMessageBuilder().hasTxnidLeastBits() && msg.getMessageBuilder().hasTxnidMostBits()) {
                    TxnID tx = new TxnID(msg.getMessageBuilder().getTxnidMostBits(),
                            msg.getMessageBuilder().getTxnidLeastBits());
                    if (topic.isTxnAborted(tx, entry.getPosition())) {
                        cursor.asyncDelete(entry.getPosition(), this, entry.getPosition());
                        inFlightTask.incCompletedEntries();
                        entry.release();
                        msg.recycle();
                        continue;
                    }
                }

                if (isEnableReplicatedSubscriptions) {
                    checkReplicatedSubscriptionMarker(entry.getPosition(), msg, headersAndPayload);
                }

                if (msg.isReplicated()) {
                    // Discard messages that were already replicated into this region
                    cursor.asyncDelete(entry.getPosition(), this, entry.getPosition());
                    inFlightTask.incCompletedEntries();
                    entry.release();
                    msg.recycle();
                    continue;
                }

                if (msg.hasReplicateTo() && !msg.getReplicateTo().contains(remoteCluster)) {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Skipping message at position {}, replicateTo {}", replicatorId,
                                entry.getPosition(), msg.getReplicateTo());
                    }
                    cursor.asyncDelete(entry.getPosition(), this, entry.getPosition());
                    inFlightTask.incCompletedEntries();
                    entry.release();
                    msg.recycle();
                    continue;
                }

                if (msg.isExpired(messageTTLInSeconds)) {
                    msgExpired.recordEvent(0 /* no value stat */);
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Discarding expired message at position {}, replicateTo {}",
                                replicatorId, entry.getPosition(), msg.getReplicateTo());
                    }
                    cursor.asyncDelete(entry.getPosition(), this, entry.getPosition());
                    inFlightTask.incCompletedEntries();
                    entry.release();
                    msg.recycle();
                    continue;
                }

                if (STATE_UPDATER.get(this) != State.Started || isLocalMessageSkippedOnce) {
                    // The producer is not ready yet after having stopped/restarted. Drop the message because it will
                    // recover when the producer is ready
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Dropping read message at {} because producer is not ready",
                                replicatorId, entry.getPosition());
                    }
                    isLocalMessageSkippedOnce = true;
                    inFlightTask.incCompletedEntries();
                    entry.release();
                    msg.recycle();
                    continue;
                }

                dispatchRateLimiter.ifPresent(rateLimiter -> rateLimiter.consumeDispatchQuota(1, entry.getLength()));
                msg.setReplicatedFrom(localCluster);

                headersAndPayload.retain();

                CompletableFuture<SchemaInfo> schemaFuture = getSchemaInfo(msg);
                if (!schemaFuture.isDone() || schemaFuture.isCompletedExceptionally()) {
                    /**
                     * Skip in flight reading tasks.
                     * Explain the result of the race-condition between:
                     *   - {@link #readMoreEntries}
                     *   - {@link #beforeTerminateOrCursorRewinding(ReasonOfWaitForCursorRewinding)}
                     * Since {@link #acquirePermitsIfNotFetchingSchema} and
                     *   {@link #beforeTerminateOrCursorRewinding(ReasonOfWaitForCursorRewinding)} acquire the
                     * same lock, it is safe.
                     */
                    beforeTerminateOrCursorRewinding(ReasonOfWaitForCursorRewinding.Fetching_Schema);
                    inFlightTask.incCompletedEntries();
                    entry.release();
                    headersAndPayload.release();
                    msg.recycle();
                    // Mark the replicator is fetching the schema for now and rewind the cursor
                    // and trigger the next read after complete the schema fetching.
                    skipRemainingMessages = true;
                    log.info("[{}] Pause the data replication due to new detected schema", replicatorId);
                    schemaFuture.whenComplete((__, e) -> {
                        if (e != null) {
                            log.warn("[{}] Failed to get schema from local cluster, will try in the next loop",
                                    replicatorId, e);
                        }
                        log.info("[{}] Resume the data replication after the schema fetching done", replicatorId);
                        doRewindCursor(true);
                    });
                } else {
                    msg.setSchemaInfoForReplicator(schemaFuture.get());
                    msg.getMessageBuilder().clearTxnidMostBits();
                    msg.getMessageBuilder().clearTxnidLeastBits();
                    // Add props for sequence checking.
                    msg.getMessageBuilder().addProperty().setKey(MSG_PROP_REPL_SOURCE_POSITION)
                            .setValue(String.format("%s:%s", entry.getLedgerId(), entry.getEntryId()));
                    msgOut.recordEvent(headersAndPayload.readableBytes());
                    stats.incrementMsgOutCounter();
                    stats.incrementBytesOutCounter(headersAndPayload.readableBytes());
                    // Increment pending messages for messages produced locally
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Publishing {}:{}", replicatorId, entry.getLedgerId(), entry.getEntryId());
                    }
                    producer.sendAsync(msg, ProducerSendCallback.create(this, entry, msg, inFlightTask));
                    atLeastOneMessageSentForReplication = true;
                }
            }
        } catch (Exception e) {
            log.error("[{}] Unexpected exception in replication task: {}", replicatorId, e.getMessage(), e);
        }
        return atLeastOneMessageSentForReplication;
    }
}

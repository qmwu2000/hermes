package com.ctrip.hermes.broker.longpolling;

import io.netty.channel.Channel;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.annotation.Named;

import com.ctrip.hermes.broker.queue.MessageQueueCursor;
import com.ctrip.hermes.core.bo.Tpg;
import com.ctrip.hermes.core.bo.Tpp;
import com.ctrip.hermes.core.lease.Lease;
import com.ctrip.hermes.core.log.BizEvent;
import com.ctrip.hermes.core.log.BizLogger;
import com.ctrip.hermes.core.message.TppConsumerMessageBatch;
import com.ctrip.hermes.core.message.TppConsumerMessageBatch.MessageMeta;
import com.ctrip.hermes.core.transport.netty.NettyUtils;
import com.ctrip.hermes.core.utils.HermesThreadFactory;

/**
 * @author Leo Liang(jhliang@ctrip.com)
 *
 */
@Named(type = LongPollingService.class)
public class DefaultLongPollingService extends AbstractLongPollingService implements Initializable {

	@Inject
	private BizLogger m_bizLogger;

	private static final Logger log = LoggerFactory.getLogger(DefaultLongPollingService.class);

	private ScheduledExecutorService m_scheduledThreadPool;

	@Override
	public void initialize() throws InitializationException {
		m_scheduledThreadPool = Executors.newScheduledThreadPool(m_config.getLongPollingServiceThreadCount(),
		      HermesThreadFactory.create("LongPollingService", false));
	}

	@Override
	public void schedulePush(Tpg tpg, long correlationId, int batchSize, Channel channel, long expireTime,
	      Lease brokerLease) {
		if (log.isDebugEnabled()) {
			log.debug("Schedule push for client(correlationId={}, topic={}, partition={}, groupId={})", correlationId,
			      tpg.getTopic(), tpg.getPartition(), tpg.getGroupId());
		}

		final PullMessageTask pullMessageTask = new PullMessageTask(tpg, correlationId, batchSize, channel, expireTime,
		      brokerLease);

		if (m_stopped.get()) {
			response(pullMessageTask, null);
		}

		m_scheduledThreadPool.submit(new Runnable() {

			@Override
			public void run() {
				executeTask(pullMessageTask);
			}

		});
	}

	private void executeTask(final PullMessageTask pullMessageTask) {
		if (m_stopped.get()) {
			return;
		}
		try {
			// skip expired task
			if (pullMessageTask.getExpireTime() < m_systemClockService.now()) {
				if (log.isDebugEnabled()) {
					log.debug("Client expired(correlationId={}, topic={}, partition={}, groupId={})", pullMessageTask
					      .getCorrelationId(), pullMessageTask.getTpg().getTopic(), pullMessageTask.getTpg().getPartition(),
					      pullMessageTask.getTpg().getGroupId());
				}
				return;
			}

			if (!pullMessageTask.getBrokerLease().isExpired()) {
				if (!queryAndResponseData(pullMessageTask)) {
					if (!m_stopped.get()) {
						m_scheduledThreadPool.schedule(new Runnable() {

							@Override
							public void run() {
								executeTask(pullMessageTask);
							}
						}, m_config.getLongPollingCheckIntervalMillis(), TimeUnit.MILLISECONDS);
					}
				}
			} else {
				if (log.isDebugEnabled()) {
					log.debug("Broker no lease for this request(correlationId={}, topic={}, partition={}, groupId={})",
					      pullMessageTask.getCorrelationId(), pullMessageTask.getTpg().getTopic(), pullMessageTask.getTpg()
					            .getPartition(), pullMessageTask.getTpg().getGroupId());
				}
				// no lease, return empty cmd
				response(pullMessageTask, null);
			}
		} catch (Exception e) {
			log.error("Exception occurred while executing pull message task", e);
		}
	}

	private boolean queryAndResponseData(PullMessageTask pullTask) {
		Tpg tpg = pullTask.getTpg();

		MessageQueueCursor cursor = m_queueManager.getCursor(tpg, pullTask.getBrokerLease());

		if (cursor == null) {
			return false;
		}

		List<TppConsumerMessageBatch> batches = null;

		batches = cursor.next(pullTask.getBatchSize());

		if (batches != null && !batches.isEmpty()) {

			String ip = NettyUtils.parseChannelRemoteAddr(pullTask.getChannel(), false);
			for (TppConsumerMessageBatch batch : batches) {
				m_ackManager.delivered(new Tpp(batch.getTopic(), batch.getPartition(), batch.isPriority()),
				      tpg.getGroupId(), batch.isResend(), batch.getMessageMetas());

				bizLogDelivered(ip, batch.getMessageMetas(), tpg);
			}

			response(pullTask, batches);
			return true;
		} else {
			return false;
		}
	}

	private void bizLogDelivered(String ip, List<MessageMeta> metas, Tpg tpg) {
		for (MessageMeta meta : metas) {
			BizEvent event = new BizEvent("Message.Delivered");
			event.addData("msgId", meta.getOriginId());
			event.addData("topic", tpg.getTopic());
			event.addData("consumerIp", ip);
			event.addData("groupId", tpg.getGroupId());

			m_bizLogger.log(event);
		}
	}

	@Override
	protected void doStop() {
		m_scheduledThreadPool.shutdown();
	}
}

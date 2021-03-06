package com.ctrip.hermes.rest.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Properties;

import javax.ws.rs.core.Response;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.config.RequestConfig.Builder;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.annotation.Named;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;
import com.ctrip.hermes.consumer.api.BaseMessageListener;
import com.ctrip.hermes.consumer.api.Consumer;
import com.ctrip.hermes.consumer.api.Consumer.ConsumerHolder;
import com.ctrip.hermes.core.bo.SubscriptionView;
import com.ctrip.hermes.core.env.ClientEnvironment;
import com.ctrip.hermes.core.log.BizEvent;
import com.ctrip.hermes.core.log.BizLogger;
import com.ctrip.hermes.core.message.ConsumerMessage;
import com.ctrip.hermes.core.message.ConsumerMessage.MessageStatus;
import com.ctrip.hermes.core.message.payload.RawMessage;

@Named
public class MessagePushService implements Initializable {

	private static final Logger m_logger = LoggerFactory.getLogger(MessagePushService.class);

	@Inject
	private BizLogger m_bizLogger;

	@Inject
	private ClientEnvironment m_env;

	private HttpClient m_httpClient;

	private RequestConfig m_requestConfig;

	@Inject
	private MetricsManager m_metricsManager;

	@Override
	public void initialize() throws InitializationException {
		PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
		cm.setMaxTotal(100);

		m_httpClient = HttpClients.custom().setConnectionManager(cm).build();

		Builder b = RequestConfig.custom();
		Properties globalConfig = m_env.getGlobalConfig();
		// TODO config
		b.setConnectTimeout(Integer.valueOf(globalConfig.getProperty("gateway.subcription.connect.timeout", "2000")));
		b.setSocketTimeout(Integer.valueOf(globalConfig.getProperty("gateway.subscription.socket.timeout", "5000")));
		m_requestConfig = b.build();
	}

	public ConsumerHolder startPusher(final SubscriptionView sub) {
		final Meter success_meter = m_metricsManager.meter("push_success", sub.getTopic(), sub.getGroup(), sub
		      .getEndpoints().toString());

		final Meter failed_meter = m_metricsManager.meter("push_fail", sub.getTopic(), sub.getGroup(), sub.getEndpoints()
		      .toString());

		final Timer push_timer = m_metricsManager.timer("push_timer", sub.getTopic(), sub.getGroup(), sub.getEndpoints()
		      .toString());

		final String[] urls = sub.getEndpoints().split(",");

		final ConsumerHolder consumerHolder = Consumer.getInstance().start(sub.getTopic(), sub.getGroup(),
		      new BaseMessageListener<RawMessage>(sub.getGroup()) {

			      @Override
			      protected void onMessage(final ConsumerMessage<RawMessage> msg) {
				      while (msg.getStatus() != MessageStatus.SUCCESS) {
					      if (failed_meter.getOneMinuteRate() > 0.5) {
						      m_logger.warn("Suspend current push");
						      break;
					      }

					      for (final String url : urls) {
						      BizEvent pushEvent = new BizEvent("Rest.push");
						      HttpResponse pushResponse = null;
						      try {
							      Context time = push_timer.time();
							      pushEvent.addData("topic", sub.getTopic());
							      pushEvent.addData("group", sub.getGroup());
							      pushEvent.addData("refKey", msg.getRefKey());
							      pushEvent.addData("endpoint", url);

							      pushResponse = pushMessage(msg, url);
							      time.stop();

							      pushEvent.addData("result", pushResponse.getStatusLine().getStatusCode());
							      if (pushResponse.getStatusLine().getStatusCode() == Response.Status.OK.getStatusCode()) {
								      msg.ack();
								      success_meter.mark();
								      return;
							      } else if (pushResponse.getStatusLine().getStatusCode() >= Response.Status.INTERNAL_SERVER_ERROR
							            .getStatusCode()) {
								      msg.nack();
								      failed_meter.mark();
								      return;
							      } else {
								      m_logger.warn("Push message failed, reason:{} msg:{} url:{}", pushResponse
								            .getStatusLine().getReasonPhrase(), msg.getBody(), url);
								      failed_meter.mark();
								      continue;
							      }
						      } catch (Exception e) {
							      m_logger.warn("Push message failed", e);
							      failed_meter.mark();
						      } finally {
							      m_bizLogger.log(pushEvent);
						      }
					      }
				      }
			      }
		      });
		return consumerHolder;
	}

	private HttpResponse pushMessage(ConsumerMessage<RawMessage> msg, String url) throws IOException {
		HttpPost post = new HttpPost(url);
		HttpResponse response = null;
		try {
			post.setConfig(m_requestConfig);
			ByteArrayInputStream stream = new ByteArrayInputStream(msg.getBody().getEncodedMessage());
			// TODO Leave here for future show
			// post.setEntity(new StringEntity(new String(msg.getBody().getEncodedMessage())));
			post.addHeader("X-Hermes-Topic", msg.getTopic());
			post.addHeader("X-Hermes-Ref-Key", msg.getRefKey());
			Iterator<String> propertyNames = msg.getPropertyNames();
			StringBuffer sb = new StringBuffer();
			while (propertyNames.hasNext()) {
				String key = propertyNames.next();
				String value = msg.getProperty(key);
				sb.append(key).append('=').append(value);
			}
			if (sb.length() > 0) {
				post.addHeader("X-Hermes-Message-Property", sb.toString());
			}
			post.setEntity(new InputStreamEntity(stream, ContentType.APPLICATION_OCTET_STREAM));
			response = m_httpClient.execute(post);
		} finally {
			post.reset();
		}
		return response;
	}
}
